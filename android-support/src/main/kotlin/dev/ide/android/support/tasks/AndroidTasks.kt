package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Aapt2
import dev.ide.android.support.tools.ApkSigner
import dev.ide.android.support.tools.DesugaredLibrary
import dev.ide.android.support.tools.DexDiagnostics
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.L8Request
import dev.ide.android.support.tools.MergePlan
import dev.ide.android.support.tools.OffHeapArchiveDexer
import dev.ide.android.support.tools.ResourceShrink
import dev.ide.android.support.tools.ShrinkRequest
import dev.ide.android.support.tools.Shrinker
import dev.ide.android.support.tools.SigningConfig
import dev.ide.android.support.viewbinding.LayoutBindingModel
import dev.ide.android.support.viewbinding.ViewBindingJavaSource
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.DiagnosticKind
import dev.ide.build.TaskResult
import dev.ide.build.engine.reportToolDiagnostics
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.lang.kotlin.compile.BUILTIN_KOTLIN_COMPILER_PLUGINS
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KotlinCompilerPlugin
import dev.ide.lang.kotlin.compile.resolveFor
import dev.ide.model.Module
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.stream.Collectors
import java.util.zip.ZipFile
import kotlin.io.path.writeText

/**
 * The native Android pipeline as discrete, independently-incremental [Task]s. Each
 * declares typed inputs/outputs read from *live* content at fingerprint time, so build-engine skips a
 * step whose inputs are unchanged. Paths are resolved by [dev.ide.android.support.AndroidBuildSystem];
 * the tools are injected ports, so these tasks contain no tool specifics.
 */

/**
 * `mergeResources`: merge resource directories from all sources — dependency `android-lib` modules, AAR
 * libraries, then the app's own source sets — into one merged `res/` dir. [resDirs] is in *ascending*
 * priority, so a later (higher-priority) source overrides an earlier one for the same resource.
 *
 * Non-`values` files overlay by path (higher priority wins). `values*` files are **merged by entry**, AGP-
 * style: every `<resources>` child is keyed by (qualifier, tag, type, name) and a later source overrides an
 * earlier one, emitting ONE `values.xml` per qualifier. This deduplicates a resource that arrives from more
 * than one source — e.g. the same library reached through two cache paths, or a wrapper AAR plus the AAR it
 * forwards to — which would otherwise reach `aapt2 link` as two definitions and fail with "resource X has a
 * conflicting value for configuration". (The previous approach copied every contribution under a unique name
 * and relied on aapt2 to overlay them, which it does not do across a single linked source set.)
 */
internal class MergeResourcesTask(
    override val name: TaskName,
    private val resDirs: List<Path>,
    private val outDir: Path,
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply { dirPaths("res", resDirs) }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("merged", outDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        mergeResourceDirs(resDirs, outDir)
        ctx.logger()("mergeResources -> ${outDir.fileName}")
        return TaskResult.Success
    }
}

/**
 * Merge [resDirs] (ascending priority) into [outDir] — the reusable core of [MergeResourcesTask], also driven
 * by the layout preview's live resource relink ([dev.ide.android.support.PreviewResourceLinker]). Non-`values`
 * files overlay by RESOURCE IDENTITY (higher priority wins, extension-independent); `values*` files merge by
 * entry (last wins), deduplicating a resource that arrives from more than one source so it reaches `aapt2 link`
 * once. A values file that fails to parse is copied verbatim under a unique name (so a single broken file
 * doesn't drop the rest of the merge).
 */
internal fun mergeResourceDirs(resDirs: List<Path>, outDir: Path) {
    if (Files.exists(outDir)) Files.walk(outDir).use { s ->
        s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }
    Files.createDirectories(outDir)
    fun copyRaw(from: Path, to: Path) {
        to.parent?.let { Files.createDirectories(it) }
        Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    val values = ValuesMerger()
    // File resources overlay by RESOURCE IDENTITY (qualifier dir + name, extension-INDEPENDENT), not by file
    // name — so a higher-priority source's `ic_launcher.png` overrides a lower one's `ic_launcher.xml` instead
    // of BOTH landing in merged-res and failing aapt2 link with "resource 'drawable/ic_launcher' has a
    // conflicting value". Matches AGP's ResourceMerger (file resources keyed by folder-type + name + qualifier;
    // higher priority wins). Ascending priority means the later source in [resDirs] wins.
    val fileResOutput = HashMap<String, Path>()  // resource identity -> the merged path currently holding it
    resDirs.filter { Files.isDirectory(it) }.forEach { dir ->
        Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) }.sorted().forEach { f ->
                val rel = dir.relativize(f)
                val qualifier = rel.getName(0).toString()
                if (qualifier.startsWith("values")) {
                    // Accumulate entries; ascending priority means a later source's entry wins.
                    if (!values.add(qualifier, f)) copyRaw(f, outDir.resolve(qualifier).resolve("unparsed_${values.bump()}_${f.fileName}"))
                } else {
                    val dest = outDir.resolve(rel)
                    val id = "$qualifier/${fileResourceName(rel.fileName.toString())}"
                    // A prior same-identity file under a DIFFERENT name (extension) must go, or aapt2 sees two.
                    fileResOutput.put(id, dest)?.let { prior -> if (prior != dest) runCatching { Files.deleteIfExists(prior) } }
                    copyRaw(f, dest) // overlay: a higher-priority source overrides the same resource
                }
            }
        }
    }
    values.writeTo(outDir)
}

/** The aapt2 resource name of a res FILE, i.e. its name without the type extension (`ic_launcher.png` ->
 *  `ic_launcher`); a nine-patch keeps its base name (`bg.9.png` -> `bg`). Used so two files that declare the
 *  same resource in different formats collapse to one identity in [mergeResourceDirs]. */
private fun fileResourceName(fileName: String): String {
    val ninePatch = ".9.png"
    return if (fileName.endsWith(ninePatch, ignoreCase = true)) fileName.dropLast(ninePatch.length)
    else fileName.substringBeforeLast('.', fileName)
}

/**
 * Accumulates `<resources>` entries across all merged sources, keyed by (qualifier, tag, type, name) so a
 * later source overrides an earlier one (last-wins overlay), then emits one `values.xml` per qualifier with
 * the deduplicated set. Uses javax.xml DOM (available on both desktop and ART). A file that fails to parse
 * is reported via [add] returning false so the caller can fall back to copying it verbatim.
 */
private class ValuesMerger {
    // qualifier -> (entry key -> imported element); LinkedHashMap keeps a stable, insertion-ordered output.
    private val byQualifier = LinkedHashMap<String, LinkedHashMap<String, org.w3c.dom.Element>>()
    private val rootAttrs =
        LinkedHashMap<String, String>()   // xmlns:* (and other root attrs) seen on any <resources>
    private val doc =
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().newDocument()
    private var counter = 0

    fun bump(): Int = counter++

    /** Parse [file] and fold its entries into [qualifier]. Returns false if it could not be parsed as values XML. */
    fun add(qualifier: String, file: Path): Boolean {
        val parsed = runCatching {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(file.toFile())
        }.getOrNull() ?: return false
        val root = parsed.documentElement ?: return false
        if (root.tagName != "resources") return false
        // Preserve namespace declarations (xliff:, tools:, …) so imported entries that use them stay valid.
        val attrs = root.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName.startsWith("xmlns")) rootAttrs.putIfAbsent(a.nodeName, a.nodeValue)
        }
        val bucket = byQualifier.getOrPut(qualifier) { LinkedHashMap() }
        val children = root.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val el = n as org.w3c.dom.Element
            val name = el.getAttribute("name")
            val type = el.getAttribute("type")   // <item type="id" name="…">; empty for typed tags
            // Nameless entries (e.g. <eat-comment/>) can't be keyed for override — keep each uniquely.
            val key =
                if (name.isEmpty()) "${el.tagName}#${counter++}" else "${el.tagName}|$type|$name"
            val imported = doc.importNode(el, true) as org.w3c.dom.Element
            val prior = bucket[key]
            // <declare-styleable> is a grouping, not a value: aapt2 UNIONS the child <attr> of same-named
            // styleables across libraries rather than overriding by last-wins. AppCompat and Material both
            // ship <declare-styleable name="SearchView"> with disjoint attrs; replacing one with the other
            // drops a set of attr declarations (e.g. Material's dividerVisible/containedAnimationEnabled,
            // declared ONLY inside that styleable), so a style referencing them fails aapt2 link as "not found".
            if (prior != null && el.tagName == "declare-styleable") {
                unionStyleable(prior, imported)
            } else {
                bucket[key] = imported   // last source wins
            }
        }
        return true
    }

    /** Fold [incoming]'s child `<attr>` declarations into [target] (a same-named declare-styleable),
     *  deduping by attr name and keeping the richer declaration (the one bearing a `format` or enum/flag
     *  children) over a bare `<attr name="x"/>` reference. Matches aapt2's styleable union. */
    private fun unionStyleable(target: org.w3c.dom.Element, incoming: org.w3c.dom.Element) {
        val have = LinkedHashMap<String, org.w3c.dom.Element>()
        childAttrs(target).forEach { have[it.getAttribute("name")] = it }
        for (attr in childAttrs(incoming)) {            // snapshot list — safe to move nodes while iterating
            val nm = attr.getAttribute("name")
            if (nm.isEmpty()) {
                target.appendChild(attr); continue
            }
            val prior = have[nm]
            when {
                prior == null -> {
                    target.appendChild(attr); have[nm] = attr
                }
                richness(attr) > richness(prior) -> {
                    target.replaceChild(attr, prior); have[nm] = attr
                }
                // else: keep the existing (equal or richer) declaration
            }
        }
    }

    private fun childAttrs(el: org.w3c.dom.Element): List<org.w3c.dom.Element> {
        val out = ArrayList<org.w3c.dom.Element>()
        val ch = el.childNodes
        for (i in 0 until ch.length) {
            val n = ch.item(i)
            if (n.nodeType == org.w3c.dom.Node.ELEMENT_NODE && (n as org.w3c.dom.Element).tagName == "attr")
                out.add(n)
        }
        return out
    }

    /** An `<attr>` that declares the attribute (carries a `format` or enum/flag children) outranks one that
     *  merely references it (`<attr name="x"/>`), so the format-bearing declaration survives the union. */
    private fun richness(attr: org.w3c.dom.Element): Int {
        var s = 0
        if (attr.hasAttribute("format")) s++
        val ch = attr.childNodes
        for (i in 0 until ch.length) if (ch.item(i).nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
            s++; break
        }
        return s
    }

    /** Emit `<qualifier>/values.xml` for every accumulated qualifier. */
    fun writeTo(outDir: Path) {
        if (byQualifier.isEmpty()) return
        val tf = javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
            setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "utf-8")
        }
        for ((qualifier, entries) in byQualifier) {
            val out = doc.createElement("resources")
            rootAttrs.forEach { (k, v) -> out.setAttribute(k, v) }
            entries.values.forEach { out.appendChild(it) }
            val dir = outDir.resolve(qualifier)
            Files.createDirectories(dir)
            val target = dir.resolve("values.xml")
            Files.newOutputStream(target).use { os ->
                tf.transform(
                    javax.xml.transform.dom.DOMSource(out),
                    javax.xml.transform.stream.StreamResult(os)
                )
            }
            // appendChild moves nodes out of `out`; rebuild not needed since each entry is written once.
        }
    }
}

/**
 * `generateR`: build a non-final, library-local `R.java` from an `android-lib`'s own resources
 * (the reusable AAR/library R model). aapt2 `--non-final-ids` makes the `R` fields
 * non-`final`, so the compiler emits `getstatic` rather than inlining IDs; the library is then compiled
 * against this `R` independently of any app, and the *final* IDs are filled in by the app-generated `R`
 * (`--extra-packages`) at runtime. This `R` is compile-only — it is kept OUT of the library's dexed output.
 * A minimal manifest is synthesized when the library has none (only its package matters here).
 */
internal class GenerateLibraryRTask(
    override val name: TaskName,
    private val resDirs: List<Path>,
    private val manifest: Path,
    private val androidJar: Path,
    private val packageName: String,
    private val minSdk: Int,
    private val compiledResDir: Path,
    private val genDir: Path,
    private val throwawayAp: Path,
    private val synthManifest: Path,
    private val aapt2: Aapt2,
    /** When set, aapt2 also writes the R symbol table (`R.txt`) here — the AAR ships it for consumers. */
    private val rTxt: Path? = null,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("res", resDirs)
            if (Files.isRegularFile(manifest)) filePaths("manifest", listOf(manifest))
            property("package", packageName)
            property("androidJar", androidJar.toString())
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply {
        dirPath("R", genDir)
        rTxt?.let { filePath("rTxt", it) }
    }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val m = if (Files.isRegularFile(manifest)) manifest else synthesizeManifest()
        val compile = aapt2.compile(resDirs, compiledResDir)
        compile.result.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("aapt2", compile.result.log, DiagnosticKind.RESOURCE)
        if (!compile.result.success) return TaskResult.Failed("aapt2 compile (library R) failed")
        val r = aapt2.link(
            compile.archives,
            m,
            androidJar,
            packageName,
            emptyList(),
            minSdk,
            minSdk,
            genDir,
            throwawayAp,
            nonFinalIds = true,
            rTxt = rTxt,
        )
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("aapt2", r.log, DiagnosticKind.RESOURCE)
        return if (r.success) TaskResult.Success else TaskResult.Failed("aapt2 link (library R) failed")
    }

    private fun synthesizeManifest(): Path {
        synthManifest.parent?.let { Files.createDirectories(it) }
        synthManifest.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$packageName\"/>\n"
        )
        return synthManifest
    }
}

/** `aapt2 compile`: compile each `res/` dir into a flat-file archive under [outDir]. */
internal class Aapt2CompileTask(
    override val name: TaskName,
    private val resDirs: List<Path>,
    private val outDir: Path,
    private val aapt2: Aapt2,
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply { dirPaths("res", resDirs) }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            dirPath(
                "compiled", outDir
            )
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outDir)
        val r = aapt2.compile(resDirs, outDir)
        r.result.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("aapt2", r.result.log, DiagnosticKind.RESOURCE)
        return if (r.result.success) TaskResult.Success else TaskResult.Failed("aapt2 compile failed")
    }
}

/** `aapt2 link`: merge compiled archives + manifest into `resources.ap_` and emit `R.java`. */
internal class Aapt2LinkTask(
    override val name: TaskName,
    private val compiledDir: Path,
    private val manifest: Path,
    private val androidJar: Path,
    private val customPackage: String,
    private val extraPackages: List<String>,
    private val minSdk: Int,
    private val targetSdk: Int,
    private val versionCode: Int,
    private val versionName: String,
    private val genJavaDir: Path,
    private val resourcesAp: Path,
    private val aapt2: Aapt2,
    /** When set, aapt2 `--proguard` writes the manifest/layout keep rules here for the R8 minify task. */
    private val proguardRules: Path? = null,
    /** When true, link to proto resources (the input form R8's resource shrinker reads). */
    private val protoFormat: Boolean = false,
) : Task {
    private fun archives(): List<Path> =
        if (Files.isDirectory(compiledDir)) Files.list(compiledDir).use { s ->
            s.filter { it.toString().endsWith(".zip") }.sorted().collect(Collectors.toList())
        }
        else emptyList()

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("compiled", listOf(compiledDir))
            filePaths("manifest", listOf(manifest))
            property("package", customPackage)
            property("extraPackages", extraPackages.joinToString(":"))
            property("minSdk", minSdk)
            property("targetSdk", targetSdk)
            property("versionCode", versionCode)
            property("versionName", versionName)
            property("androidJar", androidJar.toString())
            property("proguard", proguardRules != null)
            property("proto", protoFormat)
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            filePath("ap", resourcesAp)
            dirPath("R", genJavaDir)
            proguardRules?.let { filePath("proguard", it) }
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val r = aapt2.link(
            archives(),
            manifest,
            androidJar,
            customPackage,
            extraPackages,
            minSdk,
            targetSdk,
            genJavaDir,
            resourcesAp,
            versionCode,
            versionName,
            proguardRules = proguardRules,
            protoFormat = protoFormat,
        )
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("aapt2", r.log, DiagnosticKind.RESOURCE)
        return if (r.success) TaskResult.Success else TaskResult.Failed("aapt2 link failed")
    }
}

/**
 * `generateViewBinding<Variant>`: when `buildFeatures { viewBinding }` is on, generate the real
 * `<namespace>.databinding.<Layout>Binding` Java per layout (the build counterpart of the editor's synthetic
 * binding classes — same shape, via [LayoutBindingModel]/[ViewBindingJavaSource]). Runs after the layouts
 * exist and before `compileKotlin`/`compileJava`, emitting into [outDir] which joins their source roots.
 */
internal class GenerateViewBindingTask(
    override val name: TaskName,
    private val resDirs: List<Path>,   // the module's OWN res roots — ViewBinding generates from own layouts
    private val namespace: String,
    private val outDir: Path,
) : Task {
    /** Only layout XML drives ViewBinding; fingerprint those files, not whole res dirs (a values edit is inert). */
    private fun layoutFiles(): List<Path> = resDirs.filter { Files.isDirectory(it) }.flatMap { res ->
        Files.list(res).use { dirs ->
            dirs.filter { Files.isDirectory(it) && it.fileName.toString().substringBefore('-') == "layout" }
                .collect(Collectors.toList())
        }.flatMap { dir ->
            Files.list(dir).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".xml") }.collect(Collectors.toList())
            }
        }
    }

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("layouts", layoutFiles())
            property("namespace", namespace)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("viewbinding", outDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        withContext(Dispatchers.IO) {
            // Regenerate cleanly so a deleted/renamed layout's stale binding doesn't linger on the source path.
            if (Files.isDirectory(outDir)) Files.walk(outDir).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
            Files.createDirectories(outDir)
        }
        val bindings = LayoutBindingModel.bindingsFor(resDirs, namespace)
        val pkgDir = LayoutBindingModel.packageName(namespace).replace('.', '/')
        withContext(Dispatchers.IO) {
            val dir = outDir.resolve(pkgDir)
            Files.createDirectories(dir)
            for (b in bindings) {
                dir.resolve("${b.simpleName}.java").writeText(ViewBindingJavaSource.emit(b, namespace))
            }
        }
        ctx.logger()("Generated ${bindings.size} ViewBinding class(es)")
        return TaskResult.Success
    }
}

/**
 * `generateRFile`: package the aapt2-generated `R.java` directly into **`R.jar`** bytecode — AGP's
 * `compile_and_runtime_not_namespaced_r_class_jar`. With `--extra-packages` aapt2 emits the WHOLE resource
 * table once per dependency package, millions of lines across dozens of huge `R` classes; ecj retains every
 * binding for the whole invocation and OOMs the heap-capped, in-process on-device build. An `R` is nothing but
 * int/int[] constants, so emit it as bytecode (exactly what AGP's `R.jar` does). `R.jar` then joins the compile
 * classpath and the dex inputs in place of compiling `R.java`. See [RBytecodeGenerator].
 */
internal class GenerateRJarTask(
    override val name: TaskName,
    private val genJavaDir: Path,
    private val outJar: Path,
) : Task {
    private fun rSources(): List<Path> =
        if (!Files.isDirectory(genJavaDir)) emptyList()
        else Files.walk(genJavaDir).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString() == "R.java" }.collect(Collectors.toList())
        }

    override val inputs: TaskInputs get() = TaskInputsImpl().apply { filePaths("rSources", rSources()) }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("rJar", outJar) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val res = withContext(Dispatchers.IO) { runCatching { RBytecodeGenerator.writeJar(rSources(), outJar) } }
        return res.fold(
            onSuccess = { TaskResult.Success },
            onFailure = { TaskResult.Failed("R.jar generation failed: ${it.message}") },
        )
    }
}

/**
 * `compileJava`: compile the variant's Java sources against the Android classpath. The generated `R.java` is
 * NOT compiled here — it is packaged as `R.jar` by [GenerateRJarTask] and reaches this task on [classpath].
 * Only non-`R` generated files (e.g. `Manifest.java`) under [genJavaDir] are compiled alongside the sources.
 */
internal class AndroidCompileTask(
    override val name: TaskName,
    private val sourceRoots: List<Path>,
    private val genJavaDir: Path,
    private val classpath: List<Path>,   // android.jar (compileOnly) + dependency outputs/jars
    private val outClasses: Path,
    private val level: String,
    /** ecj `-bootclasspath`: empty on the desktop (host JRE), `android.jar` + desugar stubs on ART. */
    private val bootClasspath: List<Path>,
    /** Additional generated source roots (e.g. ViewBinding) to compile alongside [sourceRoots] + [genJavaDir]. */
    private val extraGenDirs: List<Path> = emptyList(),
) : Task {
    // NB: `sourceRoots + genJavaDir` would bind Collection.plus(Iterable) — a Path is an Iterable<Path>
    // of its name components — and silently scatter the gen dir into segments. Append as a single element.
    private fun javaIn(roots: List<Path>): List<Path> =
        roots.filter { Files.isDirectory(it) }.flatMap { root ->
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                    .collect(Collectors.toList())
            }
        }

    // The generated `R.java` is excluded — it is packaged as `R.jar` by [GenerateRJarTask] and arrives on
    // [classpath]. Any OTHER generated file (e.g. aapt2's `Manifest.java`, plain constants) IS compiled.
    private fun sources(): List<Path> =
        javaIn(sourceRoots + extraGenDirs) + javaIn(listOf(genJavaDir)).filterNot { it.fileName.toString() == "R.java" }

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("sources", sources())
            filePaths("classpath", classpath)
            property("level", level)
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            dirPath(
                "classes", outClasses
            )
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        withContext(Dispatchers.IO) {
            Files.createDirectories(outClasses)
        }
        val srcs = sources()
        if (srcs.isEmpty()) return TaskResult.Success

        val r = JdtBatchCompiler.compile(srcs, classpath, outClasses, level, bootClasspath = bootClasspath)
        r.messages.forEach(ctx.logger())
        ctx.reportToolDiagnostics("java", r.messages)
        return if (r.success) TaskResult.Success
        else TaskResult.Failed(r.messages.joinToString("\n").ifBlank { "compilation failed" })
    }
}

/**
 * `compileKotlin`: K2 codegen of the variant's Kotlin sources into a `kotlin-classes` dir, ahead of
 * `compileJava`. The module's Java sources + generated `R.java` are handed to kotlinc as resolution-only
 * inputs (Kotlin may reference same-module Java types and `R`); only `.kt` produce `.class`. The Java
 * compile then puts this output on its classpath, and the dexer dexes it alongside the Java classes.
 */
internal class AndroidKotlinCompileTask(
    private val module: Module,
    override val name: TaskName,
    private val sourceRoots: List<Path>,
    private val genJavaDir: Path,        // R.java etc. — interop resolution input, not emitted
    private val classpath: List<Path>,   // android.jar (+ desugar stubs) + dependency outputs/jars + R classes
    private val outClasses: Path,        // the kotlin-classes output dir
    private val level: String,
    /** K2 bootclasspath: empty on the desktop (host JDK), `android.jar` + desugar stubs on ART. */
    private val bootClasspath: List<Path>,
    private val compiler: IncrementalKotlinCompiler,
    /** Kotlin compiler plugins to apply (those whose `appliesTo` matches the module). */
    private val plugins: List<KotlinCompilerPlugin> = BUILTIN_KOTLIN_COMPILER_PLUGINS,
    /** Additional generated source roots (e.g. ViewBinding) fed to kotlinc for resolution, like [genJavaDir]. */
    private val extraGenDirs: List<Path> = emptyList(),
) : Task {
    private fun walk(roots: List<Path>, ext: String): List<Path> =
        roots.filter { Files.isDirectory(it) }.flatMap { root ->
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(ext) }
                    .collect(Collectors.toList())
            }
        }

    private fun kotlinSources(): List<Path> = walk(sourceRoots, ".kt")
    private fun javaSources(): List<Path> = walk(sourceRoots + listOf(genJavaDir) + extraGenDirs, ".java")

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("kotlinSources", kotlinSources())
            filePaths("javaSources", javaSources())
            filePaths("classpath", classpath)
            property("level", level)
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            dirPath(
                "classes", outClasses
            )
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outClasses)
        val kt = kotlinSources()
        if (kt.isEmpty()) return TaskResult.Success
        // Apply the compiler plugins that match the module (e.g. Compose when the runtime is on the classpath).
        val resolved = plugins.resolveFor(module, classpath + bootClasspath)
        val r = compiler.compile(
            kt, javaSources(), classpath, outClasses, level,
            bootClasspath = bootClasspath,
            compilerPlugins = resolved.classpaths, pluginOptions = resolved.options,
            runtimePluginClasspaths = resolved.runtimeClasspaths,
        )
        // Stream the compiler output as structured (located, navigable) diagnostics only — NOT also as raw
        // logger() lines. Logging both made every Kotlin error appear twice: once as an INFO transcript line
        // here and again as the ERROR summary the engine logs for the failed task. (Mirrors JdtCompileTask
        // and the desktop KotlinCompileTask, which report diagnostics without echoing them to the log.)
        ctx.reportToolDiagnostics("kotlin", r.messages)
        return if (r.success) TaskResult.Success
        else TaskResult.Failed(
            r.messages.joinToString("\n").ifBlank { "kotlin compilation failed" })
    }
}

/**
 * `dexBuilder<Variant>`: AGP's single dex-archive builder ([com.android.build.gradle.internal.tasks]
 * `DexArchiveBuilderTask`). It dexes the three input scopes AGP partitions — the project's own classes,
 * sub-module jars, and external-library jars (`projectClasses`/`subProjectClasses`/`externalLibClasses`) —
 * into per-scope dex archives (per-class `.dex` via [Dexer.dexArchive]) under [projectDexRoot]/
 * [subDexRoot]/[extDexRoot].
 *
 * Incrementality is internal and content-addressed: each input is archived into `<scope>/<contentHash>/`,
 * so an unchanged input's bucket already exists and is left untouched (never re-dexed), a changed input
 * gets a fresh hash bucket, and stale buckets are pruned. The engine still skips the whole task when no
 * input changed; when it does run, only new/changed inputs are dexed — the same effect AGP gets from
 * Gradle's `InputChanges`, without an in-task incremental API in this engine. The archives are combined by
 * the scope merge tasks ([DexMergeTask]: `mergeProjectDex`/`mergeLibDex`/`mergeExtDex`/`mergeDex`).
 */
internal class DexArchiveBuilderTask(
    override val name: TaskName,
    private val projectClasses: List<Path>,      // the app/project's compiled class dirs (Java + Kotlin output)
    private val subProjectJars: List<Path>,      // dependency module `jar` artifacts
    private val externalJars: List<Path>,        // external library jars (incl. AAR classes.jar)
    private val androidJar: Path,
    private val minApi: Int,
    private val release: Boolean,
    private val stagingJar: Path,                 // the project class dir is jarred here before archiving
    private val projectDexRoot: Path,
    private val subDexRoot: Path,
    private val extDexRoot: Path,
    private val dexer: Dexer,
    private val dexCacheRoot: Path? = null,   // global content-addressed library-dex cache (null = per-project only)
    private val desugaredLibConfig: Path? = null, // core-library-desugaring config (null = disabled, behaves as before)
    // The app's generated `R.jar`: dexed into its OWN archive root ([rDexRoot]), NOT the external scope, and merged
    // into the project dex layer (AGP keeps R in the project scope). Excluded from the external desugaring universe
    // and its cache digest — R holds only resource-id constants (no type any library desugars against) yet changes
    // on every resource edit, so were it in the ext scope it would bust the shared cache and force mergeExtDex to
    // re-merge every library. Keeping it separate makes the external scope stable across resource edits.
    private val rJars: List<Path> = emptyList(),
    private val rDexRoot: Path? = null,
    // When false, external libraries stay on the desugaring universe/classpath (so project + sub-module dexing
    // resolves against them) but are NOT archived here — a separate [DexExternalLibsTask] dexes the whole external
    // classpath in one forked big-heap pass instead (the fast fresh path for desugaring builds). True keeps the
    // per-library archive + [DexMergeTask] merge (cross-project per-lib reuse; used when no desugaring applies).
    private val archiveExternalScope: Boolean = true,
) : Task {
    /** The first recognized dex error of a run, so the task's failure summary names the actual cause (e.g. a
     *  duplicate class) instead of a generic "dexBuilder failed". Set from the humanized dexer output. */
    private val firstFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private fun recordFailure(log: List<String>) { DexDiagnostics.firstError(log)?.let { firstFailure.compareAndSet(null, it) } }

    /** A cache key for the desugaring config: empty when disabled, so all cache namespaces are byte-identical
     *  to the no-desugaring build. Non-empty (content hash) when enabled, so toggling it busts stale dex. */
    private fun desugarConfigKey(): String =
        desugaredLibConfig?.takeIf { Files.exists(it) }?.let { DexArchives.fileHash(it) } ?: ""

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("project", projectClasses)
            filePaths("sub", subProjectJars)
            filePaths("ext", externalJars)
            filePaths("r", rJars)
            property("minApi", minApi)
            property("release", release)
            property("androidJar", androidJar.toString())
            desugarConfigKey().takeIf { it.isNotEmpty() }?.let { property("desugarConfig", it) }
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            dirPath("projectDex", projectDexRoot)
            dirPath("subDex", subDexRoot)
            dirPath("extDex", extDexRoot)
            rDexRoot?.let { dirPath("rDex", it) }
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        // A library's desugaring classpath is the type-universe D8 needs to resolve interface hierarchies for
        // default/static-interface-method (and lambda) desugaring below the native API level. AGP scopes it by
        // dependency direction (deps point down): an EXTERNAL library never depends on your sub-modules, app, or
        // R, so its classpath — and thus its dex cache key — is the external-library set ALONE. A SUB-module can
        // depend on external libs (and other subs), so its universe is sub + ext. Scoping this way (rather than
        // one flat universe) is what keeps the external library cache stable when you edit app code, a resource,
        // or a sub-module. The dexing + shared cache is shared with the layout preview via [SharedLibraryDexer].
        val libDexer = SharedLibraryDexer(
            dexer, androidJar, minApi, release, dexCacheRoot, desugaredLibConfig,
            log = ctx.logger(),
            checkCanceled = { ctx.checkCanceled() },
            reportDiagnostics = { ctx.reportToolDiagnostics("d8", it, DiagnosticKind.DEX) },
            onFailure = { firstFailure.compareAndSet(null, it) },
        )
        val hashCacheDir = subDexRoot.parent ?: subDexRoot
        // External scope: universe = external libs alone. R.jar rides along as an [extraDexJars] entry so it gets a
        // content-hash bucket (dexable) WITHOUT entering the desugaring universe or its cache digest.
        val extUniverse = libDexer.computeUniverse(externalJars, hashCacheDir, extraDexJars = rJars)
        // Sub-module + project scope: universe = sub + ext (subs and the app desugar against both). Reuse the ext
        // universe when there are no sub-modules (the common single-app case) to avoid a second hashing pass.
        val subUniverse =
            if (subProjectJars.isEmpty()) extUniverse
            else libDexer.computeUniverse(subProjectJars + externalJars, hashCacheDir)

        var ok = archiveProject(ctx, subUniverse)
        // Libraries are atomic jars → per-jar content-hash buckets (a changed lib re-dexes alone).
        ok = libDexer.dexScope(subProjectJars, subDexRoot, subUniverse) && ok
        // External libs stay in the universe above (project/sub desugar against them) but may be dexed elsewhere
        // (one-pass forked; see [DexExternalLibsTask]) rather than archived here.
        if (archiveExternalScope) ok = libDexer.dexScope(externalJars, extDexRoot, extUniverse) && ok
        // R.jar into its OWN archive root (merged into the project layer downstream). It has nothing to desugar, so
        // the ext libs on its classpath are harmless; it re-dexes alone on a resource edit and never touches the
        // external scope's archives (so mergeExtDex stays up-to-date). NB: its ~3s cost on a material app is R's
        // SIZE (≈50 dependency-package R classes, thousands of constants), not the classpath — and it's GC-bound,
        // so throwing D8 threads at it made it slower, not faster (see DexConcurrency.archivePlanFor).
        if (rDexRoot != null && rJars.isNotEmpty()) {
            ok = libDexer.dexScope(rJars, rDexRoot, extUniverse) && ok
        }
        return if (ok) TaskResult.Success else TaskResult.Failed(firstFailure.get() ?: "dexBuilder failed")
    }

    /**
     * The `R` classes come exclusively from the `R.jar` (dexed in the external scope). Never dex an `R.class`
     * from the *project* scope: a build that predates the `R.jar` path compiled the whole gen tree
     * (`R.java` + every `--extra-package` `R`) straight into this class output, and those `.class` are not
     * cleaned when the compile switches to excluding `R.java`. Dexing them would define every `R` type in BOTH
     * the project scope (an earlier `classes*.dex`) and the external `R.jar` scope (a later one); ART resolves
     * the FIRST definition, so the stale project-scope `R` wins — and once a resource edit shifts the ids, its
     * constants no longer match the packaged `resources.arsc` (e.g. `R.styleable.AppCompatTheme`'s
     * `windowActionBar` slot points at the wrong attr → AppCompat's "You need to use a Theme.AppCompat theme"
     * check fails). Excluding them here is cache-proof and self-healing: the omitted classes fall into
     * [archiveProject]'s `removed` set, so the next build deletes their stale project-scope `.dex`.
     */
    private fun isGeneratedRClass(fileName: String): Boolean = fileName == "R.class" || fileName.startsWith("R\$")

    /**
     * Project scope, per class file: archive only the `.class` files whose content changed (tracked in a
     * `.classmanifest`), so editing one class re-dexes just it. Changed classes are the D8 *program*; the
     * unchanged ones + library jars are the desugaring *classpath* so a changed class still sees its siblings.
     * D8's `DexFilePerClassFile` writes `<class>.dex` straight into [projectDexRoot]; the merge resolves them.
     */
    private fun archiveProject(ctx: TaskContext, universe: SharedLibraryDexer.Universe): Boolean {
        Files.createDirectories(projectDexRoot)
        // Collect across every project-class root (Java output + Kotlin output) keyed by package-relative
        // path; a later root wins a (rare) name clash. Relpaths from distinct roots don't otherwise collide.
        val byRel = LinkedHashMap<String, Path>()
        val perRoot =
            LinkedHashMap<String, Int>()   // root name -> .class count contributed (Java vs Kotlin output)
        for (root in projectClasses.filter { Files.isDirectory(it) }) {
            var n = 0
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") && !isGeneratedRClass(it.fileName.toString()) }
                    .forEach { f ->
                        byRel[root.relativize(f).toString().replace('\\', '/')] = f; n++
                    }
            }
            perRoot[root.fileName.toString()] = n
        }
        val rootSummary =
            projectClasses.joinToString(", ") { "${it.fileName}=${perRoot[it.fileName.toString()] ?: 0}" }
        if (byRel.isEmpty()) {
            ctx.logger()("dexBuilder project scope: no class files to dex ($rootSummary) — app code will be absent from the APK")
            DexArchives.clearClassDex(projectDexRoot); return true
        }
        // relpath -> content hash, suffixed with the desugaring-config key so enabling/disabling it re-dexes
        // every class (its dex depends on the config). Empty suffix when disabled = identical to before.
        val cfg = desugarConfigKey()
        val current =
            byRel.mapValues { (_, f) -> DexArchives.fileHash(f) + if (cfg.isEmpty()) "" else ":$cfg" }
        val previous = DexArchives.readClassManifest(projectDexRoot)
        val changed = current.keys.filter { previous[it] != current[it] }      // new or modified
        val removed = previous.keys - current.keys
        (changed + removed).forEach { DexArchives.deleteClassDex(projectDexRoot, it) }
        ctx.logger()("dexBuilder project scope: ${byRel.size} class file(s) [$rootSummary]; ${changed.size} to (re)dex")

        var ok = true
        if (changed.isNotEmpty()) {
            val changedJar = stagingJar.resolveSibling("project-changed.jar")
            val restJar = stagingJar.resolveSibling("project-rest.jar")
            // Strip @kotlin.Metadata from the program classes: the in-process D8/R8 crashes rewriting Kotlin
            // metadata newer than it supports, which drops the dex output (see strippedKotlinMetadata).
            DexArchives.jarClasses(byRel, changed.toSet(), changedJar, stripKotlinMetadata = true)
            val classpath = ArrayList<Path>()
            // The desugaring classpath (the changed class's unchanged siblings + the library universe) is only
            // consulted to desugar interface methods / lambdas / core-lib backports. When none of that applies
            // (see [desugaringNeeded]) skip it entirely — including jarring up the unchanged siblings (restJar).
            if (universe.desugaringNeeded) {
                val unchanged = current.keys - changed.toSet()
                if (unchanged.isNotEmpty()) {
                    DexArchives.jarClasses(byRel, unchanged, restJar); classpath.add(restJar)
                }
                // Library universe, class-deduped so D8 never sees a type twice. Exclude the project's own
                // classes (program + restJar) so a library that redefines one can't collide with them either.
                classpath.addAll(DexArchives.classpathFor(universe.universeByHash.values, universe.classesOf, exclude = current.keys))
            }
            val r = dexer.dexArchive(
                listOf(changedJar),
                classpath,
                androidJar,
                minApi,
                release,
                projectDexRoot,
                threads = DexConcurrency.plan(1).threadsPerInvocation,
                desugaredLibConfig = desugaredLibConfig,
            )
            r.log.forEach(ctx.logger()); ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
            if (!r.success) {
                ok = false; recordFailure(r.log); ctx.logger()("dex archive failed for project classes")
            }
        }
        // Verify every (re)dexed class actually produced a `.dex`. A dexer can report success yet silently drop
        // a class it couldn't process (e.g. D8 choking on Kotlin metadata newer than it supports) — which would
        // leave that class out of the APK (a launch-time ClassNotFoundException). Record ONLY classes whose
        // `.dex` exists, so a dropped class is re-dexed next build instead of being remembered as done; fail the
        // task (naming the casualties) rather than package an APK missing app code.
        val produced =
            current.keys.filter { Files.isRegularFile(projectDexRoot.resolve(DexArchives.dexRelOf(it))) }
                .toSet()
        // `module-info`/`package-info` carry no runtime code, so D8 emits no `.dex` for them — not a drop.
        val dropped =
            current.keys.filter { DexArchives.dexable(it) && it !in produced }   // changed this run or a stale prior drop
        if (dropped.isNotEmpty()) {
            ok = false
            ctx.logger()(
                "dexBuilder project scope: ${dropped.size} class(es) produced no .dex and will be ABSENT from the APK " + "(e.g. ${
                    dropped.take(5).joinToString { it.removeSuffix(".class").replace('/', '.') }
                }) — the dexer dropped them, often Kotlin metadata it can't parse")
        }
        // Record a class as done only if it dexed (or never needed to), so a dropped class re-dexes next build.
        DexArchives.writeClassManifest(
            projectDexRoot, current.filterKeys { it in produced || !DexArchives.dexable(it) })
        return ok
    }
}

/**
 * Bumped whenever the bundled D8/R8 (`libs.versions.toml` `r8`) or the archive layout changes, so a tool
 * upgrade can't reuse stale dex from the shared cache. Folded into the cache namespace (see `cacheTag`).
 */
// v2: library programs are dexed with @kotlin.Metadata stripped (DexArchives.strippedJar) — bump so caches
// from the pre-strip format are re-dexed once into the new namespace rather than reused with stale metadata.
internal const val DEX_CACHE_FORMAT = "v2-r8-8.13.19"

/** Content-addressing + bucket bookkeeping for [DexArchiveBuilderTask]'s per-scope dex archives. */
internal object DexArchives {
    /**
     * Content hash of [path] — a dir's sorted (relpath + bytes), a jar/zip's sorted (entryName + bytes),
     * else the raw file bytes. Hashing *content* (not the file/jar bytes, which carry timestamps) keeps an
     * unchanged input's bucket name stable across rebuilds, so it is reused rather than re-dexed.
     */
    fun contentHash(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        when {
            Files.isDirectory(path) -> {
                val files = Files.walk(path).use { s ->
                    s.filter { Files.isRegularFile(it) }.sorted().collect(Collectors.toList())
                }
                for (f in files) {
                    md.update(
                        path.relativize(f).toString().replace('\\', '/').toByteArray(Charsets.UTF_8)
                    )
                    md.update(runCatching { Files.readAllBytes(f) }.getOrDefault(ByteArray(0)))
                }
            }

            isZip(path) -> {
                val read = runCatching {
                    ZipFile(path.toFile()).use { zf ->
                        zf.entries().toList().filterNot { it.isDirectory }.sortedBy { it.name }
                            .forEach { e ->
                                md.update(e.name.toByteArray(Charsets.UTF_8))
                                zf.getInputStream(e).use { md.update(it.readBytes()) }
                            }
                    }
                }
                // A jar that ZipFile can't open (ART throws "No entries" on a zero-entry archive, e.g. a
                // resource-only AAR's empty classes.jar) must not crash the whole dex build — hash its raw
                // bytes instead, which still gives a stable per-content fingerprint.
                if (read.isFailure) md.update(runCatching { Files.readAllBytes(path) }.getOrDefault(ByteArray(0)))
            }

            Files.isRegularFile(path) -> md.update(Files.readAllBytes(path))
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 24)
    }

    /** A short, order-independent digest of a set of content hashes — used to key the shared cache to the
     *  library desugaring universe (so a bucket isn't reused under a different classpath). */
    fun digestOf(hashes: Collection<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        hashes.sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 16)
    }

    /** The `.class` entry names a jar defines (e.g. `kotlin/jvm/internal/Intrinsics.class`), for class-level
     *  classpath dedup. A non-jar or unreadable input yields the empty set (no class conflict). */
    fun classNamesOf(jar: Path): Set<String> {
        if (!isZip(jar)) return emptySet()
        val out = HashSet<String>()
        runCatching {
            ZipFile(jar.toFile()).use { zf ->
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    val name = e.nextElement().name
                    if (name.endsWith(".class")) out.add(name)
                }
            }
        }
        return out
    }

    /** Content hash of a single file's bytes (`.class` files are deterministic — no timestamps). */
    fun fileHash(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(runCatching { Files.readAllBytes(file) }.getOrDefault(ByteArray(0)))
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 24)
    }

    fun hasDex(dir: Path): Boolean = Files.isDirectory(dir) && Files.walk(dir)
        .use { s -> s.anyMatch { it.toString().endsWith(".dex") } }

    /** The per-class `.dex` path a `DexFilePerClassFile` archive writes for a `.class` at [classRel]. */
    fun dexRelOf(classRel: String): String = classRel.removeSuffix(".class") + ".dex"

    /** Whether D8 emits a `.dex` for [classRel]. `module-info`/`package-info` carry no runtime code, and
     *  classes under `META-INF/versions/` are a multi-release jar's alternate classes (D8 dexes the base
     *  class, not these) — so none of them produce a `.dex`, and the completeness check must not expect one. */
    fun dexable(classRel: String): Boolean =
        !classRel.startsWith("META-INF/") && classRel.substringAfterLast('/')
            .let { it != "module-info.class" && it != "package-info.class" }

    /**
     * Whether [bucket] holds a per-class `.dex` for every dexable class in [jarClasses] (the library jar's
     * `.class` entries). A dexer can report success yet drop a class, and a build that's interrupted (or an
     * older one) can leave a partial bucket; bucket reuse only checks for ANY `.dex` ([hasDex]), so an
     * incomplete bucket would be reused forever — silently omitting that class (a runtime
     * `ClassNotFoundException`). An incomplete bucket is re-dexed rather than reused.
     */
    fun bucketComplete(bucket: Path, jarClasses: Set<String>): Boolean {
        if (!hasDex(bucket)) return false
        return jarClasses.all { !dexable(it) || Files.isRegularFile(bucket.resolve(dexRelOf(it))) }
    }

    /**
     * Build a duplicate-free desugaring classpath from [candidates]. D8 aborts when two classpath entries
     * define the same type, so walk the jars in priority order and keep one only if it introduces no class
     * already provided — by an earlier-kept jar or by [exclude] (the program's own classes, which belong to
     * the input, not the classpath). A jar that fully overlaps an earlier one (e.g. a second kotlin-stdlib) is
     * dropped whole; a partial overlap is also dropped, at worst reviving a benign "Type not found" desugaring
     * warning for its unique classes — never a hard failure. A class-less jar (a resource-only AAR's
     * classes.jar) adds no types and can't be opened on ART when empty (zero-entry zip), so it is excluded.
     */
    fun classpathFor(
        candidates: Collection<Path>, classesOf: Map<Path, Set<String>>, exclude: Set<String>,
    ): List<Path> {
        val seen = HashSet(exclude)
        val kept = ArrayList<Path>()
        for (jar in candidates) {
            val cs = classesOf[jar] ?: emptySet()
            if (cs.isNotEmpty() && cs.none { it in seen }) {
                kept.add(jar); seen.addAll(cs)
            }
        }
        return kept
    }

    private const val CLASS_MANIFEST = ".classmanifest"

    /** Read the per-class `relpath -> contentHash` manifest written by [writeClassManifest]. */
    fun readClassManifest(root: Path): Map<String, String> {
        val f = root.resolve(CLASS_MANIFEST)
        if (!Files.isRegularFile(f)) return emptyMap()
        return runCatching {
            Files.readAllLines(f).mapNotNull { line ->
                val i = line.indexOf('\t'); if (i <= 0) null else line.substring(
                0, i
            ) to line.substring(i + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Write the per-class manifest (sorted, so the bytes are stable when the class set is unchanged). */
    fun writeClassManifest(root: Path, map: Map<String, String>) {
        Files.createDirectories(root)
        runCatching {
            Files.write(
                root.resolve(CLASS_MANIFEST),
                map.entries.sortedBy { it.key }.map { "${it.key}\t${it.value}" })
        }
    }

    /** Delete the per-class `.dex` for a `com/example/Foo.class` relpath (DexFilePerClassFile names it `Foo.dex`). */
    fun deleteClassDex(root: Path, classRelPath: String) {
        runCatching { Files.deleteIfExists(root.resolve(classRelPath.removeSuffix(".class") + ".dex")) }
    }

    /** Drop every per-class `.dex` + the manifest (the project has no classes anymore). */
    fun clearClassDex(root: Path) {
        if (!Files.isDirectory(root)) return
        Files.walk(root).use { s ->
            s.filter {
                Files.isRegularFile(it) && (it.toString()
                    .endsWith(".dex") || it.fileName.toString() == CLASS_MANIFEST)
            }.collect(Collectors.toList())
        }.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** Jar the [keys] subset of [byRel] (relpath -> class file) into [jar], preserving package paths. */
    fun jarClasses(
        byRel: Map<String, Path>, keys: Set<String>, jar: Path, stripKotlinMetadata: Boolean = false
    ) {
        jar.parent?.let { Files.createDirectories(it) }
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            var wrote = false
            keys.sorted().forEach { rel ->
                val f = byRel[rel] ?: return@forEach
                jos.putNextEntry(JarEntry(rel))
                if (stripKotlinMetadata) jos.write(strippedKotlinMetadata(Files.readAllBytes(f))) else Files.copy(
                    f, jos
                )
                jos.closeEntry()
                wrote = true
            }
            // A JarOutputStream closed with ZERO entries throws `ZipException: No entries` on ART; if this scope
            // had no classes, write a benign manifest so the (class-free) jar stays valid on device. Dexes to nothing.
            if (!wrote) { jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF")); jos.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); jos.closeEntry() }
        }
    }

    /**
     * Drop the `@kotlin.Metadata` annotation from a class. The bundled in-process D8/R8 ([D8InProcessDexer])
     * crashes (`Should never be called`) trying to *rewrite* Kotlin metadata newer than its bundled
     * `kotlin-metadata-jvm` understands — and that crash drops the dex output for the whole invocation, so a
     * project compiled with a newer Kotlin (e.g. a Compose app on Kotlin 2.4) loses `MainActivity` and ships a
     * `ClassNotFoundException` APK. The annotation only feeds Kotlin reflection (`kotlin-reflect`) over the
     * app's own classes — irrelevant to execution and to Compose — so stripping it before dexing sidesteps the
     * rewriter entirely, exactly as R8 release-mode does when it can't preserve metadata. A class with no such
     * annotation passes through byte-for-byte.
     */
    fun strippedKotlinMetadata(bytes: ByteArray): ByteArray {
        var found = false
        val reader = ClassReader(bytes)
        val writer =
            ClassWriter(0)   // remove only an annotation — frames/maxs are untouched, so no recompute
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor == "Lkotlin/Metadata;") {
                    found = true; return null
                }
                return super.visitAnnotation(descriptor, visible)
            }
        }, 0)
        return if (found) writer.toByteArray() else bytes
    }

    /**
     * A copy of [jar] at [dst] with `@kotlin.Metadata` stripped from every class, OR the original [jar]
     * unchanged when it carries no Kotlin classes (no `.kotlin_module` entry under `META-INF`) so a pure-Java
     * library is never needlessly re-jarred. Feeding this (rather than the raw jar) to D8 as the *program* stops the
     * bundled in-process D8/R8 — whose shaded `kotlin-metadata-jvm` predates the app's Kotlin (2.4) — from
     * logging an "error … parsing kotlin meta data" warning for every library class, and removes the
     * metadata-rewriter crash path that can silently drop dex output ([strippedKotlinMetadata]). Library
     * metadata only feeds `kotlin-reflect` over those types (rare in an app, and irrelevant to execution /
     * Compose), so dropping it matches R8 release, which does not preserve library metadata by default.
     * Non-class entries are copied byte-for-byte. Callers strip only on a dex *miss*, so a cached library is
     * never re-jarred.
     */
    fun strippedJar(jar: Path, dst: Path): Path {
        if (!isZip(jar)) return jar
        val hasKotlin = runCatching {
            ZipFile(jar.toFile()).use { zf ->
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    val n = e.nextElement().name
                    if (n.startsWith("META-INF/") && n.endsWith(".kotlin_module")) return@use true
                }
                false
            }
        }.getOrDefault(false)
        if (!hasKotlin) return jar
        dst.parent?.let { Files.createDirectories(it) }
        ZipFile(jar.toFile()).use { zf ->
            JarOutputStream(Files.newOutputStream(dst)).use { jos ->
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    if (entry.isDirectory) continue
                    val bytes = zf.getInputStream(entry).use { it.readBytes() }
                    jos.putNextEntry(JarEntry(entry.name))
                    jos.write(if (entry.name.endsWith(".class")) strippedKotlinMetadata(bytes) else bytes)
                    jos.closeEntry()
                }
            }
        }
        return dst
    }

    /** Delete immediate child buckets of [root] whose name is not in [keep] (removed/changed inputs). */
    fun prune(root: Path, keep: Set<String>) {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { s ->
            s.filter { Files.isDirectory(it) && it.fileName.toString() !in keep }
                .collect(Collectors.toList())
        }.forEach { clearDir(it) }
    }

    fun clearDir(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Recursively copy [src] dir into [dst] (cheap — moves a few per-class `.dex`, vs re-running D8). */
    fun copyDir(src: Path, dst: Path) {
        if (!Files.isDirectory(src)) return
        Files.createDirectories(dst)
        Files.walk(src).use { s ->
            s.forEach { p ->
                val target = dst.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else {
                    target.parent?.let { Files.createDirectories(it) }; Files.copy(
                        p, target, StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
        }
    }

    /**
     * Seed the shared cache with a freshly-dexed [bucket] at [shared]. Stage to a temp dir then atomically
     * move it into place, so a half-written bucket is never visible — and a lost race with another project
     * dexing the same jar concurrently just discards our copy (the winner's is already valid).
     */
    fun publishToCache(bucket: Path, shared: Path) {
        runCatching {
            shared.parent?.let { Files.createDirectories(it) }
            val tmp =
                shared.resolveSibling(shared.fileName.toString() + ".tmp-" + java.util.UUID.randomUUID())
            clearDir(tmp); copyDir(bucket, tmp)
            try {
                Files.move(tmp, shared, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                if (!hasDex(shared)) runCatching { Files.move(tmp, shared) }.onFailure {
                    clearDir(
                        tmp
                    )
                } else clearDir(tmp)
            }
        }
    }

    /**
     * A jar's content hash, cached by (absolute path, size, mtime) in a `.hashcache` sidecar so an unchanged
     * library isn't re-read byte-for-byte every build (the cost that scaled with library count).
     */
    class HashCache(root: Path) {
        private val file = root.resolve(".hashcache")
        private val entries = LinkedHashMap<String, String>()   // absPath -> "size:mtime:hash"
        private var dirty = false

        init {
            if (Files.isRegularFile(file)) runCatching {
                Files.readAllLines(file).forEach { line ->
                    val t = line.indexOf('\t'); if (t > 0) entries[line.substring(0, t)] =
                    line.substring(t + 1)
                }
            }
        }

        fun hashOf(jar: Path): String {
            val key = jar.toAbsolutePath().toString()
            val mtime = runCatching { Files.getLastModifiedTime(jar).toMillis() }.getOrDefault(0L)
            val sig = "${runCatching { Files.size(jar) }.getOrDefault(-1L)}:$mtime"
            entries[key]?.let { v ->
                val sep =
                    v.lastIndexOf(':')                    // value is "size:mtime:hash"; hash has no ':'
                if (sep > 0 && v.substring(0, sep) == sig) return v.substring(sep + 1)
            }
            val h = contentHash(jar)
            entries[key] = "$sig:$h"; dirty = true
            return h
        }

        fun flush() {
            if (!dirty) return
            runCatching {
                file.parent?.let { Files.createDirectories(it) }; Files.write(
                file, entries.map { "${it.key}\t${it.value}" })
            }
        }
    }

    private fun isZip(p: Path): Boolean = Files.isRegularFile(p) && p.toString().let {
        it.endsWith(".jar", true) || it.endsWith(".zip", true) || it.endsWith(
            ".aar", true
        )
    }
}

/**
 * `dexExtLibs<Variant>`: dex the WHOLE external-library classpath to indexed dex in ONE pass, replacing the
 * per-library archive ([DexArchiveBuilderTask] ext scope) + [DexMergeTask] merge for desugaring builds. The
 * injected [dexer] is the forked big-heap D8 on device (self-falls-back to in-process when a fork isn't
 * affordable): the fork escapes ART's ~576MB app-heap cap that makes the in-process archive GC-bound — measured
 * ~2.6x faster fresh (~6s vs ~15s archive+merge), since dexing is GC-bound and the fork has room to spare. The
 * indexed output is content-addressed by the library set ([cacheRoot]) and reused across builds/cleans/projects
 * with the same set. The whole classpath is the D8 program, so cross-library desugaring resolves without a
 * per-lib classpath. Because libraries are dexed together (no per-lib buckets), a dependency change re-dexes the
 * whole classpath — no worse than before: a desugaring build's shared cache is keyed by the whole set, so a dep
 * change already re-dexed everything. (Used only when desugaring applies; minSdk >= 26 keeps per-lib buckets for
 * cross-project per-library reuse.)
 */
internal class DexExternalLibsTask(
    override val name: TaskName,
    private val libJars: List<Path>,
    private val androidJar: Path,
    private val minApi: Int,
    private val release: Boolean,
    private val outDexDir: Path,
    private val dexer: Dexer,
    private val desugaredLibConfig: Path? = null,
    private val cacheRoot: Path? = null,
) : Task {
    private fun desugarKey(): String = desugaredLibConfig?.takeIf { Files.exists(it) }?.let { DexArchives.fileHash(it) } ?: ""

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("libs", libJars)
            property("minApi", minApi)
            property("release", release)
            property("androidJar", androidJar.toString())
            desugarKey().takeIf { it.isNotEmpty() }?.let { property("desugarConfig", it) }
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("dex", outDexDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        // Class-bearing jars only (skip a resource-only AAR's empty classes.jar — ART can't open a zero-entry zip).
        val jars = libJars.filter { Files.exists(it) && Files.size(it) > 0L && DexArchives.classNamesOf(it).isNotEmpty() }
        if (jars.isEmpty()) { DexArchives.clearDir(outDexDir); return TaskResult.Success }

        val stateDir = outDexDir.resolveSibling("${outDexDir.fileName}.extlibs-state")
        val cached = cacheRoot?.resolve(cacheKey(jars, stateDir))
        if (cached != null && DexArchives.hasDex(cached)) {
            DexArchives.clearDir(outDexDir); DexArchives.copyDir(cached, outDexDir)
            ctx.logger()("${name.value}: reused indexed external dex from shared cache (${jars.size} libs)")
            return TaskResult.Success
        }
        DexArchives.clearDir(outDexDir); Files.createDirectories(outDexDir)
        // The whole classpath IS the D8 program (so cross-library desugaring resolves); android.jar is the library.
        // Forked big-heap when available (GC-free), else in-process; D8's internal pool parallelizes across cores.
        // Strip @kotlin.Metadata from each Kotlin library first so the bundled D8's older kotlin-metadata parser
        // doesn't warn per class (and can't hit the rewriter drop path); pure-Java jars pass through untouched.
        // The strip is CONTENT-ADDRESSED (a library is immutable, so it is stripped ONCE per machine into the
        // shared `stripped-libs` cache and reused across builds/cleans/projects — a dep change re-strips only the
        // genuinely new jars) and PARALLEL across cores, so it is not a serial re-jar of the whole classpath on
        // every cache miss.
        val hc = DexArchives.HashCache(stateDir)
        val hashOf = jars.associateWith { hc.hashOf(it) }   // serial (HashCache isn't thread-safe) but cheap: path+size+mtime
        hc.flush()
        val stripCache = cacheRoot?.resolveSibling("stripped-libs")?.also { runCatching { Files.createDirectories(it) } }
        val stripTmp = outDexDir.resolveSibling("${outDexDir.fileName}.stripping")
        DexArchives.clearDir(stripTmp); Files.createDirectories(stripTmp)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val programs = coroutineScope {
            val sem = Semaphore(threads)
            jars.map { j ->
                async(Dispatchers.IO) { sem.withPermit { ctx.checkCanceled(); strippedProgram(j, hashOf.getValue(j), stripCache, stripTmp) } }
            }.awaitAll()
        }
        val r = try {
            dexer.dex(programs, androidJar, minApi, release, outDexDir, threads, desugaredLibConfig)
        } finally {
            DexArchives.clearDir(stripTmp)   // per-build scratch; the reusable stripped jars live in stripCache
        }
        r.log.forEach(ctx.logger()); ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
        if (!r.success) return TaskResult.Failed(DexDiagnostics.firstError(r.log) ?: "external dex failed")
        if (!DexArchives.hasDex(outDexDir)) return TaskResult.Failed("external dex produced no output for ${jars.size} libs")
        if (cached != null) runCatching { DexArchives.clearDir(cached); DexArchives.publishToCache(outDexDir, cached) }
        ctx.logger()("${name.value}: dexed ${jars.size} external libraries -> indexed dex")
        return TaskResult.Success
    }

    /**
     * The D8 program for library [jar]: a `@kotlin.Metadata`-stripped copy for a Kotlin library, the [jar]
     * itself for a pure-Java one (never re-jarred). Stripped copies are content-addressed at
     * `stripCache/<hash>.jar` and reused across builds/projects (an immutable library is stripped once); the
     * shared write is staged in [tmpDir] then atomically moved so a concurrent stripper can't see a partial jar.
     * Falls back to a per-build temp when there's no [stripCache].
     */
    private fun strippedProgram(jar: Path, hash: String, stripCache: Path?, tmpDir: Path): Path {
        stripCache?.resolve("$hash.jar")?.let { if (Files.isRegularFile(it)) return it }
        val tmp = Files.createTempFile(tmpDir, "$hash-", ".jar")
        val out = DexArchives.strippedJar(jar, tmp)
        if (out !== tmp) { runCatching { Files.deleteIfExists(tmp) }; return jar }  // pure-Java: nothing written
        val target = stripCache?.resolve("$hash.jar") ?: return tmp
        return try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); target
        } catch (_: Exception) {
            if (Files.isRegularFile(target)) { runCatching { Files.deleteIfExists(tmp) }; target } else tmp
        }
    }

    /** Content-address by the library set (path+size+mtime-cached content hashes) + dexing params + format. */
    private fun cacheKey(jars: List<Path>, stateDir: Path): String {
        Files.createDirectories(stateDir)
        val hc = DexArchives.HashCache(stateDir)
        val hashes = jars.map { hc.hashOf(it) }
        hc.flush()
        return DexArchives.digestOf(hashes + listOf("minApi$minApi", if (release) "rel" else "dbg", DEX_CACHE_FORMAT, "cfg:${desugarKey()}"))
    }
}

/**
 * The dex *merge* under AGP's four `DexMergingAction` names — `mergeProjectDex` (the app), `mergeLibDex`
 * (sub-modules), `mergeExtDex` (external libraries), `mergeDex` (MERGE_ALL, mono-/legacy-multidex). D8 is
 * also the dex merger, so feeding it the per-class `.dex` of the archives ([DexArchiveBuilderTask] output)
 * merges them into indexed `classes.dex` (+ `classes2.dex` … past the 64k method limit). This is the cheap
 * step that re-runs only when its scope's archives change — so editing the app skips `mergeLibDex`/
 * `mergeExtDex` entirely.
 *
 * [groupPerBucket] honours AGP's `LIBRARIES_MERGING_THRESHOLD`: below the threshold external libraries are
 * merged per library (each archive bucket → its own indexed dex group, i.e. more `classes*.dex` files
 * but finer change isolation); at/above it they are merged together into one group (fewer dex files). The
 * packager flattens + renumbers whichever layout results.
 */
internal class DexMergeTask(
    override val name: TaskName,
    private val dexArchives: List<Path>,
    private val androidJar: Path,
    private val minApi: Int,
    private val release: Boolean,
    private val outDexDir: Path,
    private val dexer: Dexer,
    private val groupPerBucket: Boolean = false,
    /** Max class-dex per merge batch (the "Dex merge batch size" setting); a scope larger than this is split
     *  into bounded chunks (native multidex only). Clamped ≥ 1 so a bad pref can't crash chunking. */
    private val mergeChunk: Int = DEFAULT_MERGE_CHUNK,
    /** Shared content-addressed cache for the MERGED dex output, keyed by the input bucket-hash set + dexing
     *  params. Wired only for the stable EXTERNAL-library scope (whose input is immutable pinned libraries), so
     *  the merged layer is dexed once per machine and reused across builds/cleans/projects — the per-lib archives
     *  already survive a clean, but the merge did not. Null = no shared merge cache (per-project only). */
    private val mergeCacheRoot: Path? = null,
) : Task {
    /**
     * The archive *buckets* under [roots]: a per-class archive ([DexArchiveBuilderTask]) lays its `.dex` out
     * either directly under the root (the project scope) or under one content-hash subdir per library (the
     * lib/ext scopes). Treat each hash subdir as a bucket, or the root itself when it holds no hash subdirs,
     * so a `.dex`'s path *relative to its bucket* is the class path (`androidx/collection/ArrayMapKt.dex`) —
     * stable across buckets, hence usable as a dedup key.
     */
    private fun bucketsOf(roots: List<Path>): List<Path> =
        roots.filter { Files.isDirectory(it) }.flatMap { root ->
            val hashBuckets = Files.list(root).use { s ->
                s.filter { Files.isDirectory(it) && HASH_BUCKET.matches(it.fileName.toString()) }
                    .sorted().collect(Collectors.toList())
            }
            if (hashBuckets.isEmpty()) listOf(root) else hashBuckets
        }

    private fun dexesIn(bucket: Path): List<Path> = Files.walk(bucket).use { s ->
        s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList())
    }

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("archives", dexArchives)
            property("minApi", minApi)
            property("release", release)
            property("perBucket", groupPerBucket)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("dex", outDexDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outDexDir)
        val buckets = bucketsOf(dexArchives)
        val perBucketDexes = buckets.map { dexesIn(it) }

        // Shared merged-dex cache (wired only for the stable EXTERNAL scope): the merged indexed dex is a pure
        // function of the input library set + dexing params, so content-address it by the set of input bucket
        // hashes and reuse it across builds/cleans/projects. The per-lib ARCHIVES already survive a clean, but the
        // merged OUTPUT did not — so mergeExtDex re-ran on every clean; a cache hit copies the merge instead.
        val cached = mergeCacheRoot?.takeIf { buckets.isNotEmpty() }?.resolve(mergeCacheKey(buckets))
        if (cached != null && DexArchives.hasDex(cached)) {
            DexArchives.clearDir(outDexDir); DexArchives.copyDir(cached, outDexDir)
            ctx.logger()("${name.value}: reused merged dex from shared cache (${buckets.size} input(s))")
            return TaskResult.Success
        }
        val result = runMerge(ctx, buckets, perBucketDexes)
        if (result == TaskResult.Success && cached != null) {
            runCatching { DexArchives.clearDir(cached); DexArchives.publishToCache(outDexDir, cached) }
        }
        return result
    }

    /** Cache key for the merged output: the (order-independent) set of input bucket dir names — which are the
     *  per-library content hashes for the ext scope — folded with the dexing params + format stamp. */
    private fun mergeCacheKey(buckets: List<Path>): String =
        DexArchives.digestOf(buckets.map { it.fileName.toString() } + listOf("minApi$minApi", if (release) "rel" else "dbg", DEX_CACHE_FORMAT))

    private suspend fun runMerge(ctx: TaskContext, buckets: List<Path>, perBucketDexes: List<List<Path>>): TaskResult {
        if (groupPerBucket) {
            // One indexed group per archive bucket (each input library stays its own set of dex files). Buckets
            // are independent, so the merges can run concurrently AND — when the dexer forks the merge into a
            // separate big-heap VM — be batched: a forking dexer reports a [MergePlan] that coalesces many
            // per-library buckets into a handful of forked invocations (instead of forking once per library, the
            // serial flood on a phone) and runs a few at once. The in-process default leaves one group per bucket
            // and bounds concurrency by the app heap (`DexConcurrency`) — i.e. desktop behaviour is unchanged.
            DexArchives.clearDir(outDexDir); Files.createDirectories(outDexDir)
            val plan = resolveMergePlan(buckets.size)
            // Per-library mode historically tolerated the SAME class arriving from two libraries (a shaded /
            // duplicated dependency the resolver can't coordinate-dedup): each library was its own indexed group,
            // so ART resolved the duplicate first-wins across dex files at runtime. Batching collapses libraries
            // into shared groups, where D8 instead rejects the duplicate ("Type … is defined multiple times").
            // Preserve the tolerance by keeping each class once across the scope — first bucket in sorted order
            // wins, the same copy runtime would have used — which also shrinks the APK. (Merge-all mode below
            // stays passthrough: a duplicate there is dirty input and D8 surfacing it is correct — see
            // DexMergePassthroughTest.)
            val seen = HashSet<String>()
            var dropped = 0
            val deduped = buckets.indices.map { bi ->
                val bucket = buckets[bi]
                perBucketDexes[bi].filter { dex ->
                    seen.add(bucket.relativize(dex).toString().replace('\\', '/')).also { if (!it) dropped++ }
                }
            }
            if (dropped > 0) ctx.logger()("${name.value}: $dropped duplicate class dex collapsed across libraries (first-wins)")
            val groups = coalesce(deduped, plan.maxInvocations).filter { it.isNotEmpty() }
            return mergeGroups(ctx, groups, plan)
        }
        // Merge-all: hand every input dex to D8 as-is — duplicate-class avoidance is the dependency resolver's
        // job (capability conflict eviction + version/variant selection produce a clean graph). A class arriving
        // twice is dirty input, and D8 surfacing it (a dup landing in the same bucket) is correct — no dedup here.
        // Pair each dex with its class-path (relative to its archive bucket) for stable bucketing.
        val entries = buckets.indices.flatMap { bi ->
            perBucketDexes[bi].map { buckets[bi].relativize(it).toString().replace('\\', '/') to it }
        }
        // An empty layer is valid (e.g. mergeLibDex with no sub-module deps) — produce an empty dex dir.
        if (entries.isEmpty()) { DexArchives.clearDir(outDexDir); return TaskResult.Success }

        // Native multidex (minApi >= 21): distribute classes into a fixed set of buckets and re-merge only the
        // buckets whose classes changed — AGP's DexMergingTask bucketing + incremental reuse. ART loads every
        // classes*.dex at install, so N indexed groups are runtime-equivalent to one merge (just less cross-dex
        // string dedup) while editing one class re-merges its one bucket instead of the whole scope.
        if (minApi >= 21) return bucketedIncrementalMerge(ctx, entries)

        // Mono-/legacy-multidex (< 21): must be a single classes.dex set, so one merge over everything.
        DexArchives.clearDir(outDexDir); Files.createDirectories(outDexDir)
        val dexes = entries.map { it.second }
        val r = dexer.dex(dexes, androidJar, minApi, release, outDexDir, resolveMergePlan(1).threadsPerInvocation)
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
        if (!r.success) return TaskResult.Failed(DexDiagnostics.firstError(r.log) ?: "dex merge failed")
        val produced = dexesIn(outDexDir).size
        ctx.logger()("${name.value}: merged ${dexes.size} class dex -> $produced output dex")
        return TaskResult.Success
    }

    /**
     * AGP-faithful native-multidex merge ([com.android.build.gradle.internal.tasks] `DexMergingTask`): assign each
     * per-class `.dex` to one of a FIXED number of buckets by a stable hash of its class path, merge each bucket
     * into its own `g<b>` indexed group, and — keyed by a persisted per-bucket signature — re-merge only the
     * buckets whose classes changed, leaving the rest byte-for-byte. So editing one class re-merges one bucket
     * instead of the whole scope. No dedup: two copies of a class hash to the same bucket and D8 surfaces the
     * duplicate, exactly as the pass-through merge did.
     *
     * State lives in a sibling `<dex>.merge-state` dir (NOT under [outDexDir], so it doesn't perturb the output
     * fingerprint the packager/engine see): a [DexArchives.HashCache] fingerprints each input dex by path+size+
     * mtime (unchanged dex aren't re-read) and a manifest records each bucket's signature. Missing/stale state or
     * a changed bucket count falls back to re-merging the affected buckets — self-healing, never stale.
     */
    private suspend fun bucketedIncrementalMerge(ctx: TaskContext, entries: List<Pair<String, Path>>): TaskResult {
        val stateDir = outDexDir.resolveSibling("${outDexDir.fileName}.merge-state")
        Files.createDirectories(stateDir); Files.createDirectories(outDexDir)
        val n = numBuckets(entries.size)
        val hashCache = DexArchives.HashCache(stateDir)
        val byBucket = Array(n) { ArrayList<Path>() }
        val sigParts = Array(n) { ArrayList<String>() }
        for ((rel, abs) in entries) {
            val b = Math.floorMod(rel.hashCode(), n)
            byBucket[b].add(abs)
            sigParts[b].add(rel + " " + hashCache.hashOf(abs))
        }
        hashCache.flush()
        val sig = Array(n) { DexArchives.digestOf(sigParts[it]) }   // order-independent digest of a bucket's classes
        val prev = readMergeManifest(stateDir)                       // (bucketCount, bucket -> signature)
        val manifestValid = prev.first == n
        pruneStaleOutputs(n)                                          // drop anything but the current g<0..n-1> buckets
        val toMerge = (0 until n).filter { b ->
            byBucket[b].isNotEmpty() && (!manifestValid || prev.second[b] != sig[b] || !DexArchives.hasDex(groupDir(b)))
        }
        // Buckets that hold nothing this run: clear any stale group dir.
        (0 until n).filter { byBucket[it].isEmpty() }.forEach { DexArchives.clearDir(groupDir(it)) }
        val reused = (0 until n).count { byBucket[it].isNotEmpty() } - toMerge.size
        if (toMerge.isEmpty()) {
            ctx.logger()("${name.value}: ${entries.size} class dex in $n bucket(s); all up-to-date (reused $reused)")
            writeMergeManifest(stateDir, n, sig); return TaskResult.Success
        }
        ctx.logger()("${name.value}: ${entries.size} class dex in $n bucket(s); re-merging ${toMerge.size}, reusing $reused")
        val plan = resolveMergePlan(toMerge.size)
        val sem = Semaphore(plan.concurrency.coerceAtLeast(1))
        val ok = AtomicBoolean(true)
        val failMsg = java.util.concurrent.atomic.AtomicReference<String?>(null)
        coroutineScope {
            toMerge.map { b ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        ctx.checkCanceled()
                        val g = groupDir(b); DexArchives.clearDir(g); Files.createDirectories(g)
                        val r = dexer.dex(byBucket[b], androidJar, minApi, release, g, plan.threadsPerInvocation)
                        r.log.forEach(ctx.logger()); ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
                        if (!r.success) { ok.set(false); DexDiagnostics.firstError(r.log)?.let { failMsg.compareAndSet(null, it) } }
                    }
                }
            }.awaitAll()
        }
        if (!ok.get()) return TaskResult.Failed(failMsg.get() ?: "dex merge failed")
        writeMergeManifest(stateDir, n, sig)   // record only after a fully successful merge
        return TaskResult.Success
    }

    private fun groupDir(b: Int): Path = outDexDir.resolve("g$b")

    /**
     * Remove anything under [outDexDir] that isn't a current bucket dir `g<0..n-1>`: a stale `classes*.dex` or
     * chunk dir left by the pre-bucketing single-merge layout, and `g<idx>` from a run with a larger bucket
     * count. In-range bucket dirs are kept so unchanged ones can be reused; the packager would otherwise glob a
     * lingering stale `.dex` into the APK.
     */
    private fun pruneStaleOutputs(n: Int) {
        if (!Files.isDirectory(outDexDir)) return
        val keep = (0 until n).mapTo(HashSet()) { "g$it" }
        Files.list(outDexDir).use { s ->
            s.filter { it.fileName.toString() !in keep }.forEach { DexArchives.clearDir(it) }
        }
    }

    /** Bucket count: a stable-per-machine value (from cores), raised only for a very large scope so a single
     *  bucket's working set stays near [mergeChunk]. Stable across edits for any realistic app (< mergeChunk×cores
     *  classes → the cores value), so a class always lands in the same bucket and incremental reuse holds. */
    private fun numBuckets(total: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_MERGE_BUCKETS)
        val chunkBound = if (mergeChunk > 0) Math.ceil(total.toDouble() / mergeChunk).toInt() else cores
        return maxOf(cores, chunkBound).coerceIn(1, MAX_MERGE_BUCKETS)
    }

    private fun readMergeManifest(stateDir: Path): Pair<Int, Map<Int, String>> {
        val f = stateDir.resolve(MERGE_MANIFEST)
        if (!Files.isRegularFile(f)) return 0 to emptyMap()
        return runCatching {
            val lines = Files.readAllLines(f)
            val n = lines.firstOrNull()?.removePrefix("n=")?.trim()?.toIntOrNull() ?: 0
            val map = HashMap<Int, String>()
            for (line in lines.drop(1)) {
                val sp = line.indexOf(' '); if (sp <= 0) continue
                line.substring(0, sp).toIntOrNull()?.let { map[it] = line.substring(sp + 1) }
            }
            n to map
        }.getOrDefault(0 to emptyMap())
    }

    private fun writeMergeManifest(stateDir: Path, n: Int, sig: Array<String>) {
        runCatching {
            val sb = StringBuilder("n=$n\n")
            for (b in sig.indices) sb.append(b).append(' ').append(sig[b]).append('\n')
            Files.createDirectories(stateDir); Files.write(stateDir.resolve(MERGE_MANIFEST), sb.toString().toByteArray())
        }
    }

    /** The dexer's own merge plan (forked dexers batch + parallelize against device RAM); else the in-process,
     *  app-heap-bounded default — one group per input, concurrency from [DexConcurrency]. */
    private fun resolveMergePlan(inputCount: Int): MergePlan =
        dexer.mergePlan(inputCount) ?: DexConcurrency.plan(inputCount).let {
            MergePlan(maxInvocations = Int.MAX_VALUE, concurrency = it.workers, threadsPerInvocation = it.threadsPerInvocation)
        }

    /**
     * Coalesce [units] (each a bucket's `.dex` list) into at most [maxGroups] contiguous, size-balanced batches.
     * Units are already in sorted-bucket order, so contiguous batching is deterministic — the g-index is the
     * batch's position regardless of merge-completion order. `maxGroups >= units.size` (or ≤ 0) is a no-op:
     * one group per unit, the original per-bucket layout.
     */
    private fun coalesce(units: List<List<Path>>, maxGroups: Int): List<List<Path>> {
        if (maxGroups <= 0 || maxGroups >= units.size) return units
        val base = units.size / maxGroups
        val rem = units.size % maxGroups
        val groups = ArrayList<List<Path>>(maxGroups)
        var i = 0
        for (g in 0 until maxGroups) {
            val take = base + if (g < rem) 1 else 0
            val batch = ArrayList<Path>()
            repeat(take) { batch.addAll(units[i++]) }
            groups.add(batch)
        }
        return groups
    }

    /** Merge each (non-empty) group into its own `g<i>` indexed dex dir, running [MergePlan.concurrency] at once
     *  with [MergePlan.threadsPerInvocation] D8 threads each. The packager globs every `g<i>` dir and renumbers. */
    private suspend fun mergeGroups(ctx: TaskContext, groups: List<List<Path>>, plan: MergePlan): TaskResult {
        if (groups.isEmpty()) return TaskResult.Success
        val sem = Semaphore(plan.concurrency.coerceAtLeast(1))
        val ok = AtomicBoolean(true)
        val failMsg = java.util.concurrent.atomic.AtomicReference<String?>(null)
        coroutineScope {
            groups.mapIndexed { i, dexes ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        ctx.checkCanceled()
                        if (dexes.isNotEmpty()) {
                            val group = outDexDir.resolve("g$i"); Files.createDirectories(group)
                            val r = dexer.dex(dexes, androidJar, minApi, release, group, plan.threadsPerInvocation)
                            r.log.forEach(ctx.logger())
                            ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
                            if (!r.success) { ok.set(false); DexDiagnostics.firstError(r.log)?.let { failMsg.compareAndSet(null, it) } }
                        }
                    }
                }
            }.awaitAll()
        }
        return if (ok.get()) TaskResult.Success else TaskResult.Failed(failMsg.get() ?: "dex merge failed")
    }

    internal companion object {
        /** A [DexArchiveBuilderTask] content-hash bucket dir name (24-char lowercase hex; see DexArchives). */
        private val HASH_BUCKET = Regex("[0-9a-f]{24}")

        /** Default for [mergeChunk] when the "Dex merge batch size" setting is unset: the target maximum class-dex
         *  per merge bucket, so a very large scope splits into more buckets to keep each merge's working set bounded. */
        const val DEFAULT_MERGE_CHUNK = 6000

        /** Upper bound on native-multidex merge buckets: enough for parallelism + incremental reuse, capped so the
         *  packaged `classes*.dex` count stays modest (Android L caps a native-multidex app at ~100 dex files). */
        const val MAX_MERGE_BUCKETS = 8

        /** Per-bucket signature manifest for the incremental merge, kept in the sibling `<dex>.merge-state` dir. */
        private const val MERGE_MANIFEST = "merge-manifest.txt"
    }
}

/**
 * `minify<Variant>WithR8`: the release dexing path. R8 shrinks/optimizes/obfuscates and dexes every
 * input in a single step (replacing dexBuilder->mergeProjectDex/mergeLibDex->mergeDex). [keepRuleFiles]
 * (aapt2's manifest-derived rules + the build type's proguardFiles + AAR consumer rules) and [inlineRules]
 * (the build type's raw `proguardRules`) decide what survives; [fullMode] selects R8 full vs ProGuard-compat
 * mode; [mappingOutput] receives `mapping.txt`; [resources] (when set) shrinks resources in the same run;
 * [desugaredLibrary] (when set) applies core-library desugaring and emits the L8 keep rules. Class directories
 * are jarred into [stagingDir] first (R8 reads jars/class files, not raw dirs). [Shrinker] is the injected R8 port.
 */
internal class R8MinifyTask(
    override val name: TaskName,
    private val programs: List<Path>,
    private val androidJar: Path,
    private val minApi: Int,
    private val keepRuleFiles: List<Path>,
    private val inlineRules: List<String>,
    private val fullMode: Boolean,
    private val stagingDir: Path,
    private val outDexDir: Path,
    private val shrinker: Shrinker,
    private val mappingOutput: Path? = null,
    private val resources: ResourceShrink? = null,
    private val desugaredLibrary: DesugaredLibrary? = null,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("classes", programs.filter { Files.isDirectory(it) })
            filePaths("jars", programs.filter { !Files.isDirectory(it) })
            filePaths("keep", keepRuleFiles.filter { Files.exists(it) })
            property("inlineRules", inlineRules.joinToString("\n"))
            property("fullMode", fullMode)
            property("minApi", minApi)
            property("androidJar", androidJar.toString())
            resources?.let { filePaths("resIn", listOf(it.inputAp).filter { p -> Files.exists(p) }) }
            desugaredLibrary?.let { filePaths("desugarConfig", listOf(it.configJson).filter { p -> Files.exists(p) }) }
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            dirPath("dex", outDexDir)
            mappingOutput?.let { filePath("mapping", it) }
            resources?.let { filePath("resOut", it.outputAp) }
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        withContext(Dispatchers.IO) {
            Files.createDirectories(stagingDir)
            Files.createDirectories(outDexDir)
        }

        val inputs = programs.filter { Files.exists(it) }.mapIndexed { i, p ->
            if (Files.isDirectory(p)) stagingDir.resolve("in$i.jar")
                .also { ApkPackaging.jarClasses(p, it) } else p
        }
        if (inputs.isEmpty()) return TaskResult.Failed("nothing to minify")
        // Cap R8's worker pool: it's the heaviest in-process step, so fewer threads = a smaller peak heap.
        val threads = DexConcurrency.plan(1).threadsPerInvocation
        // R8 is a single whole-program pass over EVERY input (app + sub-modules + external libs); its peak heap
        // scales with this total. Log the input scale + tuning up front so an OOM profile (pair with the
        // `ide.mem` heap heartbeat) can size the whole-program working set against the device heap ceiling.
        val inputMb = inputs.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) } / (1024L * 1024L)
        ctx.logger()(
            "${name.value}: R8 whole-program shrink+dex via ${shrinker::class.simpleName} — " +
                "${inputs.size} input jar(s), ~${inputMb}MB classes, threads=$threads, " +
                "fullMode=$fullMode, minApi=$minApi" +
                (if (resources != null) ", +resourceShrink" else "") +
                (if (desugaredLibrary != null) ", +coreLibDesugar" else "")
        )
        val r = shrinker.shrink(
            ShrinkRequest(
                programs = inputs,
                library = androidJar,
                keepRuleFiles = keepRuleFiles,
                inlineRules = inlineRules,
                minApi = minApi,
                release = true,
                fullMode = fullMode,
                outDir = outDexDir,
                mappingOutput = mappingOutput,
                resources = resources,
                desugaredLibrary = desugaredLibrary,
                threads = threads,
            )
        )
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("r8", r.log, DiagnosticKind.DEX)
        return if (r.success) TaskResult.Success else TaskResult.Failed(DexDiagnostics.firstError(r.log) ?: "R8 minify failed")
    }
}

/**
 * `shrinkResources<Variant>`: convert the R8-shrunk proto resources back to aapt2's binary format for
 * packaging (R8's resource shrinker reads/writes proto). Falls back to the un-shrunk proto archive when
 * R8 emitted no shrunk output, so a resource-shrinker hiccup never yields a broken APK - only a larger one.
 */
internal class ConvertResourcesTask(
    override val name: TaskName,
    private val shrunkProtoAp: Path,
    private val protoApFallback: Path,
    private val outBinaryAp: Path,
    private val aapt2: Aapt2,
) : Task {
    private fun source(): Path = if (Files.isRegularFile(shrunkProtoAp)) shrunkProtoAp else protoApFallback

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply { filePaths("proto", listOf(source())) }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("ap", outBinaryAp) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val src = source()
        if (!Files.isRegularFile(src)) return TaskResult.Failed("no proto resources to convert: $src")
        if (src == protoApFallback) ctx.logger()("resource shrinking produced no output; packaging un-shrunk resources")
        val r = aapt2.convert(src, outBinaryAp, toProto = false)
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("aapt2", r.log, DiagnosticKind.RESOURCE)
        return if (r.success) TaskResult.Success else TaskResult.Failed("aapt2 convert (proto->binary) failed")
    }
}

/**
 * `l8DexDesugarLib<Variant>`: compile the core-library desugaring runtime (`desugar_jdk_libs`) to dex via
 * L8, using the keep rules R8/D8 emitted for it. The resulting dex is packaged alongside the app dex so the
 * backported `java.*` APIs are present at runtime below the native API level.
 */
internal class L8DexTask(
    override val name: TaskName,
    private val desugarJdkLibs: Path,
    private val configJson: Path,
    private val keepRules: Path,
    private val androidJar: Path,
    private val minApi: Int,
    private val release: Boolean,
    private val outDexDir: Path,
    private val shrinker: Shrinker,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("lib", listOf(desugarJdkLibs).filter { Files.exists(it) })
            filePaths("config", listOf(configJson).filter { Files.exists(it) })
            filePaths("keep", listOf(keepRules).filter { Files.exists(it) })
            property("minApi", minApi)
            property("release", release)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("dex", outDexDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outDexDir)
        val r = shrinker.l8(L8Request(desugarJdkLibs, configJson, keepRules, androidJar, minApi, release, outDexDir))
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("l8", r.log, DiagnosticKind.DEX)
        return if (r.success) TaskResult.Success else TaskResult.Failed(DexDiagnostics.firstError(r.log) ?: "L8 desugared-library dexing failed")
    }
}

/**
 * `merge<Variant>JavaResource`: gather the module's own Java resources (`src/<set>/resources`) plus the
 * non-class entries of its sub-module + external dependency jars into one `merged-java-res.jar`, applying
 * the resources packaging rules (AGP defaults + the module's config; see [JavaResMerger]/[PackagingRules]).
 * The packager copies that jar's entries into the APK root. Depends on the sub-module jars it reads.
 */
internal class MergeJavaResourcesTask(
    override val name: TaskName,
    private val resourceDirs: List<Path>,
    private val jars: List<Path>,
    private val filter: PackagingRules.Filter,
    private val outJar: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("res", resourceDirs)
            filePaths("jars", jars)
            property("rules", filter.fingerprint)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("jar", outJar) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            val n = JavaResMerger.merge(resourceDirs, jars, filter, outJar) { ctx.logger()("mergeJavaResource: $it") }
            ctx.logger()("mergeJavaResource -> ${outJar.fileName} ($n entries)")
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("mergeJavaResource failed: ${it.message}", it) }
    }
}

/**
 * `merge<Variant>NativeLibs`: gather every `.so` (the module's `src/<set>/jniLibs`, dependency-library
 * jniLibs + an AAR's `jni` dir, and the `.so` entries under `lib` inside dependency jars) into one
 * `<abi>`-laid-out directory the packager maps under `lib`, applying the jniLibs packaging rules
 * (see [NativeLibsMerger]).
 */
internal class MergeNativeLibsTask(
    override val name: TaskName,
    private val jniDirs: List<Path>,
    private val jars: List<Path>,
    private val filter: PackagingRules.Filter,
    private val outDir: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("jni", jniDirs)
            filePaths("jars", jars)
            property("rules", filter.fingerprint)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("merged", outDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            val n = NativeLibsMerger.merge(jniDirs, jars, filter, outDir) { ctx.logger()("mergeNativeLibs: $it") }
            ctx.logger()("mergeNativeLibs -> ${outDir.fileName} ($n libraries)")
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("mergeNativeLibs failed: ${it.message}", it) }
    }
}

/** `packageApk`: assemble the unsigned APK from `resources.ap_` + `classes*.dex` + assets + jni libs +
 *  merged Java resources. The [dexDirs] are the merged dex layers (project / lib / external for native
 *  multidex, or a single merged dir for mono-/legacy-multidex); the packager renumbers their `.dex` files
 *  into one `classes*.dex` set. [javaResJars] hold non-code entries copied to the APK root. */
internal class PackageApkTask(
    override val name: TaskName,
    private val resourcesAp: Path,
    private val dexDirs: List<Path>,
    private val assetsDirs: List<Path>,
    private val jniLibDirs: List<Path>,
    private val outApk: Path,
    private val javaResJars: List<Path> = emptyList(),
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("ap", listOf(resourcesAp))
            dirPaths("dex", dexDirs)
            dirPaths("assets", assetsDirs)
            dirPaths("jni", jniLibDirs)
            filePaths("javaRes", javaResJars)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("apk", outApk) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            val names =
                ApkPackaging.assembleApk(resourcesAp, dexDirs, assetsDirs, jniLibDirs, outApk, javaResJars)
            ctx.logger()("packageApk -> ${outApk.fileName} (${names.size} entries)")
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("packageApk failed: ${it.message}", it) }
    }
}

/** `sign`: zipalign + apksigner the unsigned APK into the final signed APK. */
internal class SignApkTask(
    override val name: TaskName,
    private val unsignedApk: Path,
    private val signedApk: Path,
    private val config: SigningConfig,
    private val signer: ApkSigner,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("unsigned", listOf(unsignedApk))
            property("keystore", config.keystore.toString())
            property("alias", config.keyAlias)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("apk", signedApk) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val r = signer.sign(unsignedApk, signedApk, config)
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("apksigner", r.log, DiagnosticKind.PACKAGING)
        return if (r.success) TaskResult.Success else TaskResult.Failed("apk signing failed")
    }
}


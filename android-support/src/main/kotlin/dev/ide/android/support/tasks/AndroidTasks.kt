package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Aapt2
import dev.ide.android.support.tools.ApkSigner
import dev.ide.android.support.tools.DesugaredLibrary
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.L8Request
import dev.ide.android.support.tools.ResourceShrink
import dev.ide.android.support.tools.ShrinkRequest
import dev.ide.android.support.tools.Shrinker
import dev.ide.android.support.tools.SigningConfig
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
import dev.ide.lang.kotlin.compile.ComposeCompilerPlugin
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
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
        clear(outDir)
        Files.createDirectories(outDir)
        val values = ValuesMerger()
        resDirs.filter { Files.isDirectory(it) }.forEach { dir ->
            Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { f ->
                    val rel = dir.relativize(f)
                    val qualifier = rel.getName(0).toString()
                    if (qualifier.startsWith("values")) {
                        // Accumulate entries; ascending priority means a later source's entry wins.
                        if (!values.add(qualifier, f)) copyRaw(
                            f,
                            outDir.resolve(qualifier)
                                .resolve("unparsed_${values.bump()}_${f.fileName}")
                        )
                    } else {
                        copyRaw(
                            f, outDir.resolve(rel)
                        ) // overlay: a higher-priority source overwrites
                    }
                }
            }
        }
        values.writeTo(outDir)
        ctx.logger()("mergeResources -> ${outDir.fileName}")
        return TaskResult.Success
    }

    private fun copyRaw(from: Path, to: Path) {
        to.parent?.let { Files.createDirectories(it) }
        Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun clear(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
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
            bucket[key] = doc.importNode(el, true) as org.w3c.dom.Element   // last source wins
        }
        return true
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
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("res", resDirs)
            if (Files.isRegularFile(manifest)) filePaths("manifest", listOf(manifest))
            property("package", packageName)
            property("androidJar", androidJar.toString())
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("R", genDir) }

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

/** `compileJava`: compile the variant's Java sources + generated `R.java` against the Android classpath. */
internal class AndroidCompileTask(
    override val name: TaskName,
    private val sourceRoots: List<Path>,
    private val genJavaDir: Path,
    private val classpath: List<Path>,   // android.jar (compileOnly) + dependency outputs/jars
    private val outClasses: Path,
    private val level: String,
    /** ecj `-bootclasspath`: empty on the desktop (host JRE), `android.jar` + desugar stubs on ART. */
    private val bootClasspath: List<Path>,
) : Task {
    // NB: `sourceRoots + genJavaDir` would bind Collection.plus(Iterable) — a Path is an Iterable<Path>
    // of its name components — and silently scatter the gen dir into segments. Append as a single element.
    private fun sources(): List<Path> =
        (sourceRoots + listOf(genJavaDir)).filter { Files.isDirectory(it) }.flatMap { root ->
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                    .collect(Collectors.toList())
            }
        }

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
        if (srcs.isEmpty()) {
            return TaskResult.Success
        }

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
    override val name: TaskName,
    private val sourceRoots: List<Path>,
    private val genJavaDir: Path,        // R.java etc. — interop resolution input, not emitted
    private val classpath: List<Path>,   // android.jar (+ desugar stubs) + dependency outputs/jars + R classes
    private val outClasses: Path,        // the kotlin-classes output dir
    private val level: String,
    /** K2 bootclasspath: empty on the desktop (host JDK), `android.jar` + desugar stubs on ART. */
    private val bootClasspath: List<Path>,
    private val compiler: IncrementalKotlinCompiler,
) : Task {
    private fun walk(roots: List<Path>, ext: String): List<Path> =
        roots.filter { Files.isDirectory(it) }.flatMap { root ->
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(ext) }
                    .collect(Collectors.toList())
            }
        }

    private fun kotlinSources(): List<Path> = walk(sourceRoots, ".kt")
    private fun javaSources(): List<Path> = walk(sourceRoots + listOf(genJavaDir), ".java")

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
        // A module that depends on the Compose runtime must be compiled with the Compose compiler plugin.
        val composePlugin = if (ComposeCompilerPlugin.isComposeModule(classpath + bootClasspath))
            listOfNotNull(ComposeCompilerPlugin.jar()) else emptyList()
        val r = compiler.compile(
            kt, javaSources(), classpath, outClasses, level,
            bootClasspath = bootClasspath, compilerPlugins = composePlugin,
        )
        r.messages.forEach(ctx.logger())
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
) : Task {
    /** A cache key for the desugaring config: empty when disabled, so all cache namespaces are byte-identical
     *  to the no-desugaring build. Non-empty (content hash) when enabled, so toggling it busts stale dex. */
    private fun desugarConfigKey(): String =
        desugaredLibConfig?.takeIf { Files.exists(it) }?.let { DexArchives.fileHash(it) } ?: ""

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            dirPaths("project", projectClasses)
            filePaths("sub", subProjectJars)
            filePaths("ext", externalJars)
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
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        // The library type-universe (sub-module + external jars) is D8's desugaring classpath when archiving a
        // library, so it can resolve interface hierarchies for default/static-interface-method (and lambda)
        // desugaring below the native API level — without it D8 warns "Type ... was not found ...". Project
        // (app) classes are deliberately excluded: nothing a library dexes depends on them (deps point down),
        // and folding them in would invalidate the library cache on every app edit.
        val libUniverse =
            (subProjectJars + externalJars).filter { Files.exists(it) && Files.size(it) > 0L }
        val hashCache = DexArchives.HashCache(subDexRoot.parent ?: subDexRoot)
        val hashOf = libUniverse.associateWith { hashCache.hashOf(it) }
        hashCache.flush()
        // Deduplicate the desugaring classpath by content hash. The same artifact can reach the dexer via more
        // than one path (two resolver cache paths, the shared dex cache, or as both a sub-module and an external
        // jar), and D8's `addClasspathFiles` aborts when a type is defined by two classpath entries ("Classpath
        // type already present: kotlin.sequences.SequencesKt"). Collapse byte-identical jars to one
        // representative — the same content-hash dedup the program inputs already get below.
        val universeByHash = LinkedHashMap<String, Path>()   // content hash -> representative jar
        for (j in libUniverse) hashOf[j]?.let { universeByHash.putIfAbsent(it, j) }
        // Content-hash dedup above only collapses byte-identical jars. Two *distinct* jars can still define the
        // same type — two resolved kotlin-stdlib versions, or a library that bundles stdlib classes — which D8
        // also rejects ("Classpath type already present: kotlin.jvm.internal.Intrinsics"). So index each jar's
        // class entries and dedupe the desugaring classpath at the class level (see [classpathFor]).
        val classesOf = universeByHash.values.associateWith { DexArchives.classNamesOf(it) }
        // A library's dexed output can depend on this classpath, so a shared-cache bucket is only safe to reuse
        // under an identical universe — fold the deduped digest into the cache namespace. The core-library
        // desugaring config also changes a jar's dex, so it joins the digest WHEN enabled; when disabled the
        // digest is byte-identical to a no-desugaring build (so existing caches stay valid).
        val cfgKey = desugarConfigKey()
        val desugarExtra = if (cfgKey.isEmpty()) emptyList() else listOf("cfg:$cfgKey")
        val desugarDigest = DexArchives.digestOf(universeByHash.keys + desugarExtra)

        var ok = archiveProject(ctx, universeByHash, classesOf)
        // Libraries are atomic jars → per-jar content-hash buckets (a changed lib re-dexes alone).
        ok = dexJars(
            ctx, subDexRoot, subProjectJars, hashOf, universeByHash, classesOf, desugarDigest
        ) && ok
        ok = dexJars(
            ctx, extDexRoot, externalJars, hashOf, universeByHash, classesOf, desugarDigest
        ) && ok
        return if (ok) TaskResult.Success else TaskResult.Failed("dexBuilder failed")
    }

    /**
     * Project scope, per class file: archive only the `.class` files whose content changed (tracked in a
     * `.classmanifest`), so editing one class re-dexes just it. Changed classes are the D8 *program*; the
     * unchanged ones + library jars are the desugaring *classpath* so a changed class still sees its siblings.
     * D8's `DexFilePerClassFile` writes `<class>.dex` straight into [projectDexRoot]; the merge resolves them.
     */
    private fun archiveProject(
        ctx: TaskContext, universeByHash: Map<String, Path>, classesOf: Map<Path, Set<String>>
    ): Boolean {
        Files.createDirectories(projectDexRoot)
        // Collect across every project-class root (Java output + Kotlin output) keyed by package-relative
        // path; a later root wins a (rare) name clash. Relpaths from distinct roots don't otherwise collide.
        val byRel = LinkedHashMap<String, Path>()
        val perRoot =
            LinkedHashMap<String, Int>()   // root name -> .class count contributed (Java vs Kotlin output)
        for (root in projectClasses.filter { Files.isDirectory(it) }) {
            var n = 0
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
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
            val unchanged = current.keys - changed.toSet()
            val classpath = ArrayList<Path>()
            if (unchanged.isNotEmpty()) {
                DexArchives.jarClasses(byRel, unchanged, restJar); classpath.add(restJar)
            }
            // Library universe, class-deduped so D8 never sees a type twice. Exclude the project's own classes
            // (the program + restJar) so a library that happens to redefine one can't collide with them either.
            classpath.addAll(classpathFor(universeByHash.values, classesOf, exclude = current.keys))
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
                ok = false; ctx.logger()("dex archive failed for project classes")
            }
        }
        // Verify every (re)dexed class actually produced a `.dex`. A dexer can report success yet silently drop
        // a class it couldn't process (e.g. D8 choking on Kotlin metadata newer than it supports) — which would
        // leave that class out of the APK (a launch-time ClassNotFoundException). Record ONLY classes whose
        // `.dex` exists, so a dropped class is re-dexed next build instead of being remembered as done; fail the
        // task (naming the casualties) rather than package an APK missing app code.
        val produced =
            current.keys.filter { Files.isRegularFile(projectDexRoot.resolve(dexRelOf(it))) }
                .toSet()
        // `module-info`/`package-info` carry no runtime code, so D8 emits no `.dex` for them — not a drop.
        val dropped =
            current.keys.filter { dexable(it) && it !in produced }   // changed this run or a stale prior drop
        if (dropped.isNotEmpty()) {
            ok = false
            ctx.logger()(
                "dexBuilder project scope: ${dropped.size} class(es) produced no .dex and will be ABSENT from the APK " + "(e.g. ${
                    dropped.take(5).joinToString { it.removeSuffix(".class").replace('/', '.') }
                }) — the dexer dropped them, often Kotlin metadata it can't parse")
        }
        // Record a class as done only if it dexed (or never needed to), so a dropped class re-dexes next build.
        DexArchives.writeClassManifest(
            projectDexRoot, current.filterKeys { it in produced || !dexable(it) })
        return ok
    }

    /** The per-class `.dex` path a `DexFilePerClassFile` archive writes for a `.class` at [classRel]. */
    private fun dexRelOf(classRel: String): String = classRel.removeSuffix(".class") + ".dex"

    /** Whether D8 emits a `.dex` for [classRel] — `module-info`/`package-info` (no runtime code) it does not. */
    private fun dexable(classRel: String): Boolean = classRel.substringAfterLast('/')
        .let { it != "module-info.class" && it != "package-info.class" }

    /**
     * Build a duplicate-free desugaring classpath from [candidates]. D8 aborts when two classpath entries
     * define the same type, so walk the jars in priority order and keep one only if it introduces no class
     * already provided — by an earlier-kept jar or by [exclude] (the program's own classes, which belong to
     * the input, not the classpath). A jar that fully overlaps an earlier one (e.g. a second kotlin-stdlib) is
     * dropped whole; a partial overlap is also dropped, at worst reviving a benign "Type not found" desugaring
     * warning for its unique classes — never a hard failure. A class-less jar (a resource-only AAR's
     * classes.jar) adds no types to the desugaring classpath and can't even be opened on ART when it is
     * empty (zero-entry zip → `ZipException: No entries`), so it is excluded.
     */
    private fun classpathFor(
        candidates: Collection<Path>, classesOf: Map<Path, Set<String>>, exclude: Set<String>
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

    /**
     * Archive each jar into `<root>/<contentHash>/`. Three tiers of reuse, then dex only the true misses —
     * and dex those in parallel, bounded by [DexConcurrency]:
     *  1. the module bucket already has dex (unchanged since last build here) → reuse, no work;
     *  2. the shared cross-project cache has it (another project/clean already dexed this exact jar) → copy;
     *  3. otherwise dex it once, then seed the shared cache so every other project skips it next time.
     * Jar content hashes ([hashOf], precomputed once) come from a path+size+mtime cache so unchanged libraries
     * aren't re-hashed each build. [universeByHash] is the content-hash-deduped desugaring classpath universe
     * (hash -> representative jar), [classesOf] each jar's class entries (for class-level dedup), and
     * [desugarDigest] keys the shared cache to it (see [execute]).
     */
    private suspend fun dexJars(
        ctx: TaskContext,
        root: Path,
        jars: List<Path>,
        hashOf: Map<Path, String>,
        universeByHash: Map<String, Path>,
        classesOf: Map<Path, Set<String>>,
        desugarDigest: String,
    ): Boolean {
        Files.createDirectories(root)
        val byHash =
            LinkedHashMap<String, Path>()                       // content hash -> a jar (dedups copies)
        // Skip jars with no class entries (a resource-only AAR's classes.jar): they dex to nothing, and an
        // empty/zero-entry jar can't be opened by ART's zip layer — never hand it to D8.
        for (jar in jars) hashOf[jar]?.let { h -> if (DexArchives.classNamesOf(jar).isNotEmpty()) byHash.putIfAbsent(h, jar) }
        val keep = byHash.keys.toHashSet()
        val todo =
            byHash.entries.filter { !DexArchives.hasDex(root.resolve(it.key)) }   // tier-1 reuse drops the rest
        if (todo.isEmpty()) {
            DexArchives.prune(root, keep); return true
        }

        val plan = DexConcurrency.plan(todo.size)
        val sem = Semaphore(plan.workers)
        val ok = AtomicBoolean(true)
        coroutineScope {
            todo.map { (hash, jar) ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        ctx.checkCanceled()
                        // Desugaring classpath = the library universe minus this jar's own content (by hash, so a
                        // second path can't put it back) and class-deduped (so a different jar can't redefine
                        // this jar's types, nor any type twice across the classpath).
                        val others = universeByHash.filterKeys { it != hash }.values
                        val classpath =
                            classpathFor(others, classesOf, exclude = classesOf[jar] ?: emptySet())
                        if (!dexOneLibrary(
                                ctx,
                                jar,
                                root.resolve(hash),
                                hash,
                                classpath,
                                desugarDigest,
                                plan.threadsPerInvocation
                            )
                        ) ok.set(false)
                    }
                }
            }.awaitAll()
        }
        DexArchives.prune(root, keep)
        return ok.get()
    }

    /** Tier 2/3 for one library: copy from the shared cache on a hit, else dex it (with [classpath] as the
     *  desugaring classpath) and seed the cache under the [desugarDigest]-keyed namespace. */
    private fun dexOneLibrary(
        ctx: TaskContext,
        jar: Path,
        bucket: Path,
        hash: String,
        classpath: List<Path>,
        desugarDigest: String,
        threads: Int
    ): Boolean {
        val shared = dexCacheRoot?.resolve(cacheTag(desugarDigest))?.resolve(hash)
        if (shared != null && DexArchives.hasDex(shared)) {
            DexArchives.clearDir(bucket); DexArchives.copyDir(shared, bucket)
            ctx.logger()("dex cache hit: ${jar.fileName}")
            return true
        }
        DexArchives.clearDir(bucket); Files.createDirectories(bucket)
        val r =
            dexer.dexArchive(listOf(jar), classpath, androidJar, minApi, release, bucket, threads, desugaredLibConfig)
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
        if (!r.success) {
            ctx.logger()("dex archive failed for ${jar.fileName}"); return false
        }
        if (shared != null && !DexArchives.hasDex(shared)) DexArchives.publishToCache(
            bucket, shared
        )
        return true
    }

    /** Shared-cache namespace: a dexed archive is only reusable for the same min-api, mode, dex format, and
     *  desugaring classpath (the library universe, [desugarDigest]). */
    private fun cacheTag(desugarDigest: String): String =
        "minApi$minApi-${if (release) "release" else "debug"}-$DEX_CACHE_FORMAT-cp$desugarDigest"
}

/**
 * Bumped whenever the bundled D8/R8 (`libs.versions.toml` `r8`) or the archive layout changes, so a tool
 * upgrade can't reuse stale dex from the shared cache. Folded into the cache namespace (see `cacheTag`).
 */
private const val DEX_CACHE_FORMAT = "v1-r8-8.13.19"

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

    /**
     * Collect the `.dex` to merge from [buckets], dropping a class that is defined by more than one bucket so
     * D8 never sees it twice ("Type ... is defined multiple times"). This happens when two distinct library
     * jars ship the same class — most often a Gradle *capability* conflict the Maven resolver doesn't model,
     * e.g. `androidx.collection:collection` and `:collection-ktx` both defining `androidx.collection.ArrayMapKt`.
     * Buckets are walked in sorted order so the winner is deterministic; only true duplicate definitions are
     * dropped (every unique class is kept). [seen] carries the keys across buckets/groups.
     */
    private fun dedupedDexes(bucket: Path, seen: MutableSet<String>): List<Path> =
        dexesIn(bucket).filter { dex ->
            seen.add(
                bucket.relativize(dex).toString().replace('\\', '/')
            )
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
        DexArchives.clearDir(outDexDir); Files.createDirectories(outDexDir)
        val buckets = bucketsOf(dexArchives)
        // One global key set so a class defined in two buckets is merged once, whether grouped or merged-all.
        val seen = HashSet<String>()
        val perBucketDexes =
            buckets.map { dedupedDexes(it, seen) }   // sequential → deterministic first-wins
        val dropped = buckets.sumOf { dexesIn(it).size } - seen.size
        if (dropped > 0) ctx.logger()("dex merge: dropped $dropped duplicate class dex (defined by more than one library)")

        if (groupPerBucket) {
            // One indexed group per archive bucket (each input library stays its own set of dex files). Buckets
            // are independent, so merge them in parallel; the group index is the bucket's sorted position, so
            // the output is deterministic regardless of completion order (an empty bucket just leaves a gap —
            // the packager globs every `g*/` dex).
            val plan = DexConcurrency.plan(buckets.size)
            val sem = Semaphore(plan.workers)
            val ok = AtomicBoolean(true)
            coroutineScope {
                perBucketDexes.mapIndexed { i, dexes ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            ctx.checkCanceled()
                            if (dexes.isNotEmpty()) {
                                val group = outDexDir.resolve("g$i"); Files.createDirectories(group)
                                val r = dexer.dex(
                                    dexes,
                                    androidJar,
                                    minApi,
                                    release,
                                    group,
                                    plan.threadsPerInvocation
                                )
                                r.log.forEach(ctx.logger())
                                ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
                                if (!r.success) ok.set(false)
                            }
                        }
                    }
                }.awaitAll()
            }
            return if (ok.get()) TaskResult.Success else TaskResult.Failed("dex merge failed")
        }
        val dexes = perBucketDexes.flatten()
        // An empty layer is valid (e.g. mergeLibDex with no sub-module deps) — produce an empty dex dir.
        if (dexes.isEmpty()) return TaskResult.Success
        val r = dexer.dex(
            dexes,
            androidJar,
            minApi,
            release,
            outDexDir,
            DexConcurrency.plan(1).threadsPerInvocation
        )
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("d8", r.log, DiagnosticKind.DEX)
        if (!r.success) return TaskResult.Failed("dex merge failed")
        val produced = dexesIn(outDexDir).size
        ctx.logger()("${name.value}: merged ${dexes.size} class dex -> $produced output dex")
        return TaskResult.Success
    }

    private companion object {
        /** A [DexArchiveBuilderTask] content-hash bucket dir name (24-char lowercase hex; see DexArchives). */
        val HASH_BUCKET = Regex("[0-9a-f]{24}")
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
        Files.createDirectories(stagingDir)
        Files.createDirectories(outDexDir)
        val inputs = programs.filter { Files.exists(it) }.mapIndexed { i, p ->
            if (Files.isDirectory(p)) stagingDir.resolve("in$i.jar")
                .also { ApkPackaging.jarClasses(p, it) } else p
        }
        if (inputs.isEmpty()) return TaskResult.Failed("nothing to minify")
        // Cap R8's worker pool: it's the heaviest in-process step, so fewer threads = a smaller peak heap.
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
                threads = DexConcurrency.plan(1).threadsPerInvocation,
            )
        )
        r.log.forEach(ctx.logger())
        ctx.reportToolDiagnostics("r8", r.log, DiagnosticKind.DEX)
        return if (r.success) TaskResult.Success else TaskResult.Failed("R8 minify failed")
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
        return if (r.success) TaskResult.Success else TaskResult.Failed("L8 desugared-library dexing failed")
    }
}

/** `packageApk`: assemble the unsigned APK from `resources.ap_` + `classes*.dex` + assets + jni libs. The
 *  [dexDirs] are the merged dex layers (project / lib / external for native multidex, or a single merged
 *  dir for mono-/legacy-multidex); the packager renumbers their `.dex` files into one `classes*.dex` set. */
internal class PackageApkTask(
    override val name: TaskName,
    private val resourcesAp: Path,
    private val dexDirs: List<Path>,
    private val assetsDirs: List<Path>,
    private val jniLibDirs: List<Path>,
    private val outApk: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("ap", listOf(resourcesAp))
            dirPaths("dex", dexDirs)
            dirPaths("assets", assetsDirs)
            dirPaths("jni", jniLibDirs)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("apk", outApk) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            val names =
                ApkPackaging.assembleApk(resourcesAp, dexDirs, assetsDirs, jniLibDirs, outApk)
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


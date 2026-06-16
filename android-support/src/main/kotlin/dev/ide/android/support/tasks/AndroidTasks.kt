package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Aapt2
import dev.ide.android.support.tools.ApkSigner
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.Shrinker
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.build.engine.JavaCompile
import dev.ide.build.engine.KotlinCompile
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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
 * libraries, then the app's own source sets — into one merged `res/` dir. [resDirs]
 * is in *ascending* priority, so a later (higher-priority) source overrides an earlier one for the same
 * resource. Non-`values` files overlay by path (higher wins); `values*` files are all kept under unique
 * names so aapt2 merges their entries. The merged dir is the single input to aapt2 compile.
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
        resDirs.filter { Files.isDirectory(it) }.forEachIndexed { idx, dir ->
            Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.forEach { f ->
                val rel = dir.relativize(f)
                val qualifier = rel.getName(0).toString()
                val target = if (qualifier.startsWith("values")) {
                    // keep every contribution so aapt2 merges value entries (filenames would otherwise clash)
                    outDir.resolve(qualifier).resolve("merged_${idx}_${f.fileName}")
                } else {
                    outDir.resolve(rel) // overlay: a higher-priority source (later idx) overwrites
                }
                target.parent?.let { Files.createDirectories(it) }
                Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } }
        }
        ctx.logger()("mergeResources -> ${outDir.fileName}")
        return TaskResult.Success
    }

    private fun clear(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
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
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
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
        if (!compile.result.success) return TaskResult.Failed("aapt2 compile (library R) failed")
        val r = aapt2.link(
            compile.archives, m, androidJar, packageName, emptyList(), minSdk, minSdk, genDir, throwawayAp,
            nonFinalIds = true,
        )
        r.log.forEach(ctx.logger())
        return if (r.success) TaskResult.Success else TaskResult.Failed("aapt2 link (library R) failed")
    }

    private fun synthesizeManifest(): Path {
        synthManifest.parent?.let { Files.createDirectories(it) }
        synthManifest.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$packageName\"/>\n")
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
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("compiled", outDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outDir)
        val r = aapt2.compile(resDirs, outDir)
        r.result.log.forEach(ctx.logger())
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
    private val genJavaDir: Path,
    private val resourcesAp: Path,
    private val aapt2: Aapt2,
) : Task {
    private fun archives(): List<Path> =
        if (Files.isDirectory(compiledDir))
            Files.list(compiledDir).use { s -> s.filter { it.toString().endsWith(".zip") }.sorted().toList() }
        else emptyList()

    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        dirPaths("compiled", listOf(compiledDir))
        filePaths("manifest", listOf(manifest))
        property("package", customPackage)
        property("extraPackages", extraPackages.joinToString(":"))
        property("minSdk", minSdk)
        property("targetSdk", targetSdk)
        property("androidJar", androidJar.toString())
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply {
        filePath("ap", resourcesAp)
        dirPath("R", genJavaDir)
    }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val r = aapt2.link(archives(), manifest, androidJar, customPackage, extraPackages, minSdk, targetSdk, genJavaDir, resourcesAp)
        r.log.forEach(ctx.logger())
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
    private val compile: JavaCompile,
) : Task {
    // NB: `sourceRoots + genJavaDir` would bind Collection.plus(Iterable) — a Path is an Iterable<Path>
    // of its name components — and silently scatter the gen dir into segments. Append as a single element.
    private fun sources(): List<Path> = (sourceRoots + listOf(genJavaDir))
        .filter { Files.isDirectory(it) }
        .flatMap { root -> Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.toList() } }

    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        filePaths("sources", sources())
        filePaths("classpath", classpath)
        property("level", level)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("classes", outClasses) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outClasses)
        val srcs = sources()
        if (srcs.isEmpty()) return TaskResult.Success
        val r = compile.compile(srcs, classpath, outClasses, level)
        r.messages.forEach(ctx.logger())
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
    private val compile: KotlinCompile,
) : Task {
    private fun walk(roots: List<Path>, ext: String): List<Path> = roots.filter { Files.isDirectory(it) }
        .flatMap { root -> Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(ext) }.toList() } }

    private fun kotlinSources(): List<Path> = walk(sourceRoots, ".kt")
    private fun javaSources(): List<Path> = walk(sourceRoots + listOf(genJavaDir), ".java")

    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        filePaths("kotlinSources", kotlinSources())
        filePaths("javaSources", javaSources())
        filePaths("classpath", classpath)
        property("level", level)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("classes", outClasses) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outClasses)
        val kt = kotlinSources()
        if (kt.isEmpty()) return TaskResult.Success
        val r = compile.compile(kt, javaSources(), classpath, outClasses, level)
        r.messages.forEach(ctx.logger())
        return if (r.success) TaskResult.Success
        else TaskResult.Failed(r.messages.joinToString("\n").ifBlank { "kotlin compilation failed" })
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
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        dirPaths("project", projectClasses)
        filePaths("sub", subProjectJars)
        filePaths("ext", externalJars)
        property("minApi", minApi)
        property("release", release)
        property("androidJar", androidJar.toString())
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply {
        dirPath("projectDex", projectDexRoot)
        dirPath("subDex", subDexRoot)
        dirPath("extDex", extDexRoot)
    }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        var ok = archiveProject(ctx)
        // Libraries are atomic jars → per-jar content-hash buckets (a changed lib re-dexes alone).
        ok = dexJars(ctx, subDexRoot, subProjectJars) && ok
        ok = dexJars(ctx, extDexRoot, externalJars) && ok
        return if (ok) TaskResult.Success else TaskResult.Failed("dexBuilder failed")
    }

    /**
     * Project scope, per class file: archive only the `.class` files whose content changed (tracked in a
     * `.classmanifest`), so editing one class re-dexes just it. Changed classes are the D8 *program*; the
     * unchanged ones + library jars are the desugaring *classpath* so a changed class still sees its siblings.
     * D8's `DexFilePerClassFile` writes `<class>.dex` straight into [projectDexRoot]; the merge resolves them.
     */
    private fun archiveProject(ctx: TaskContext): Boolean {
        Files.createDirectories(projectDexRoot)
        // Collect across every project-class root (Java output + Kotlin output) keyed by package-relative
        // path; a later root wins a (rare) name clash. Relpaths from distinct roots don't otherwise collide.
        val byRel = LinkedHashMap<String, Path>()
        for (root in projectClasses.filter { Files.isDirectory(it) }) {
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.forEach { f ->
                    byRel[root.relativize(f).toString().replace('\\', '/')] = f
                }
            }
        }
        if (byRel.isEmpty()) { DexArchives.clearClassDex(projectDexRoot); return true }
        val current = byRel.mapValues { (_, f) -> DexArchives.fileHash(f) }   // relpath -> content hash
        val previous = DexArchives.readClassManifest(projectDexRoot)
        val changed = current.keys.filter { previous[it] != current[it] }      // new or modified
        val removed = previous.keys - current.keys
        (changed + removed).forEach { DexArchives.deleteClassDex(projectDexRoot, it) }

        var ok = true
        if (changed.isNotEmpty()) {
            val changedJar = stagingJar.resolveSibling("project-changed.jar")
            val restJar = stagingJar.resolveSibling("project-rest.jar")
            DexArchives.jarClasses(byRel, changed.toSet(), changedJar)
            val unchanged = current.keys - changed.toSet()
            val classpath = ArrayList<Path>()
            if (unchanged.isNotEmpty()) { DexArchives.jarClasses(byRel, unchanged, restJar); classpath.add(restJar) }
            classpath.addAll(subProjectJars.filter { Files.exists(it) })
            classpath.addAll(externalJars.filter { Files.exists(it) })
            val r = dexer.dexArchive(listOf(changedJar), classpath, androidJar, minApi, release, projectDexRoot)
            r.log.forEach(ctx.logger()); if (!r.success) { ok = false; ctx.logger()("dex archive failed for project classes") }
        }
        DexArchives.writeClassManifest(projectDexRoot, current)
        return ok
    }

    /** Archive each jar into `<root>/<contentHash>/`, reusing unchanged buckets and pruning stale ones. */
    private fun dexJars(ctx: TaskContext, root: Path, jars: List<Path>): Boolean {
        Files.createDirectories(root)
        val keep = HashSet<String>()
        var ok = true
        for (jar in jars.filter { Files.exists(it) && Files.size(it) > 0L }) {
            val h = DexArchives.contentHash(jar); keep.add(h)
            val bucket = root.resolve(h)
            if (DexArchives.hasDex(bucket)) continue            // unchanged input → reuse its archive
            DexArchives.clearDir(bucket); Files.createDirectories(bucket)
            val r = dexer.dexArchive(listOf(jar), emptyList(), androidJar, minApi, release, bucket)
            r.log.forEach(ctx.logger()); if (!r.success) { ok = false; ctx.logger()("dex archive failed for ${jar.fileName}") }
        }
        DexArchives.prune(root, keep)
        return ok
    }
}

/** Content-addressing + bucket bookkeeping for [DexArchiveBuilderTask]'s per-scope dex archives. */
private object DexArchives {
    /**
     * Content hash of [path] — a dir's sorted (relpath + bytes), a jar/zip's sorted (entryName + bytes),
     * else the raw file bytes. Hashing *content* (not the file/jar bytes, which carry timestamps) keeps an
     * unchanged input's bucket name stable across rebuilds, so it is reused rather than re-dexed.
     */
    fun contentHash(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        when {
            Files.isDirectory(path) -> {
                val files = Files.walk(path).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
                for (f in files) {
                    md.update(path.relativize(f).toString().replace('\\', '/').toByteArray(Charsets.UTF_8))
                    md.update(runCatching { Files.readAllBytes(f) }.getOrDefault(ByteArray(0)))
                }
            }
            isZip(path) -> ZipFile(path.toFile()).use { zf ->
                zf.entries().toList().filterNot { it.isDirectory }.sortedBy { it.name }.forEach { e ->
                    md.update(e.name.toByteArray(Charsets.UTF_8))
                    zf.getInputStream(e).use { md.update(it.readBytes()) }
                }
            }
            Files.isRegularFile(path) -> md.update(Files.readAllBytes(path))
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 24)
    }

    /** Content hash of a single file's bytes (`.class` files are deterministic — no timestamps). */
    fun fileHash(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(runCatching { Files.readAllBytes(file) }.getOrDefault(ByteArray(0)))
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 24)
    }

    fun hasDex(dir: Path): Boolean = Files.isDirectory(dir) &&
        Files.walk(dir).use { s -> s.anyMatch { it.toString().endsWith(".dex") } }

    private const val CLASS_MANIFEST = ".classmanifest"

    /** Read the per-class `relpath -> contentHash` manifest written by [writeClassManifest]. */
    fun readClassManifest(root: Path): Map<String, String> {
        val f = root.resolve(CLASS_MANIFEST)
        if (!Files.isRegularFile(f)) return emptyMap()
        return runCatching {
            Files.readAllLines(f).mapNotNull { line ->
                val i = line.indexOf('\t'); if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Write the per-class manifest (sorted, so the bytes are stable when the class set is unchanged). */
    fun writeClassManifest(root: Path, map: Map<String, String>) {
        Files.createDirectories(root)
        runCatching { Files.write(root.resolve(CLASS_MANIFEST), map.entries.sortedBy { it.key }.map { "${it.key}\t${it.value}" }) }
    }

    /** Delete the per-class `.dex` for a `com/example/Foo.class` relpath (DexFilePerClassFile names it `Foo.dex`). */
    fun deleteClassDex(root: Path, classRelPath: String) {
        runCatching { Files.deleteIfExists(root.resolve(classRelPath.removeSuffix(".class") + ".dex")) }
    }

    /** Drop every per-class `.dex` + the manifest (the project has no classes anymore). */
    fun clearClassDex(root: Path) {
        if (!Files.isDirectory(root)) return
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && (it.toString().endsWith(".dex") || it.fileName.toString() == CLASS_MANIFEST) }.toList()
        }.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** Jar the [keys] subset of [byRel] (relpath -> class file) into [jar], preserving package paths. */
    fun jarClasses(byRel: Map<String, Path>, keys: Set<String>, jar: Path) {
        jar.parent?.let { Files.createDirectories(it) }
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            keys.sorted().forEach { rel ->
                val f = byRel[rel] ?: return@forEach
                jos.putNextEntry(JarEntry(rel)); Files.copy(f, jos); jos.closeEntry()
            }
        }
    }

    /** Delete immediate child buckets of [root] whose name is not in [keep] (removed/changed inputs). */
    fun prune(root: Path, keep: Set<String>) {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { s -> s.filter { Files.isDirectory(it) && it.fileName.toString() !in keep }.toList() }
            .forEach { clearDir(it) }
    }

    fun clearDir(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    private fun isZip(p: Path): Boolean = Files.isRegularFile(p) &&
        p.toString().let { it.endsWith(".jar", true) || it.endsWith(".zip", true) || it.endsWith(".aar", true) }
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
    private fun dexFiles(roots: List<Path>): List<Path> = roots.filter { Files.isDirectory(it) }.flatMap { dir ->
        Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".dex") }.sorted().toList() }
    }

    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        dirPaths("archives", dexArchives)
        property("minApi", minApi)
        property("release", release)
        property("perBucket", groupPerBucket)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("dex", outDexDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        DexArchives.clearDir(outDexDir); Files.createDirectories(outDexDir)
        if (groupPerBucket) {
            // One indexed group per archive bucket (each input library stays its own set of dex files).
            val buckets = dexArchives.filter { Files.isDirectory(it) }
                .flatMap { Files.list(it).use { s -> s.filter { c -> Files.isDirectory(c) }.sorted().toList() } }
            var i = 0
            for (b in buckets) {
                val dexes = dexFiles(listOf(b))
                if (dexes.isEmpty()) continue
                val group = outDexDir.resolve("g${i++}"); Files.createDirectories(group)
                val r = dexer.dex(dexes, androidJar, minApi, release, group)
                r.log.forEach(ctx.logger())
                if (!r.success) return TaskResult.Failed("dex merge failed")
            }
            return TaskResult.Success
        }
        val dexes = dexFiles(dexArchives)
        // An empty layer is valid (e.g. mergeLibDex with no sub-module deps) — produce an empty dex dir.
        if (dexes.isEmpty()) return TaskResult.Success
        val r = dexer.dex(dexes, androidJar, minApi, release, outDexDir)
        r.log.forEach(ctx.logger())
        return if (r.success) TaskResult.Success else TaskResult.Failed("dex merge failed")
    }
}

/**
 * `minify<Variant>WithR8`: the release dexing path. R8 shrinks/optimizes/obfuscates and dexes every
 * input in a single step (replacing dexBuilder→mergeProjectDex/mergeLibDex→mergeDex), guided by
 * [keepRulesFile] (aapt2's manifest-derived rules when present). Class directories are jarred into
 * [stagingDir] first (R8 reads jars/class files, not raw dirs). The [Shrinker] is the injected R8 port.
 */
internal class R8MinifyTask(
    override val name: TaskName,
    private val programs: List<Path>,
    private val androidJar: Path,
    private val minApi: Int,
    private val keepRulesFile: Path,
    private val stagingDir: Path,
    private val outDexDir: Path,
    private val shrinker: Shrinker,
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        dirPaths("classes", programs.filter { Files.isDirectory(it) })
        filePaths("jars", programs.filter { !Files.isDirectory(it) })
        if (Files.exists(keepRulesFile)) filePaths("keep", listOf(keepRulesFile))
        property("minApi", minApi)
        property("androidJar", androidJar.toString())
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("dex", outDexDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(stagingDir)
        Files.createDirectories(outDexDir)
        val inputs = programs.filter { Files.exists(it) }.mapIndexed { i, p ->
            if (Files.isDirectory(p)) stagingDir.resolve("in$i.jar").also { ApkPackaging.jarClasses(p, it) } else p
        }
        if (inputs.isEmpty()) return TaskResult.Failed("nothing to minify")
        val keepRules = if (Files.exists(keepRulesFile)) Files.readAllLines(keepRulesFile) else emptyList()
        val r = shrinker.shrink(inputs, androidJar, keepRules, minApi, release = true, outDexDir)
        r.log.forEach(ctx.logger())
        return if (r.success) TaskResult.Success else TaskResult.Failed("R8 minify failed")
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
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        filePaths("ap", listOf(resourcesAp))
        dirPaths("dex", dexDirs)
        dirPaths("assets", assetsDirs)
        dirPaths("jni", jniLibDirs)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("apk", outApk) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            val names = ApkPackaging.assembleApk(resourcesAp, dexDirs, assetsDirs, jniLibDirs, outApk)
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
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        filePaths("unsigned", listOf(unsignedApk))
        property("keystore", config.keystore.toString())
        property("alias", config.keyAlias)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("apk", signedApk) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val r = signer.sign(unsignedApk, signedApk, config)
        r.log.forEach(ctx.logger())
        return if (r.success) TaskResult.Success else TaskResult.Failed("apk signing failed")
    }
}


package dev.ide.lang.kotlin.compile

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * Incremental Kotlin compilation below the task level. The build engine already skips `compileKotlin` whole
 * when nothing changed; this layer makes the case where it does run cheap, by recompiling only the `.kt`
 * files whose content changed instead of the whole module: the Kotlin counterpart to the per-class
 * incremental dex archive (`.classmanifest`).
 *
 * It is ABI-aware and conservative. State for a module lives in a sidecar next to its output dir: each
 * source's content hash, the source→`.class` mapping, and a per-class ABI snapshot ([KotlinAbi]).
 *
 *  - Fast path: only method bodies of the changed files changed (every recompiled class's ABI is unchanged,
 *    no class was removed): recompile just those files against the unchanged classes as a binary classpath,
 *    drop the new `.class` into the output dir, keep everything else. The common edit.
 *  - Full recompile: anything that could affect a dependent: a public signature/declaration change (ABI
 *    snapshot differs), a class removed, a source deleted, or the compile context (Java interop sources,
 *    classpath, jvmTarget) changed. Conservative because no file→file dependency graph is kept; when in
 *    doubt, rebuild the module (always correct).
 *
 * Pure JVM + ASM + `kotlin-metadata-jvm`; no compiler IC caches (which mmap on ART). The state is plain
 * text keyed off the output dir, so it survives an IDE restart and needs no in-memory continuity.
 */
class IncrementalKotlinCompiler(private val compiler: KotlinJvmCompiler = KotlinJvmCompiler()) {

    /** Which path the last compile took — surfaced for tests/diagnostics (the build engine ignores it). */
    enum class Mode { FULL, INCREMENTAL, NOOP }

    data class Result(
        val success: Boolean,
        val messages: List<String>,
        val mode: Mode,
        /** The `.kt` files actually recompiled (all of them on [Mode.FULL]). */
        val recompiledSources: List<Path> = emptyList(),
    )

    fun compile(
        kotlinSources: List<Path>,
        javaSources: List<Path>,
        classpath: List<Path>,
        outputDir: Path,
        jvmTarget: String = "17",
        bootClasspath: List<Path> = emptyList(),
        compilerPlugins: List<Path> = emptyList(),
        pluginOptions: List<String> = emptyList(),
    ): Result {
        val kt = kotlinSources.map { it.toAbsolutePath().normalize() }.filter { Files.isRegularFile(it) }
        if (kt.isEmpty()) {                              // no Kotlin left → nothing to emit; clear stale state
            clearDir(outputDir); state(outputDir).delete()
            return Result(true, emptyList(), Mode.NOOP)
        }

        val context = contextHash(javaSources, classpath, bootClasspath, jvmTarget, compilerPlugins, pluginOptions)
        val srcHash = kt.associateWith { fileHash(it) }
        val prev = state(outputDir).read()

        // No usable baseline, the interop/classpath context moved, or a source was deleted → full rebuild.
        val removed = prev != null && (prev.srcHash.keys - srcHash.keys).isNotEmpty()
        if (prev == null || prev.context != context || removed) {
            return full(kt, javaSources, classpath, outputDir, jvmTarget, bootClasspath, context, srcHash, compilerPlugins, pluginOptions)
        }

        val dirty = kt.filter { prev.srcHash[it] != srcHash[it] }
        if (dirty.isEmpty()) {
            // No source changed, so the task re-ran for an unrelated reason. Skip only if the output dir still
            // holds exactly what was last produced; if a `.class` was deleted/tampered, rebuild to restore it.
            if (currentClasses(outputDir).toSet() == prev.abi.keys) return Result(true, emptyList(), Mode.NOOP)
            return full(kt, javaSources, classpath, outputDir, jvmTarget, bootClasspath, context, srcHash, compilerPlugins, pluginOptions)
        }

        return incremental(kt, dirty, javaSources, classpath, outputDir, jvmTarget, bootClasspath, context, srcHash, prev, compilerPlugins, pluginOptions)
            ?: full(kt, javaSources, classpath, outputDir, jvmTarget, bootClasspath, context, srcHash, compilerPlugins, pluginOptions)
    }

    /** Whole-module compile into a clean output dir; records a fresh manifest. */
    private fun full(
        kt: List<Path>, javaSources: List<Path>, classpath: List<Path>, outputDir: Path,
        jvmTarget: String, bootClasspath: List<Path>, context: String, srcHash: Map<Path, String>,
        compilerPlugins: List<Path>, pluginOptions: List<String>,
    ): Result {
        clearDir(outputDir)
        Files.createDirectories(outputDir)
        val r = compiler.compile(kt, javaSources, classpath, outputDir, jvmTarget, bootClasspath,
            compilerPlugins = compilerPlugins, pluginOptions = pluginOptions)
        if (!r.success) return Result(false, r.messages, Mode.FULL, kt)
        val srcToOut = relativizeMapping(r.outputs, outputDir, srcHash.keys)
        val abi = snapshotAll(outputDir)
        state(outputDir).write(State(context, srcHash, srcToOut, abi))
        return Result(true, r.messages, Mode.FULL, kt)
    }

    /**
     * The fast path. Compile [dirty] alone, with the unchanged classes copied out as a read-only binary
     * classpath (so no FQN is seen as both source and binary), into a staging dir, then decide:
     *  - any recompiled class's ABI changed, or a class the dirty set used to own disappeared → return null
     *    (the caller falls back to [full], which is always correct);
     *  - otherwise commit the staged `.class` into the output dir and update the manifest in place.
     * Multi-file facade parts (a class co-owned by a dirty and a clean source) also fall back, since
     * excluding it from the clean classpath would drop the clean part.
     */
    private fun incremental(
        kt: List<Path>, dirty: List<Path>, javaSources: List<Path>, classpath: List<Path>, outputDir: Path,
        jvmTarget: String, bootClasspath: List<Path>, context: String, srcHash: Map<Path, String>, prev: State,
        compilerPlugins: List<Path>, pluginOptions: List<String>,
    ): Result? {
        val dirtyOwned = dirty.flatMap { prev.srcToOut[it].orEmpty() }.toSet()           // their prior outputs
        val cleanOwned = (kt - dirty.toSet()).flatMap { prev.srcToOut[it].orEmpty() }.toSet()
        if (dirtyOwned.any { it in cleanOwned }) return null                              // shared facade → full

        val stateDir = state(outputDir).dir
        val cleanDir = stateDir.resolve("clean")
        val stagingDir = stateDir.resolve("staging")
        clearDir(cleanDir); clearDir(stagingDir)
        // Materialize cleanDir even when no clean classes get copied below (a single-source module, or every
        // class owned by a dirty source): it goes on the compile classpath and into -Xfriend-paths, and kotlinc
        // emits "Classpath entry points to a non-existent location" for a missing dir. An empty dir is silent.
        Files.createDirectories(cleanDir)
        // Materialize the binary view of the unchanged module half: every output class NOT owned by a dirty
        // source. Hardlink rather than copy — the compiler only READS these, and clearDir later drops the link
        // (the original under outputDir, a second link to the same inode, is untouched), so on a large module a
        // one-file edit no longer copies the bytes of every other class. Falls back to a real copy on a
        // filesystem that can't hardlink (cross-device, or one without link support); cleanDir is a sibling of
        // outputDir, so they are normally on the same FS and the link succeeds.
        for (rel in currentClasses(outputDir)) {
            if (rel in dirtyOwned) continue
            val dest = cleanDir.resolve(rel); Files.createDirectories(dest.parent)
            val from = outputDir.resolve(rel)
            runCatching { Files.createLink(dest, from) }.getOrElse { Files.copy(from, dest) }
        }
        Files.createDirectories(stagingDir)

        // friendPaths=[cleanDir] keeps same-module `internal` access working across the source/binary split.
        val r = compiler.compile(
            dirty, javaSources, listOf(cleanDir) + classpath, stagingDir, jvmTarget, bootClasspath,
            friendPaths = listOf(cleanDir),
            compilerPlugins = compilerPlugins, pluginOptions = pluginOptions,
        )
        if (!r.success) { clearDir(cleanDir); clearDir(stagingDir); return Result(false, r.messages, Mode.INCREMENTAL, dirty) }

        val newMapping = relativizeMapping(r.outputs, stagingDir, dirty.toSet())
        val produced = currentClasses(stagingDir)

        // A class a dirty source used to own but no longer produces is a structural (removal) change.
        val stillOwned = newMapping.values.flatten().toSet()
        if (dirtyOwned.any { it !in stillOwned }) { clearDir(cleanDir); clearDir(stagingDir); return null }

        // An ABI change on any pre-existing class can affect dependents → full. A brand-new class cannot
        // (nothing referenced it yet), so it does not trip the fast path.
        val newAbi = HashMap<String, String>()
        for (rel in produced) {
            val abi = KotlinAbi.snapshot(Files.readAllBytes(stagingDir.resolve(rel)))
            newAbi[rel] = abi
            val before = prev.abi[rel]
            if (before != null && before != abi) { clearDir(cleanDir); clearDir(stagingDir); return null }
        }

        // Commit: stage → output, then refresh the manifest entries for the dirty sources only.
        for (rel in produced) {
            val dest = outputDir.resolve(rel); Files.createDirectories(dest.parent)
            Files.copy(stagingDir.resolve(rel), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val srcToOut = HashMap(prev.srcToOut).apply { dirty.forEach { put(it, newMapping[it].orEmpty()) } }
        val abi = HashMap(prev.abi).apply { putAll(newAbi) }
        state(outputDir).write(State(context, HashMap(prev.srcHash).apply { putAll(srcHash) }, srcToOut, abi))
        clearDir(cleanDir); clearDir(stagingDir)
        return Result(true, r.messages, Mode.INCREMENTAL, dirty)
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** Snapshot every `.class` under [dir], keyed by output-relative path. */
    private fun snapshotAll(dir: Path): Map<String, String> =
        currentClasses(dir).associateWith { KotlinAbi.snapshot(Files.readAllBytes(dir.resolve(it))) }

    /** The output-relative paths of every `.class` under [dir] (empty when [dir] is absent). */
    private fun currentClasses(dir: Path): List<String> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .map { dir.relativize(it).toString().replace('\\', '/') }.collect(Collectors.toList())
        }
    }

    /** Reduce the compiler's `source → absolute .class` mapping to `source → output-relative paths`, kept to
     *  the [keep] sources (the ones whose state is being recorded this pass). */
    private fun relativizeMapping(outputs: Map<Path, List<Path>>, baseDir: Path, keep: Set<Path>): Map<Path, List<String>> {
        val base = baseDir.toAbsolutePath().normalize()
        val out = HashMap<Path, List<String>>()
        for ((src, classes) in outputs) {
            val key = src.toAbsolutePath().normalize()
            if (key !in keep) continue
            out[key] = classes.mapNotNull { c ->
                val abs = c.toAbsolutePath().normalize()
                if (abs.startsWith(base)) base.relativize(abs).toString().replace('\\', '/') else null
            }
        }
        return out
    }

    /** A digest of everything-but-the-Kotlin-sources that changes what the Kotlin sources compile to. */
    private fun contextHash(
        javaSources: List<Path>, classpath: List<Path>, boot: List<Path>, jvmTarget: String,
        compilerPlugins: List<Path>, pluginOptions: List<String>,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(jvmTarget.toByteArray(Charsets.UTF_8))
        // Compiler plugins change the emitted bytecode (e.g. Compose's synthetic params), so applying/
        // changing one must invalidate a baseline produced without it.
        compilerPlugins.map { it.toAbsolutePath().normalize().toString() }.sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)) }
        pluginOptions.sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)) }
        boot.map { it.toAbsolutePath().normalize().toString() }.sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)) }
        // Java interop sources AND classpath entries (dep outputs, libs) both get a cheap path+size+mtime
        // signature rather than a content hash. The build engine already re-runs this task only when an input
        // truly changed (a content-based fingerprint at the task level), so this is the secondary guard — and a
        // .java save always bumps its mtime, so a real interop change still invalidates the baseline. This
        // avoids reading every .java byte (mixed modules) and halves the stat syscalls per jar (one
        // readAttributes vs. exists+size+mtime), both of which ran on every single compile.
        javaSources.map { it.toAbsolutePath().normalize() }.filter { Files.isRegularFile(it) }.sortedBy { it.toString() }
            .forEach { statSignature(md, it) }
        classpath.map { it.toAbsolutePath().normalize() }.sortedBy { it.toString() }.forEach { statSignature(md, it) }
        return hex(md)
    }

    /** Feed a path + size + last-modified signature for [p] into [md] using a single stat (readAttributes). */
    private fun statSignature(md: MessageDigest, p: Path) {
        md.update(p.toString().toByteArray(Charsets.UTF_8))
        val attrs = runCatching {
            Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
        }.getOrNull() ?: return
        md.update(attrs.size().toString().toByteArray(Charsets.UTF_8))
        md.update(attrs.lastModifiedTime().toMillis().toString().toByteArray(Charsets.UTF_8))
    }

    private fun fileHash(p: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(runCatching { Files.readAllBytes(p) }.getOrDefault(ByteArray(0)))
        return hex(md)
    }

    private fun hex(md: MessageDigest): String =
        md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 32)

    private fun clearDir(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
    }

    private fun state(outputDir: Path) = ManifestStore(outputDir.resolveSibling("${outputDir.fileName}.ic"))

    /** Persisted per-module incremental state (content hashes + source→class mapping + per-class ABI). */
    private data class State(
        val context: String,
        val srcHash: Map<Path, String>,
        val srcToOut: Map<Path, List<String>>,
        val abi: Map<String, String>,
    )

    /** A plain-text manifest under [dir]; keyed off the output dir so it survives restarts. */
    private class ManifestStore(val dir: Path) {
        private val file get() = dir.resolve("manifest.txt")

        fun delete() { runCatching { Files.deleteIfExists(file) } }

        fun read(): State? {
            if (!Files.isRegularFile(file)) return null
            return runCatching {
                var context = ""
                val srcHash = HashMap<Path, String>()
                val srcToOut = HashMap<Path, List<String>>()
                val abi = HashMap<String, String>()
                for (line in Files.readAllLines(file)) {
                    val p = line.split('\t')
                    when (p.getOrNull(0)) {
                        "ctx" -> context = p.getOrElse(1) { "" }
                        "s" -> srcHash[Paths.get(p[1])] = p[2]
                        "o" -> srcToOut[Paths.get(p[1])] = if (p.size > 2 && p[2].isNotEmpty()) p[2].split(',') else emptyList()
                        "a" -> abi[p[1]] = p[2]
                    }
                }
                if (context.isEmpty()) null else State(context, srcHash, srcToOut, abi)
            }.getOrNull()
        }

        fun write(s: State) {
            runCatching {
                Files.createDirectories(dir)
                val lines = ArrayList<String>()
                lines += "ctx\t${s.context}"
                s.srcHash.forEach { (k, v) -> lines += "s\t$k\t$v" }
                s.srcToOut.forEach { (k, v) -> lines += "o\t$k\t${v.joinToString(",")}" }
                s.abi.toSortedMap().forEach { (k, v) -> lines += "a\t$k\t$v" }
                Files.write(file, lines)
            }
        }
    }
}

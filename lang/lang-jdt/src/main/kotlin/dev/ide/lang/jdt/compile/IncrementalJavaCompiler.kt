package dev.ide.lang.jdt.compile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/**
 * Incremental Java compilation for a module's `compileJava` task. The build engine runs the task only when an
 * input changed; this makes that run proportional to the edit rather than to the module, by recompiling only
 * the `.java` files whose content changed when nothing outside them can be affected.
 *
 * State for a module lives in a sidecar next to its output dir ([outputDir]`.javainc`): each source's content
 * hash, the classes it produced, and each class's API snapshot ([JavaClassAbi]).
 *
 *  - Fast path: the changed sources are compiled alone, against the module's existing classes as a binary
 *    classpath, into a staging dir. If every recompiled class kept its API, no class a clean source could
 *    name disappeared, and no new class takes a simple name a clean source uses, the staged classes replace
 *    the old ones. Editing method bodies, private members or local classes stays on this path.
 *  - Full compile: no usable state, a source removed, the classpath or language level changed, the output dir
 *    no longer matching the state, or the fast path finding an API change. The output dir is cleared and
 *    every source compiled, which is always correct.
 *
 * A compile error on the fast path is reported as the build's failure and leaves the output and state as they
 * were, so the next build retries the same changed sources.
 */
class IncrementalJavaCompiler {

    /** Which path a compile took; reported for tests and the build log. */
    enum class Mode { FULL, INCREMENTAL, NOOP }

    data class Result(
        val success: Boolean,
        val messages: List<String>,
        val diagnostics: List<JdtBatchCompiler.Diagnostic>,
        val mode: Mode,
        /** The sources actually compiled (all of them on [Mode.FULL]). */
        val recompiledSources: List<Path> = emptyList(),
    )

    fun compile(
        sources: List<Path>,
        classpath: List<Path>,
        outputDir: Path,
        sourceLevel: String,
        bootClasspath: List<Path> = emptyList(),
    ): Result {
        val src = sources.map { it.toAbsolutePath().normalize() }.filter { Files.isRegularFile(it) }.distinct()
        val store = Store(outputDir.resolveSibling("${outputDir.fileName}.javainc"))
        if (src.isEmpty()) {
            clearDir(outputDir)
            Files.createDirectories(outputDir)
            store.delete()
            return Result(true, emptyList(), emptyList(), Mode.NOOP)
        }

        val prev = store.read()
        val sigs = sourceSigs(src, prev?.sources.orEmpty())
        val context = contextHash(classpath, bootClasspath, sourceLevel)
        val job = Job(src, classpath, outputDir, sourceLevel, bootClasspath, context, sigs, store)

        if (prev == null || prev.context != context || (prev.sources.keys - sigs.keys).isNotEmpty() ||
            currentClasses(outputDir) != prev.abi.keys
        ) return full(job)

        val dirty = src.filter { prev.sources[it]?.hash != sigs.getValue(it).hash }
        if (dirty.isEmpty()) {
            if (sigs != prev.sources) store.write(prev.copy(sources = sigs))
            return Result(true, emptyList(), emptyList(), Mode.NOOP)
        }
        return incremental(job, dirty, prev) ?: full(job)
    }

    /** One compile's inputs, passed between the paths. */
    private class Job(
        val sources: List<Path>,
        val classpath: List<Path>,
        val outputDir: Path,
        val level: String,
        val boot: List<Path>,
        val context: String,
        val sigs: Map<Path, SourceSig>,
        val store: Store,
    )

    /** Every source into a cleared output dir; records fresh state on success and drops it on failure. */
    private fun full(job: Job): Result {
        clearDir(job.outputDir)
        Files.createDirectories(job.outputDir)
        val r = JdtBatchCompiler.compile(job.sources, job.classpath, job.outputDir, job.level, job.boot)
        if (!r.success) {
            // The output now holds a partial class set; without state the next build is a full one again.
            job.store.delete()
            return Result(false, r.messages, r.diagnostics, Mode.FULL, job.sources)
        }
        val abi = currentClasses(job.outputDir).associateWith { rel -> classAbi(job.outputDir.resolve(rel)) }
        val outputs = job.sources.associateWith { r.outputs[JdtBatchCompiler.sourceKey(it)].orEmpty() }
        job.store.write(State(job.context, job.sigs, outputs, abi))
        return Result(true, r.messages, r.diagnostics, Mode.FULL, job.sources)
    }

    /**
     * Compile [dirty] alone into a staging dir and commit it, or return null when the change can reach a clean
     * source (the caller then compiles everything).
     */
    private fun incremental(job: Job, dirty: List<Path>, prev: State): Result? {
        val dirtySet = dirty.toSet()
        val dirtyOwned = dirty.flatMapTo(HashSet()) { prev.outputs[it].orEmpty() }
        val clean = job.sources.filter { it !in dirtySet }
        val cleanOwned = clean.flatMapTo(HashSet()) { prev.outputs[it].orEmpty() }

        val staging = job.store.dir.resolve("staging")
        clearDir(staging)
        Files.createDirectories(staging)
        try {
            // The module's own output first, so the clean sources resolve as the classes they compiled to. A
            // changed source is still compiled from source: the compiler binds source types before it asks the
            // classpath, so the stale copy of a dirty class is never read.
            val r = JdtBatchCompiler.compile(dirty, listOf(job.outputDir) + job.classpath, staging, job.level, job.boot)
            if (!r.success) return Result(false, r.messages, r.diagnostics, Mode.INCREMENTAL, dirty)

            val produced = currentClasses(staging)
            val newAbi = HashMap<String, ClassAbi>()
            val newNames = HashSet<String>()
            for (rel in produced) {
                // A class a clean source also produces: two sources now declare it.
                if (rel in cleanOwned) return null
                val abi = classAbi(staging.resolve(rel))
                newAbi[rel] = abi
                val before = prev.abi[rel]
                when {
                    before == null -> if (!abi.isLocal) newNames += abi.simpleName
                    abi.isLocal && before.isLocal -> Unit
                    abi.abi != before.abi || abi.isLocal != before.isLocal -> return null
                }
            }
            // A class a clean source could name is gone.
            if (dirtyOwned.any { it !in newAbi && prev.abi[it]?.isLocal != true }) return null
            // A new class may now be what a clean source's simple name resolves to (a same-package class wins
            // over an on-demand import, a member class over an inherited one).
            if (newNames.isNotEmpty() && clean.any { mentionsAny(it, newNames) }) return null

            for (rel in dirtyOwned) if (rel !in newAbi) Files.deleteIfExists(job.outputDir.resolve(rel))
            for (rel in produced) {
                val dest = job.outputDir.resolve(rel)
                Files.createDirectories(dest.parent)
                Files.copy(staging.resolve(rel), dest, StandardCopyOption.REPLACE_EXISTING)
            }
            val outputs = HashMap(prev.outputs).apply {
                keys.retainAll(job.sigs.keys)
                dirty.forEach { put(it, r.outputs[JdtBatchCompiler.sourceKey(it)].orEmpty()) }
            }
            val abi = HashMap(prev.abi).apply {
                keys.removeAll(dirtyOwned)
                putAll(newAbi)
            }
            job.store.write(State(job.context, job.sigs, outputs, abi))
            return Result(true, r.messages, r.diagnostics, Mode.INCREMENTAL, dirty)
        } finally {
            clearDir(staging)
        }
    }

    // ---- inputs --------------------------------------------------------------------------------------------

    /**
     * Each source's content hash, reusing the recorded one while the file's size and modification time are
     * unchanged and it was already stable when hashed (see [RACY_WINDOW_MS]).
     */
    private fun sourceSigs(sources: List<Path>, previous: Map<Path, SourceSig>): Map<Path, SourceSig> {
        val out = LinkedHashMap<Path, SourceSig>()
        for (p in sources) {
            val attrs = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java) }.getOrNull() ?: continue
            val size = attrs.size()
            val mtime = attrs.lastModifiedTime().toMillis()
            val known = previous[p]
            out[p] = if (known != null && known.size == size && known.mtime == mtime && mtime + RACY_WINDOW_MS <= known.hashedAt) {
                known
            } else {
                val hashedAt = System.currentTimeMillis()
                SourceSig(size, mtime, hashedAt, contentHash(p))
            }
        }
        return out
    }

    /**
     * Everything other than the sources that decides what they compile to. A directory on the classpath (an
     * upstream module's output, this module's Kotlin output) counts by the API of its classes, so a body-only
     * change there does not force this module to recompile; a jar counts by path, size and modification time.
     */
    private fun contextHash(classpath: List<Path>, boot: List<Path>, level: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(s: String) { md.update(s.toByteArray(Charsets.UTF_8)); md.update(0) }
        put("v$FORMAT")
        put(level)
        for (p in boot) { put("B"); putStat(p, ::put) }
        for (p in classpath.map { it.toAbsolutePath().normalize() }) {
            if (Files.isDirectory(p)) {
                put("D"); put(p.toString())
                for (rel in currentClasses(p).sorted()) {
                    val abi = DirAbiCache.abiOf(p.resolve(rel)) ?: continue
                    if (abi.isLocal) continue
                    put(rel); put(abi.abi)
                }
            } else {
                put("J"); putStat(p, ::put)
            }
        }
        return hex(md)
    }

    private fun putStat(p: Path, put: (String) -> Unit) {
        put(p.toAbsolutePath().normalize().toString())
        val attrs = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java) }.getOrNull() ?: return put("-")
        put(attrs.size().toString())
        put(attrs.lastModifiedTime().toMillis().toString())
    }

    /** True when [source]'s text uses any of [names] as a word. */
    private fun mentionsAny(source: Path, names: Set<String>): Boolean {
        val text = runCatching { String(Files.readAllBytes(source), Charsets.UTF_8) }.getOrNull() ?: return true
        val pattern = Regex(names.joinToString("|", "(?<![\\w$])(", ")(?![\\w$])") { Regex.escape(it) })
        return pattern.containsMatchIn(text)
    }

    // ---- class files ---------------------------------------------------------------------------------------

    /** A class file's API snapshot, as recorded in the state. */
    private data class ClassAbi(val abi: String, val isLocal: Boolean, val simpleName: String)

    private fun classAbi(file: Path): ClassAbi {
        val snap = runCatching { JavaClassAbi.read(Files.readAllBytes(file)) }.getOrNull()
        // An unreadable class compares unequal to anything, so a change to it always takes the full path.
        return if (snap == null) ClassAbi("?" + System.nanoTime(), false, "")
        else ClassAbi(snap.abi, snap.isLocal, snap.simpleName)
    }

    /**
     * API snapshots of classes in classpath directories, kept for the life of the process and reused while a
     * file's size, modification time and file key are unchanged and it was stable when read.
     */
    private object DirAbiCache {
        private class Entry(val size: Long, val mtime: Long, val fileKey: Any?, val readAt: Long, val abi: ClassAbi?)

        private val entries = ConcurrentHashMap<String, Entry>()

        fun abiOf(file: Path): ClassAbi? {
            val attrs = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull() ?: return null
            val key = file.toString()
            val size = attrs.size()
            val mtime = attrs.lastModifiedTime().toMillis()
            val fileKey = attrs.fileKey()
            entries[key]?.let { e ->
                if (e.size == size && e.mtime == mtime && e.fileKey == fileKey && mtime + RACY_WINDOW_MS <= e.readAt) return e.abi
            }
            val readAt = System.currentTimeMillis()
            val snap = runCatching { JavaClassAbi.read(Files.readAllBytes(file)) }.getOrNull()
            val abi = snap?.let { ClassAbi(it.abi, it.isLocal, it.simpleName) }
            if (entries.size >= MAX_CACHED) entries.clear()
            entries[key] = Entry(size, mtime, fileKey, readAt, abi)
            return abi
        }
    }

    /** The output-relative paths of every `.class` under [dir] (empty when [dir] is absent). */
    private fun currentClasses(dir: Path): Set<String> {
        if (!Files.isDirectory(dir)) return emptySet()
        return Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .map { dir.relativize(it).toString().replace('\\', '/') }
                .collect(Collectors.toSet())
        }
    }

    private fun contentHash(p: Path): String {
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

    // ---- state ---------------------------------------------------------------------------------------------

    /** A source's stat when [hash] (its content hash) was taken at [hashedAt]. */
    private data class SourceSig(val size: Long, val mtime: Long, val hashedAt: Long, val hash: String)

    private data class State(
        val context: String,
        val sources: Map<Path, SourceSig>,
        val outputs: Map<Path, List<String>>,
        val abi: Map<String, ClassAbi>,
    )

    /** The state as a plain-text manifest under [dir], so it survives a restart of the build process. */
    private class Store(val dir: Path) {
        private val file get() = dir.resolve("manifest.txt")

        fun delete() { runCatching { Files.deleteIfExists(file) } }

        fun read(): State? {
            if (!Files.isRegularFile(file)) return null
            return runCatching {
                var context = ""
                var format = -1
                val sources = HashMap<Path, SourceSig>()
                val outputs = HashMap<Path, List<String>>()
                val abi = HashMap<String, ClassAbi>()
                for (line in Files.readAllLines(file)) {
                    val p = line.split('\t')
                    when (p.getOrNull(0)) {
                        "f" -> format = p[1].toInt()
                        "ctx" -> context = p[1]
                        "s" -> sources[Paths.get(p[1])] = SourceSig(p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5])
                        "o" -> outputs[Paths.get(p[1])] = if (p.size > 2 && p[2].isNotEmpty()) p[2].split(',') else emptyList()
                        "a" -> abi[p[1]] = ClassAbi(p[2], p[3] == "L", p.getOrElse(4) { "" })
                    }
                }
                if (format != FORMAT || context.isEmpty()) null else State(context, sources, outputs, abi)
            }.getOrNull()
        }

        fun write(s: State) {
            runCatching {
                Files.createDirectories(dir)
                val lines = ArrayList<String>()
                lines += "f\t$FORMAT"
                lines += "ctx\t${s.context}"
                s.sources.forEach { (k, v) -> lines += "s\t$k\t${v.size}\t${v.mtime}\t${v.hashedAt}\t${v.hash}" }
                s.outputs.forEach { (k, v) -> lines += "o\t$k\t${v.joinToString(",")}" }
                s.abi.toSortedMap().forEach { (k, v) -> lines += "a\t$k\t${v.abi}\t${if (v.isLocal) "L" else "N"}\t${v.simpleName}" }
                Files.write(file, lines)
            }.onFailure { delete() }
        }
    }

    private companion object {
        /** Bump when the manifest or [JavaClassAbi]'s digest changes, so old state is ignored. */
        const val FORMAT = 1

        /** A file modified within this long before it was read may change again without its timestamp moving. */
        const val RACY_WINDOW_MS = 2_000L

        const val MAX_CACHED = 200_000
    }
}

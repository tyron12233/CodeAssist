package dev.ide.lang.kotlin.synthetic

import dev.ide.lang.kotlin.symbols.SourceFile
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.vfs.VirtualFile

/**
 * The Java-visible shapes of a workspace's Kotlin source ("light classes", see [KotlinSyntheticClassProvider]),
 * kept up to date one FILE at a time, so a Java file sees a Kotlin edit without the engine re-reading the
 * whole project.
 *
 * Each Kotlin file keeps the classes it rendered to, the text they came from, and the class-name lookups the
 * rendering depended on. On [classesFor] a file is parsed again only when:
 *  - it has an open editor buffer whose text differs from what it was rendered from (so an UNSAVED Kotlin edit
 *    reaches Java code before it is saved),
 *  - it was [invalidate]d (a save or an outside write), or
 *  - the module's set of Kotlin classes changed in a way one of its lookups would notice (a class it names in
 *    a signature was added or removed elsewhere).
 * No parse tree is kept: an entry holds rendered data only.
 *
 * Results keep their identity when nothing they contain changed. A file whose re-rendered classes equal the
 * previous ones hands back the previous list, and a module none of whose files changed hands back the previous
 * module list, so a consumer can skip all work on a reference check. Rendered classes carry no method bodies, so
 * an edit inside a function body changes nothing a consumer sees.
 *
 * Thread-safe: every call runs under one lock. Inside a call, the parsing and the rendering of several files are
 * spread over a few worker threads ([inParallel]): each is a function of one file's text (and the module's class
 * names, fixed by then), and after a wide edit (a rename across the project) or on the first Java lookup of a
 * session there are hundreds of them, one after another, before Java code can resolve anything.
 */
class KotlinLightClasses {

    private class FileEntry(
        val textHash: Long,
        /** Rendered from an open buffer: once the buffer is gone, disk has to be read again. */
        val fromBuffer: Boolean,
        val declared: Set<String>,
        val lookups: Map<String, Boolean>,
        val classes: List<SyntheticClass>,
    )

    private class ModuleState(val roots: List<String>) {
        /** The module's `.kt` files; null after [rescan] until the next [classesFor] lists them again. */
        var files: List<VirtualFile>? = null
        var classNames: Set<String> = emptySet()
        var parts: List<List<SyntheticClass>> = emptyList()
        var result: List<SyntheticClass> = emptyList()
    }

    private val lock = Any()
    private val entries = HashMap<String, FileEntry>()
    private val modules = HashMap<String, ModuleState>()

    private val parses = java.util.concurrent.atomic.AtomicInteger()

    /** How many files have been parsed so far, for tests that check how much work an edit cost. */
    val parseCount: Int get() = parses.get()

    /**
     * The light classes of the module [moduleKey], whose Kotlin source lives under [roots]. [bufferText] returns
     * the live editor text of an open file (by [VirtualFile.path]), or null to read it from disk.
     */
    fun classesFor(
        moduleKey: String,
        roots: List<VirtualFile>,
        bufferText: (String) -> String?,
    ): List<SyntheticClass> = synchronized(lock) {
        val rootPaths = roots.map { it.path }
        val state = modules[moduleKey]?.takeIf { it.roots == rootPaths }
            ?: ModuleState(rootPaths).also { modules[moduleKey] = it }
        val files = state.files ?: listKotlinFiles(roots).also { listed ->
            state.files?.let { previous ->
                val kept = listed.mapTo(HashSet()) { it.path }
                previous.forEach { if (it.path !in kept) entries.remove(it.path) }
            }
            state.files = listed
        }

        // Bring each file's entry up to date with its current text. A file parsed here keeps its SourceFile only
        // until it is rendered below, once the module's class names are known.
        val parsed = HashMap<String, SourceFile?>()
        val texts = HashMap<String, Pair<String, Boolean>>()
        val toParse = ArrayList<VirtualFile>()
        for (vf in files) {
            val path = vf.path
            val buffer = bufferText(path)
            val old = entries[path]
            if (buffer == null && old != null && !old.fromBuffer) continue // disk entry: valid until invalidated
            val text = buffer ?: readText(vf)
            if (text == null) {
                entries.remove(path)
                continue
            }
            val hash = hash64(text)
            if (old != null && old.textHash == hash) {
                if (old.fromBuffer != (buffer != null)) {
                    entries[path] = FileEntry(hash, buffer != null, old.declared, old.lookups, old.classes)
                }
                continue
            }
            texts[path] = text to (buffer != null)
            toParse += vf
        }
        inParallel(toParse) { vf -> parse(vf, texts.getValue(vf.path).first) }
            .forEachIndexed { i, source -> parsed[toParse[i].path] = source }

        val classNames = HashSet<String>()
        for (vf in files) {
            val path = vf.path
            if (path in parsed) parsed[path]?.classes?.forEach { classNames += it.fqn }
            else entries[path]?.declared?.let(classNames::addAll)
        }
        val namesChanged = classNames != state.classNames
        if (namesChanged) state.classNames = classNames

        // Render what was parsed, and re-render any other file whose class-name lookups now answer differently.
        val reparse = ArrayList<VirtualFile>()
        for (vf in files) {
            val path = vf.path
            if (path in parsed) continue
            val entry = entries[path] ?: continue
            if (!namesChanged || entry.lookups.all { (fqn, known) -> (fqn in classNames) == known }) continue
            val text = (if (entry.fromBuffer) bufferText(path) else null) ?: readText(vf) ?: continue
            texts[path] = text to entry.fromBuffer
            reparse += vf
        }
        inParallel(reparse) { vf -> parse(vf, texts.getValue(vf.path).first) }
            .forEachIndexed { i, source -> parsed[reparse[i].path] = source }

        val toRender = files.filter { it.path in parsed }
        val rendered = inParallel(toRender) { vf ->
            val source = parsed[vf.path] ?: return@inParallel null
            val renderer = LightClassRenderer(source) { it in classNames }
            val classes = runCatching { renderer.render() }.getOrDefault(emptyList())
            classes to HashMap(renderer.lookups)
        }
        toRender.forEachIndexed { i, vf ->
            val path = vf.path
            val (text, fromBuffer) = texts.getValue(path)
            val source = parsed[path]
            val result = rendered[i]
            val previous = entries[path]
            entries[path] = if (source == null || result == null) {
                FileEntry(hash64(text), fromBuffer, emptySet(), emptyMap(), emptyList())
            } else {
                FileEntry(
                    textHash = hash64(text),
                    fromBuffer = fromBuffer,
                    declared = source.classes.mapTo(HashSet()) { it.fqn },
                    lookups = result.second,
                    // Same shape as before: hand back the previous list so consumers see an unchanged result.
                    classes = previous?.classes?.takeIf { it == result.first } ?: result.first,
                )
            }
        }

        val parts = files.map { entries[it.path]?.classes ?: emptyList() }
        if (parts.size != state.parts.size || parts.indices.any { parts[it] !== state.parts[it] }) {
            state.parts = parts
            state.result = parts.flatten()
        }
        state.result
    }

    /** The path of the Kotlin file whose light classes include [fqn] (nested classes too), among the files
     *  rendered so far. */
    fun fileDeclaring(fqn: String): String? = synchronized(lock) {
        fun declares(c: SyntheticClass): Boolean = c.fqName == fqn || c.nestedClasses.any(::declares)
        entries.entries.firstOrNull { (_, e) -> e.classes.any(::declares) }?.key
    }

    /** [path]'s content changed on disk (a save, an outside write): read it again on the next [classesFor]. */
    fun invalidate(path: String) {
        synchronized(lock) { entries.remove(path) }
    }

    /** Files may have been added or removed: list every module's source roots again on its next [classesFor].
     *  Entries of files that are still there are kept. */
    fun rescan() {
        synchronized(lock) { modules.values.forEach { it.files = null } }
    }

    /** Forget everything (memory pressure, a full re-index). */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            modules.clear()
        }
    }

    private fun parse(vf: VirtualFile, text: String): SourceFile? {
        parses.incrementAndGet()
        return runCatching { SourceIndexBuilder.extract(vf, text) }.getOrNull()
    }

    private fun readText(vf: VirtualFile): String? = runCatching { vf.readText().toString() }.getOrNull()

    private fun listKotlinFiles(roots: List<VirtualFile>): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        val seen = HashSet<String>()
        fun walk(f: VirtualFile) {
            if (f.isDirectory) runCatching { f.children() }.getOrDefault(emptyList()).forEach(::walk)
            else if (f.name.endsWith(".kt") && seen.add(f.path)) out += f
        }
        roots.forEach(::walk)
        return out
    }

    private companion object {
        /** Below this many files the hand-off to other threads costs more than it saves. */
        const val PARALLEL_MIN = 8

        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

        val pool: java.util.concurrent.ExecutorService by lazy {
            val n = java.util.concurrent.atomic.AtomicInteger()
            java.util.concurrent.Executors.newFixedThreadPool(workers) { r ->
                Thread(r, "kotlin-light-${n.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        /**
         * [work] over [items], in order, on the worker pool when there are enough of them. [work] must touch nothing
         * shared but what it is handed (no entries, no callbacks into the caller). An item whose worker failed is
         * done again on the calling thread.
         */
        fun <T, R> inParallel(items: List<T>, work: (T) -> R): List<R> {
            if (items.size < PARALLEL_MIN || workers < 2) return items.map(work)
            val futures = items.map { item -> pool.submit<R> { work(item) } }
            return futures.mapIndexed { i, f -> runCatching { f.get() }.getOrElse { work(items[i]) } }
        }

        /** 64-bit FNV-1a over the UTF-16 units, mixed with the length: a text identity that keeps no copy. */
        fun hash64(text: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (i in text.indices) {
                h = h xor text[i].code.toLong()
                h *= 0x100000001b3L
            }
            return h xor text.length.toLong()
        }
    }
}

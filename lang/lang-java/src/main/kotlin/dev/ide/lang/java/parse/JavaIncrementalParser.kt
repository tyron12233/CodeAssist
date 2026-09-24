package dev.ide.lang.java.parse

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.platform.DeviceMemory
import dev.ide.vfs.VirtualFile
import java.util.Collections

/**
 * First-cut incremental parser: [reparse] re-parses the whole file (and [parseFull] skips the parse when the text
 * has not changed). IntelliJ PSI supports true incremental
 * reparse (reusing unchanged subtrees); that
 * is a later optimization. A full re-parse of a single Java file is inexpensive relative to resolution, so this
 * is correct and adequate to land the backend.
 *
 * Each parse of the LIVE buffer is cached by file path ([latestFor]) so the editor-QoL services (semantic
 * highlight, folding, inlay hints) read the exact tree the host just parsed from the unsaved buffer — NOT the
 * analyzer's content-hash-keyed parse of the on-disk bytes, which would place tokens at stale offsets after an
 * edit.
 */
class JavaIncrementalParser(private val env: JavaEnvironment) : IncrementalParser {

    /** A parse together with the exact text it was parsed from, so an unchanged buffer is never reparsed. */
    private class Live(val text: String, val tree: JavaParsedFile)

    // Access-order LRU, capped so a long session that opens many files doesn't retain every parse. Only the
    // focused editor's file(s) need to be live; the host refreshes it right before highlight/fold/inlay.
    private val maxLive = DeviceMemory.pick(normal = 16, low = 6)
    private val latest: MutableMap<String, Live> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Live>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Live>): Boolean = size > maxLive
        },
    )

    /** The most recent live-buffer parse for [file], or null if the host hasn't parsed it through here yet. */
    fun latestFor(file: VirtualFile): JavaParsedFile? = latest[file.path]?.tree

    /** The live parse of [path] if it was parsed from exactly [text], else null. */
    fun latestIfText(path: String, text: CharSequence): JavaParsedFile? =
        latest[path]?.takeIf { it.text.length == text.length && it.text.contentEquals(text) }?.tree

    /**
     * Parse [snapshot], or hand back the previous tree when its text is unchanged. One settled edit reaches
     * here once per editor pass (folds, highlighting, diagnostics, inlays) and again for the file-structure
     * views, all with the same text; each full parse runs under the process-wide PSI lock.
     */
    override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
        latestIfText(snapshot.file.path, snapshot.text)?.let { return it }
        val text = snapshot.text.toString()
        val tree = JavaParsedFile(env.parse(snapshot.file.name, text), snapshot.file, snapshot.version)
        latest[snapshot.file.path] = Live(text, tree)
        return tree
    }

    /** Keep only the most recently used parse (memory pressure); the file on screen stays readable. */
    fun trimToLatest() {
        synchronized(latest) {
            val keep = latest.entries.lastOrNull() ?: return
            latest.clear()
            latest[keep.key] = keep.value
        }
    }

    override fun reparse(
        previous: ParsedFile,
        newSnapshot: DocumentSnapshot,
        edits: List<DocumentEdit>,
    ): ReparseResult {
        val tree = parseFull(newSnapshot)
        return ReparseResult(tree, TextRange(0, newSnapshot.length()), reusedSubtrees = 0)
    }
}

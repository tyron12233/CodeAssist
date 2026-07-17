package dev.ide.lang.java.parse

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.vfs.VirtualFile
import java.util.Collections

/**
 * First-cut incremental parser: [reparse] re-parses the whole file. IntelliJ PSI supports true incremental
 * reparse (reusing unchanged subtrees, as [dev.ide.lang.kotlin.parse.KotlinPsiMutation] does for Kotlin); that
 * is a later optimization. A full re-parse of a single Java file is inexpensive relative to resolution, so this
 * is correct and adequate to land the backend.
 *
 * Each parse of the LIVE buffer is cached by file path ([latestFor]) so the editor-QoL services (semantic
 * highlight, folding, inlay hints) read the exact tree the host just parsed from the unsaved buffer — NOT the
 * analyzer's content-hash-keyed parse of the on-disk bytes, which would place tokens at stale offsets after an
 * edit.
 */
class JavaIncrementalParser(private val env: JavaEnvironment) : IncrementalParser {

    // Access-order LRU, capped so a long session that opens many files doesn't retain every parse. Only the
    // focused editor's file(s) need to be live; the host refreshes it right before highlight/fold/inlay.
    private val latest: MutableMap<String, JavaParsedFile> = Collections.synchronizedMap(
        object : LinkedHashMap<String, JavaParsedFile>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, JavaParsedFile>): Boolean = size > MAX_LIVE
        },
    )

    /** The most recent live-buffer parse for [file], or null if the host hasn't parsed it through here yet. */
    fun latestFor(file: VirtualFile): JavaParsedFile? = latest[file.path]

    override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
        val tree = JavaParsedFile(env.parse(snapshot.file.name, snapshot.text), snapshot.file, snapshot.version)
        latest[snapshot.file.path] = tree
        return tree
    }

    override fun reparse(
        previous: ParsedFile,
        newSnapshot: DocumentSnapshot,
        edits: List<DocumentEdit>,
    ): ReparseResult {
        val tree = parseFull(newSnapshot)
        return ReparseResult(tree, TextRange(0, newSnapshot.length()), reusedSubtrees = 0)
    }

    private companion object {
        const val MAX_LIVE = 64
    }
}

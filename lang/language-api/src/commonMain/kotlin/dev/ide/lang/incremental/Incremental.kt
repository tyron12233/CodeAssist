package dev.ide.lang.incremental

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.vfs.VirtualFile

/**
 * Accounting for modification. Completion fires on nearly every keystroke, so re-parsing the whole
 * file each time is too slow on device. This package defines the cheaper path: immutable, versioned
 * document snapshots, edits as deltas, and an incremental reparse that reuses unchanged subtrees.
 */

/** Immutable, versioned text. Staleness is a cheap integer compare against ParsedFile.documentVersion. */
interface DocumentSnapshot {
    val file: VirtualFile
    val version: Long
    val text: CharSequence
    fun length(): Int
}

/** A single edit: replace [oldLength] chars at [offset] with [newText]. */
data class DocumentEdit(val offset: Int, val oldLength: Int, val newText: CharSequence) {
    val lengthDelta: Int get() = newText.length - oldLength
}

/**
 * Incremental parser. [reparse] does NOT rebuild from scratch: it shifts ranges of nodes after the
 * edit, widens the dirty region to the nearest reparsable boundary (usually the enclosing method
 * body), reparses only that span, and reattaches unchanged subtrees by reference (structural sharing).
 */
interface IncrementalParser {
    fun parseFull(snapshot: DocumentSnapshot): ParsedFile

    fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult
}

data class ReparseResult(
    val tree: ParsedFile,
    /** The span actually re-parsed; everything outside was reused. Useful for invalidating caches narrowly. */
    val reparsedRange: TextRange,
    val reusedSubtrees: Int,
)

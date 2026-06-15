package dev.ide.block

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentEdit

/**
 * The block editor's entry point. It only ever projects the shared model and computes the surgical
 * text edit a [BlockEdit] maps to — it never owns the document. This keeps the "one model, two live
 * views" model intact: the host applies the returned [DocumentEdit] to the same document the text
 * editor edits, and re-projection happens through the normal reparse path. A block edit and a
 * keystroke are the same kind of change to the one source of truth.
 */
interface BlockProjectionService {
    /**
     * Project [file] into its [BlockTree]. Implementations reuse the incremental parser's structural
     * sharing where possible, so a one-character edit re-projects a handful of blocks, not the file.
     */
    fun project(file: ParsedFile): BlockTree

    /** The block at [offset] in [tree] (caret → block), or null. */
    fun blockAt(tree: BlockTree, offset: Int): BlockNode? = tree.blockAt(offset)

    /**
     * The minimal change set [edit] maps to, computed against [tree] over its current [text] — usually one
     * [DocumentEdit], but a few edits (a [Move], or an [InsertTemplate] that also adds an import) are two
     * disjoint replaces. Empty means it could not be applied (a stale/missing ref). The caller applies
     * the edits (in descending offset order) to the document; reparse + re-projection follow. Everything
     * outside the returned ranges is preserved verbatim.
     */
    fun computeEdit(tree: BlockTree, text: CharSequence, edit: BlockEdit): List<DocumentEdit>
}

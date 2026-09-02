// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.action

/**
 * One replacement in a text buffer: drop [length] characters at [offset] and put [newText] there. An
 * insertion is `length = 0`, a deletion an empty [newText].
 *
 * Offsets are always against the buffer as the action was given it, never against a partially-edited one:
 * the host sorts a batch descending by offset before applying, so edits in one list must not overlap and
 * need no offset arithmetic between them.
 *
 * This mirrors the engine's own edit delta rather than reusing it, which is what keeps this SPI compilable
 * against nothing but the platform substrate.
 */
data class TextEdit(val offset: Int, val length: Int, val newText: String) {
    init {
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        require(length >= 0) { "length must be >= 0, was $length" }
    }

    companion object {
        fun insert(offset: Int, text: String) = TextEdit(offset, 0, text)
        fun delete(offset: Int, length: Int) = TextEdit(offset, length, "")
        fun replace(start: Int, end: Int, text: String) = TextEdit(start, end - start, text)
    }
}

/** A neutral instruction an action returns for the UI to carry out. Open set; the UI ignores ones it cannot
 *  honor. */
sealed interface ActionEffect {
    /** Open [path] in the editor, optionally moving the caret to [offset]. */
    data class OpenFile(val path: String, val offset: Int? = null) : ActionEffect

    /** Navigate to a named UI destination (a screen or tool-window id). */
    data class Navigate(val target: String) : ActionEffect

    /** Re-read the file tree (a file/dir was created/removed). */
    data object RefreshTree : ActionEffect

    /** Re-read the active editor's content from disk (a file the editor shows changed underneath it). */
    data class ReloadFile(val path: String) : ActionEffect

    // ---- editing ---------------------------------------------------------------------------------
    //
    // Edits go through the editor's own text path rather than being written to disk behind its back, so a
    // plugin action lands in the same undo stack as typing and re-triggers analysis the ordinary way.

    /**
     * Apply [edits] to the file the action was invoked in (the active editor's buffer). The common case:
     * an action rewrites the code around the caret.
     */
    data class ApplyEdits(val edits: List<TextEdit>) : ActionEffect {
        constructor(vararg edits: TextEdit) : this(edits.toList())
    }

    /**
     * Apply edits spanning several files, keyed by absolute path. Applied as one atomic change: open
     * buffers are edited in place, closed files are written through. This is what a refactor that reaches
     * beyond the current file uses, such as extracting a declaration and updating its references.
     */
    data class ApplyWorkspaceEdit(val edits: Map<String, List<TextEdit>>) : ActionEffect

    /** Put the caret at [offset] in the active editor, with nothing selected. */
    data class MoveCaret(val offset: Int) : ActionEffect

    /**
     * Select `[start, end)` in the active editor. Paired with an edit, this leaves a generated name
     * selected for the user to type over: the naming step of a refactor, without a dialog.
     */
    data class Select(val start: Int, val end: Int) : ActionEffect

    // ---- files -----------------------------------------------------------------------------------

    /**
     * Create [path] with [text], creating parent directories as needed. [open] opens it in the editor
     * afterwards. An existing file is never overwritten: the effect is skipped, so an action cannot
     * discard a file by picking a name that was already taken.
     */
    data class CreateFile(val path: String, val text: String = "", val open: Boolean = false) : ActionEffect

    /** Move or rename [from] to [to], following it in any editor tab that has it open. */
    data class RenameFile(val from: String, val to: String) : ActionEffect

    /** Delete [path], closing any editor tab showing it. */
    data class DeleteFile(val path: String) : ActionEffect
}

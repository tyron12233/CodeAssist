package dev.ide.ui.editor.core

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * The editor's recent clipboard texts, newest first, for Paste from History (IntelliJ's Ctrl+Shift+V).
 *
 * Fed by every copy and cut in the editor, and by each plain paste with what the system clipboard held (so text
 * copied in another app is in the list too). Kept in memory for as long as the app runs and never written to
 * disk: a copied password or token must not outlive the session in a file.
 */
object ClipboardHistory {
    /** Newest first, at most [MAX_ENTRIES], no two equal. Snapshot state, so a shown list updates. */
    val entries: SnapshotStateList<String> = mutableStateListOf()

    /** Put [text] at the top, moving it there if it is already in the list. Blank text, and text longer than
     *  [MAX_ENTRY_CHARS], is not kept. */
    fun record(text: String) {
        if (text.isBlank() || text.length > MAX_ENTRY_CHARS) return
        if (entries.firstOrNull() == text) return
        entries.remove(text)
        entries.add(0, text)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
    }

    /** Forget everything (the list's clear action). */
    fun clear() = entries.clear()

    const val MAX_ENTRIES = 20

    /** Longer copies are left out: twenty multi-megabyte selections is memory the editor needs more, and keeping
     *  a cut-down copy would make a paste from history silently paste only part of what was copied. */
    const val MAX_ENTRY_CHARS = 200_000
}

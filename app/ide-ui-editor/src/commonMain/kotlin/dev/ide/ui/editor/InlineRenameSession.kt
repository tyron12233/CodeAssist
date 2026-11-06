package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.core.EditSpan
import dev.ide.ui.editor.core.EditorSession

/**
 * In-place rename: the symbol's name is edited right in the editor, and every other place this file names the
 * same symbol follows as you type. The project-wide rename runs once the name is confirmed; this file is already
 * done by then, which is what makes the rename read as instant.
 *
 * The edited occurrence is the [primary] one, the name under the caret. The others are mirrored to it after each
 * edit ([mirror], driven by the host on every text revision), never inside the edit callback, so a mirror edit is
 * an ordinary edit the session re-anchors like any other.
 *
 * Offsets live in document space and are re-anchored on every edit ([onEdit], wired to
 * [EditorSession.onSnippetEdit]): an edit before a name shifts it, an edit touching a name grows or shrinks it.
 * An edit that touches no name is the user typing somewhere else, and is reported through [strayEdit] so the host
 * can end the session the way clicking away ends one in IntelliJ.
 *
 * [originalText] is the buffer as it was when the rename began, and [offset] the caret in it: what the
 * project-wide rename resolves the symbol from, since by the time it runs this buffer already has the new name.
 */
internal class InlineRenameSession(
    private val editor: EditorSession,
    ranges: List<IntRange>,
    primaryIndex: Int,
    val originalText: String,
    val offset: Int,
    val oldName: String,
    val kind: String,
) {
    private class MutRange(var start: Int, var end: Int)

    private val ranges: List<MutRange> = ranges.map { MutRange(it.first, it.last + 1) }
    private val primary: MutRange = this.ranges[primaryIndex]
    private var mirroring = false

    /** An edit landed outside every occurrence: the user moved on and typed somewhere else. */
    var strayEdit: Boolean = false
        private set

    /** Re-anchor every occurrence to survive [span]. */
    fun onEdit(span: EditSpan) {
        val es = span.start
        val ee = span.start + span.removed
        val d = span.delta
        var touched = false
        for (r in ranges) {
            when {
                ee < r.start -> { r.start += d; r.end += d }                    // entirely before
                es > r.end -> Unit                                              // entirely after
                else -> {                                                       // touches it (typing at its edges too)
                    // Only the edited name is the user's to type in; the others change through [mirror]. Typing
                    // against one of them by hand is typing somewhere else.
                    if (r === primary || mirroring) touched = true else strayEdit = true
                    r.end = maxOf(r.end + d, es)
                    if (es < r.start) r.start = es
                    if (r.end < r.start) r.end = r.start
                }
            }
        }
        if (!touched && !mirroring) strayEdit = true
    }

    /** The name as typed so far. */
    fun currentName(): String = text(primary)

    /** Every occurrence, for the editor to box while the rename is live. */
    fun fieldRanges(): List<IntRange> = ranges.map { it.start until it.end }

    /** Select the edited name, so typing replaces it. */
    fun selectPrimary() {
        editor.setSelectionRange(primary.start.coerceIn(0, editor.doc.length), primary.end.coerceIn(0, editor.doc.length))
    }

    /** Copy the edited name into every other occurrence that does not already read the same. */
    fun mirror() = setAll(currentName(), includePrimary = false)

    /** Put the old name back everywhere: a cancelled rename, or one the project refused. */
    fun restore() = setAll(oldName, includePrimary = true)

    private fun setAll(name: String, includePrimary: Boolean) {
        if (mirroring) return
        val stale = ranges.filter { (includePrimary || it !== primary) && inBounds(it) && text(it) != name }
        if (stale.isEmpty()) return
        mirroring = true
        try {
            editor.beginBatch()
            // Descending, so a replacement never moves an occurrence still to be replaced; [onEdit] re-anchors the
            // rest as each one lands. The caret and an IME composition (the word the keyboard is still composing,
            // in the edited name) ride along with the text before them.
            for (r in stale.sortedByDescending { it.start }) {
                val start = r.start
                val end = r.end
                val delta = name.length - (end - start)
                fun map(p: Int): Int = if (p >= end) p + delta else if (p > start) start + name.length else p
                val sel = editor.selection
                val comp = editor.composing
                editor.replaceRange(
                    start, end, name,
                    TextRange(map(sel.start), map(sel.end)),
                    comp?.let { TextRange(map(it.start), map(it.end)) },
                )
            }
            editor.endBatch()
        } finally {
            mirroring = false
        }
    }

    private fun inBounds(r: MutRange): Boolean = r.start in 0..editor.doc.length && r.end in r.start..editor.doc.length

    private fun text(r: MutRange): String = if (inBounds(r)) editor.doc.substring(r.start, r.end) else ""

    companion object {
        /**
         * A session over [highlights] (this file's occurrences of the symbol, as the usage highlighter resolved
         * them), with the one at [caret] as the edited name; null when there is no occurrence at the caret or an
         * occurrence does not read [oldName] (a backticked or otherwise odd spelling the popup handles better).
         */
        fun startOrNull(
            editor: EditorSession, highlights: List<IntRange>, caret: Int, text: String, oldName: String, kind: String,
        ): InlineRenameSession? {
            val ranges = highlights.distinctBy { it.first }.sortedBy { it.first }
            if (ranges.isEmpty()) return null
            if (ranges.any { it.first < 0 || it.last >= text.length || text.substring(it.first, it.last + 1) != oldName }) return null
            if (ranges.zipWithNext().any { (a, b) -> b.first <= a.last }) return null
            val primary = ranges.indexOfFirst { caret >= it.first && caret <= it.last + 1 }.takeIf { it >= 0 } ?: return null
            return InlineRenameSession(editor, ranges, primary, text, caret, oldName, kind)
        }
    }
}

/**
 * Whether [name] can be an identifier at all (letters, digits, `_` and `$`, not starting with a digit). Checked
 * before the project-wide rename starts so a stray character is pointed out in place; keywords and the language's
 * own rules are the backend's to judge.
 */
internal fun isRenameableIdentifier(name: String): Boolean =
    name.isNotEmpty() && !name[0].isDigit() && name.all { it.isLetterOrDigit() || it == '_' || it == '$' }

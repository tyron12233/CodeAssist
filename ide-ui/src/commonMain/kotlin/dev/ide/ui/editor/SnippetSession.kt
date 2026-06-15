package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.UiSnippet
import dev.ide.ui.editor.core.EditSpan
import dev.ide.ui.editor.core.EditorSession

/**
 * Drives a live snippet (template) expansion in the editor: after the insert, the caret steps through tab
 * stops with Tab / Shift-Tab, finishing at the `$0` position. A stop with several ranges (a repeated `$1`,
 * e.g. the loop variable in a `for`) is linked: its first occurrence is the edited one, and the rest are
 * mirrored to match on Tab past it, so renaming the placeholder renames every copy.
 *
 * Offsets are kept in document space and re-anchored on every edit via [onEdit] (wired to
 * [EditorSession.onSnippetEdit]), so typing inside a placeholder keeps the remaining stops correct.
 */
class SnippetSession private constructor(
    private val editor: EditorSession,
    private val stops: List<TabStop>,
    private var finalCaret: Int,
) {
    private class MutRange(var start: Int, var end: Int)
    private class TabStop(val ranges: MutableList<MutRange>)

    private var idx = -1

    /** Re-anchor every tracked range and the final caret to survive an edit. */
    fun onEdit(span: EditSpan) {
        val es = span.start
        val ee = span.start + span.removed
        val d = span.added - span.removed
        for (s in stops) for (r in s.ranges) {
            when {
                ee <= r.start -> { r.start += d; r.end += d }          // edit entirely before the range
                es >= r.end -> Unit                                    // entirely after — unaffected
                else -> { r.end += d; if (es < r.start) r.start = es } // overlap (typing in the placeholder)
            }
        }
        finalCaret = shiftPoint(finalCaret, es, ee, d)
    }

    private fun shiftPoint(p: Int, es: Int, ee: Int, d: Int): Int = when {
        ee <= p -> p + d
        es >= p -> p
        else -> es + (d + (ee - es)) // edit straddles the point → land after the inserted text
    }

    /** Move to the next stop; returns false (and lands the caret at `$0`) when there are none left. */
    fun next(): Boolean {
        mirrorCurrent()
        idx++
        val s = stops.getOrNull(idx) ?: run { finish(); return false }
        select(s)
        return true
    }

    /** Move to the previous stop (no-op past the first). */
    fun prev() {
        if (idx <= 0) return
        idx--
        stops.getOrNull(idx)?.let { select(it) }
    }

    fun finish() {
        val c = finalCaret.coerceIn(0, editor.doc.length)
        editor.setSelectionRange(c, c)
    }

    private fun select(s: TabStop) {
        val r = s.ranges.first()
        editor.setSelectionRange(r.start.coerceIn(0, editor.doc.length), r.end.coerceIn(0, editor.doc.length))
    }

    /** Copy the (possibly edited) primary placeholder text into the stop's linked occurrences. */
    private fun mirrorCurrent() {
        val s = stops.getOrNull(idx) ?: return
        if (s.ranges.size <= 1) return
        val primary = s.ranges.first()
        val len = editor.doc.length
        if (primary.start !in 0..len || primary.end !in primary.start..len) return
        val txt = editor.doc.substring(primary.start, primary.end)
        editor.beginBatch()
        // Apply to the linked copies in descending order so an earlier edit doesn't invalidate a later one's
        // offsets before it's applied; onEdit re-anchors the tracked ranges as each lands.
        for (r in s.ranges.drop(1).sortedByDescending { it.start }) {
            if (r.start in 0..editor.doc.length && r.end in r.start..editor.doc.length) {
                editor.replaceRange(r.start, r.end, txt, TextRange(r.start + txt.length))
            }
        }
        editor.endBatch()
    }

    companion object {
        /**
         * Begin a session for [snippet] whose text was just inserted starting at [base] (document offset).
         * Selects the first tab stop, or finishes immediately at `$0` if the snippet has no stops (returns
         * null in that case — nothing to drive).
         */
        fun start(editor: EditorSession, base: Int, snippet: UiSnippet): SnippetSession? {
            val stops = snippet.stops
                .filter { it.index != 0 }
                .sortedBy { it.index }
                .map { st -> TabStop(st.ranges.map { MutRange(base + it.start, base + it.end) }.toMutableList()) }
            val session = SnippetSession(editor, stops, base + snippet.finalCaretOffset)
            if (stops.isEmpty()) { session.finish(); return null }
            session.idx = 0
            session.select(stops.first())
            return session
        }
    }
}

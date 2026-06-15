package dev.ide.ui.editor

import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.editor.core.EditSpan
import dev.ide.ui.editor.core.EditorDocument

/**
 * Re-aligns diagnostics to an edited buffer so error squiggles and gutter marks track the text *as the
 * user types*, instead of sitting at stale offsets for the analysis debounce window (and then snapping).
 * Pure and backend-neutral: the editor calls [shiftDiagnostics] on every edit, and a fresh analysis
 * replaces the result a moment later — this just keeps the UI consistent in between.
 *
 * The session knows exactly what changed, so the live path takes an [EditSpan] + the post-edit
 * [EditorDocument] and re-maps in O(diagnostics) with no string diff. ([shiftDiagnostics] over two whole
 * strings is kept for callers that only have the before/after text.)
 */

/**
 * The minimal single edit that turns [old] into [new], recovered from their common prefix and suffix. An
 * editor emits one contiguous change per keystroke / paste / delete / range-replace, so this reconstructs
 * it exactly. (A non-contiguous change collapses to the smallest span covering it — slightly conservative,
 * which the follow-up re-analysis corrects.)
 */
private fun diffEdit(old: String, new: String): EditSpan {
    if (old == new) return EditSpan(0, 0, 0)
    val maxPrefix = minOf(old.length, new.length)
    var p = 0
    while (p < maxPrefix && old[p] == new[p]) p++
    var s = 0
    val maxSuffix = minOf(old.length - p, new.length - p) // keep the suffix from overlapping the prefix
    while (s < maxSuffix && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    return EditSpan(p, old.length - p - s, new.length - p - s)
}

// Diagnostic boundaries are mapped with gravity so an edit at a boundary does the intuitive thing: text
// inserted at a diagnostic's start pushes the start right (the insertion isn't part of it), while text at
// its end is left out — and an edit *inside* the range moves only the end, stretching/shrinking it.

/** Right-gravity map for a range start: an insertion at the boundary pushes it right; a position inside a
 *  deleted span clamps to the edit point. */
private fun mapStart(o: Int, edit: EditSpan): Int = when {
    o < edit.start -> o
    o == edit.start -> if (edit.removed == 0) o + edit.delta else o
    o < edit.start + edit.removed -> edit.start
    else -> o + edit.delta
}

/** Left-gravity map for a range end: an insertion at the boundary leaves it; a position inside (or at the
 *  end of) a deleted span clamps to the edit point. */
private fun mapEnd(o: Int, edit: EditSpan): Int = when {
    o <= edit.start -> o
    o <= edit.start + edit.removed -> edit.start
    else -> o + edit.delta
}

/** 1-based line of [offset] in [text]. */
private fun lineOf(text: String, offset: Int): Int {
    var line = 1
    val end = offset.coerceIn(0, text.length)
    for (i in 0 until end) if (text[i] == '\n') line++
    return line
}

/**
 * Returns [diagnostics] (computed against [old]) re-mapped onto [new]. Offsets before the edit are kept;
 * offsets after it shift by the net length change; an edit *within* a diagnostic stretches or shrinks it
 * (the start holds via [mapOffset], the end moves) — and an edit that deletes a whole diagnostic drops it.
 * [UiDiagnostic.line] / [UiDiagnostic.col] are recomputed so the gutter stays in sync.
 */
fun shiftDiagnostics(diagnostics: List<UiDiagnostic>, old: String, new: String): List<UiDiagnostic> {
    if (diagnostics.isEmpty()) return diagnostics
    val edit = diffEdit(old, new)
    if (edit.isNoOp) return diagnostics
    val out = ArrayList<UiDiagnostic>(diagnostics.size)
    for (d in diagnostics) {
        val start = mapStart(d.startOffset, edit).coerceIn(0, new.length)
        val end = mapEnd(d.endOffset, edit).coerceIn(start, new.length)
        if (end <= start && d.endOffset > d.startOffset) continue // the edit consumed the whole range
        val line = lineOf(new, start)
        val col = start - (new.lastIndexOf('\n', start - 1) + 1) + 1
        out.add(d.copy(startOffset = start, endOffset = end, line = line, col = col))
    }
    return out
}

/**
 * The O(diagnostics) live path: re-map [diagnostics] across a single [edit] the session just applied, using
 * the post-edit [doc] for line/col (a binary search per diagnostic — no whole-text scan). Used per keystroke.
 */
fun shiftDiagnostics(diagnostics: List<UiDiagnostic>, edit: EditSpan, doc: EditorDocument): List<UiDiagnostic> {
    if (diagnostics.isEmpty() || edit.isNoOp) return diagnostics
    val len = doc.length
    val out = ArrayList<UiDiagnostic>(diagnostics.size)
    for (d in diagnostics) {
        val start = mapStart(d.startOffset, edit).coerceIn(0, len)
        val end = mapEnd(d.endOffset, edit).coerceIn(start, len)
        if (end <= start && d.endOffset > d.startOffset) continue // the edit consumed the whole range
        val ln = doc.lineForOffset(start)
        out.add(d.copy(startOffset = start, endOffset = end, line = ln + 1, col = start - doc.lineStart(ln) + 1))
    }
    return out
}

package dev.ide.ui.editor.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.shiftDiagnostics
import kotlin.math.max
import kotlin.math.min

/**
 * The editor's edit engine: the single place document text, selection, and IME composition mutate, and
 * the authoritative per-tab buffer (no host-side `TextFieldValue` mirror exists). It speaks only
 * `String`/[TextRange]/[EditorDocument]; nothing here builds a `TextFieldValue`.
 *
 * This is what makes typing O(edited line) instead of O(document): every change funnels through
 * [replaceRange], which splices the [EditorDocument] line index and the [LineStyles] token cache
 * incrementally and bumps only the affected lines' revisions, so the render layer re-lays-out just those
 * lines. State fields are Compose snapshot state — the canvas redraws by reading them, and the host keys
 * its debounced effects on [textRevision] and pulls [EditorDocument.text] lazily (never per keystroke).
 *
 * Out-of-band replacement (file revert, programmatic load) goes through [setText]; editors mutate via the
 * editing ops below.
 */
class EditorSession(
    initialText: String,
    val language: CodeLanguage,
    initialSelection: TextRange = TextRange(0),
) {

    var doc by mutableStateOf(EditorDocument.of(initialText))
        private set
    var selection by mutableStateOf(initialSelection.coercedIn(initialText.length))
        private set
    /** The IME composition region, drawn underlined; null when not composing. */
    var composing by mutableStateOf<TextRange?>(null)
        private set
    /** Bumped on every edit/caret move — the UI's bring-caret-into-view trigger. */
    var editCount by mutableIntStateOf(0)
        private set
    /**
     * Bumped only on text edits (not caret/selection moves). This is the host's O(1) "the buffer
     * changed" signal: debounced analyze/breadcrumb/projection effects key on it instead of the full text
     * `String`, so a keystroke costs an `Int` write, not an O(n) materialize-and-compare.
     */
    var textRevision by mutableIntStateOf(0)
        private set
    /** Longest line seen (chars) — monotonic per buffer; sizes the horizontal scroll range. */
    var maxLineChars by mutableIntStateOf(0)
        private set

    /**
     * Diagnostics anchored to this buffer: error/warning squiggles and gutter marks. The editor treats
     * them like every other span it owns (the line index, the token spans): [replaceRange] shifts them in
     * place on each edit (O(diagnostics), no string diff), so they track the text as it is typed. The host
     * supplies fresh authoritative results via [applyAnalysis] (debounced) and reads this to render.
     */
    var diagnostics by mutableStateOf<List<UiDiagnostic>>(emptyList())
        private set

    val styles = LineStyles(language)

    /**
     * Fired synchronously after every text edit with the contiguous [EditSpan] that changed (selection-only
     * moves don't fire). The host uses it to mark the buffer dirty in O(1) — no `String` is built or diffed.
     * (Diagnostics shift themselves inside the session; this hook is for host save-state only.) Fires once
     * per [replaceRange], including inside a batch.
     */
    var onTextEdit: ((EditSpan) -> Unit)? = null
    /**
     * Secondary per-edit hook, used by an active snippet (template) session to keep its tab-stop offsets in
     * sync as the user types into a placeholder. Separate from [onTextEdit] (owned by the host for dirty
     * tracking) so neither clobbers the other. Fires synchronously after every [replaceRange], like [onTextEdit].
     */
    var onSnippetEdit: ((EditSpan) -> Unit)? = null
    /** Render-cache hook: lines at/after `fromOldLine` (pre-edit indices) shifted by `delta`. */
    var onLinesShifted: ((fromOldLine: Int, delta: Int) -> Unit)? = null
    /** Set by the platform text-input node while an IME session is active. */
    var imeListener: ImeListener? = null

    interface ImeListener {
        /** Selection and/or composition changed — push `InputMethodManager.updateSelection`. */
        fun onStateChanged()
        /** The buffer was replaced externally — the IME must `restartInput`. */
        fun onRestartInput()
    }

    private var batchDepth = 0
    private var pendingIme = false // an IME state push was deferred while a batch was open
    private var goalColumn = -1 // sticky column for consecutive vertical caret moves

    init {
        styles.reset(doc)
        var m = 0
        for (i in 0 until doc.lineCount) m = max(m, doc.lineLength(i))
        maxLineChars = m
    }

    // ---- core mutation ----

    /** Replace `[start, end)` with [text]; everything else funnels here. */
    fun replaceRange(start: Int, end: Int, text: String, newSelection: TextRange, newComposing: TextRange? = null) {
        val s = start.coerceIn(0, doc.length)
        val e = end.coerceIn(s, doc.length)
        if (s == e && text.isEmpty()) {
            updateSelectionAndComposing(newSelection, newComposing)
            return
        }
        val firstLine = doc.lineForOffset(s)
        val lastLine = doc.lineForOffset(e)
        doc = doc.replace(s, e, text)
        var breaks = 0
        for (c in text) if (c == '\n') breaks++
        val removed = lastLine - firstLine + 1
        val inserted = breaks + 1
        styles.splice(doc, firstLine, removed, inserted)
        if (inserted != removed) onLinesShifted?.invoke(firstLine + removed, inserted - removed)
        for (l in firstLine until firstLine + inserted) maxLineChars = max(maxLineChars, doc.lineLength(l))
        selection = newSelection.coercedIn(doc.length)
        composing = newComposing?.coercedIn(doc.length)?.takeIf { it.min < it.max }
        goalColumn = -1
        editCount++
        textRevision++
        val edit = EditSpan(s, e - s, text.length)
        // Diagnostics are spans anchored to this buffer, so the editor shifts them here — in place, in apply
        // order (multi-edit ops re-map each diagnostic correctly), O(diagnostics), no String diff. Just like
        // the line index and token spans above, the buffer keeps its own decorations in sync.
        if (diagnostics.isNotEmpty()) diagnostics = shiftDiagnostics(diagnostics, edit, doc)
        // Host hook for save-state only (mark dirty); no String is built. Fires per edit, including in a batch.
        onTextEdit?.invoke(edit)
        onSnippetEdit?.invoke(edit)
        notifyIme()
    }

    /** Swap in a fresh authoritative analysis (aligned to the current text). The host calls this debounced. */
    fun applyAnalysis(result: List<UiDiagnostic>) {
        diagnostics = result
    }

    private fun updateSelectionAndComposing(sel: TextRange, comp: TextRange?) {
        val ns = sel.coercedIn(doc.length)
        val nc = comp?.coercedIn(doc.length)?.takeIf { it.min < it.max }
        if (ns == selection && nc == composing) return
        selection = ns
        composing = nc
        goalColumn = -1
        editCount++
        notifyIme()
    }

    /**
     * Push the IME its new selection/composition. This is the only per-edit "notification" left, and it's
     * O(1) — there is no host text mirror to rebuild. The host observes [textRevision]/[selection] as Compose
     * snapshot state and pulls [EditorDocument.text] lazily (debounced), so a keystroke never materializes a
     * `String`. Coalesced across a batch so a multi-edit op pushes the IME once.
     */
    private fun notifyIme() {
        if (batchDepth > 0) {
            pendingIme = true
            return
        }
        imeListener?.onStateChanged()
    }

    /** IME batch edits (and multi-edit completion accepts): one IME push for the whole group. */
    fun beginBatch() {
        batchDepth++
    }

    fun endBatch() {
        if (batchDepth > 0 && --batchDepth == 0 && pendingIme) {
            pendingIme = false
            imeListener?.onStateChanged()
        }
    }

    // ---- external replacement ----

    /**
     * Replace the whole buffer out-of-band — a file revert or a programmatic load. Not on the typing path
     * (editors mutate via the editing ops); this rebuilds the document, resets the styler, lands the caret
     * at [selection], and bumps [textRevision] so the debounced analysis re-runs and the IME restarts.
     */
    fun setText(text: String, selection: TextRange = TextRange(0)) {
        doc = EditorDocument.of(text)
        styles.reset(doc)
        var m = 0
        for (i in 0 until doc.lineCount) m = max(m, doc.lineLength(i))
        maxLineChars = m
        this.selection = selection.coercedIn(doc.length)
        composing = null
        diagnostics = emptyList() // a wholesale replace invalidates the old anchors; re-analysis will refill
        goalColumn = -1
        editCount++
        textRevision++
        imeListener?.onRestartInput()
    }

    // ---- editing ops (keyboard + UI) ----

    /** Type [text] at the selection. Single chars get the smart-edit rules (auto-close, skip-over…). */
    fun commitText(text: String) {
        if (text.length == 1) {
            val edit = smartInsert(doc.chars, selection.min, selection.max, text[0], language)
            replaceRange(edit.start, edit.end, edit.text, TextRange(edit.caret))
        } else {
            val s = selection.min
            replaceRange(s, selection.max, text, TextRange(s + text.length))
        }
    }

    fun backspace(word: Boolean = false) {
        if (word && selection.collapsed) {
            val s = wordBoundaryLeft(doc.chars, selection.start)
            if (s < selection.start) replaceRange(s, selection.start, "", TextRange(s))
            return
        }
        val edit = smartBackspace(doc.chars, selection.min, selection.max) ?: return
        replaceRange(edit.start, edit.end, edit.text, TextRange(edit.caret))
    }

    fun deleteForward(word: Boolean = false) {
        if (!selection.collapsed) {
            replaceRange(selection.min, selection.max, "", TextRange(selection.min))
            return
        }
        val p = selection.start
        val e = if (word) wordBoundaryRight(doc.chars, p) else nextCharBoundary(doc.chars, p)
        if (e > p) replaceRange(p, e, "", TextRange(p))
    }

    fun moveHorizontal(dir: Int, select: Boolean, word: Boolean = false) {
        val text = doc.chars
        // plain arrow over a selection collapses to its edge (platform convention)
        if (!select && !word && !selection.collapsed) {
            setCaret(if (dir < 0) selection.min else selection.max)
            return
        }
        val from = selection.end
        val target = when {
            word -> if (dir < 0) wordBoundaryLeft(text, from) else wordBoundaryRight(text, from)
            dir < 0 -> prevCharBoundary(text, from)
            else -> nextCharBoundary(text, from)
        }
        if (select) updateSelectionAndComposing(TextRange(selection.start, target), composing)
        else setCaret(target)
    }

    fun moveVertical(lines: Int, select: Boolean) {
        val from = selection.end
        val line = doc.lineForOffset(from)
        val col = from - doc.lineStart(line)
        val goal = if (goalColumn >= 0) goalColumn else col
        val targetLine = (line + lines).coerceIn(0, doc.lineCount - 1)
        val target = when {
            targetLine == line && lines < 0 -> 0 // already at the first line → document start
            targetLine == line && lines > 0 -> doc.length
            else -> doc.lineStart(targetLine) + goal.coerceAtMost(doc.lineLength(targetLine))
        }
        if (select) updateSelectionAndComposing(TextRange(selection.start, target), composing)
        else setCaret(target)
        goalColumn = goal // survive across consecutive vertical moves
    }

    /** Smart Home: first non-whitespace of the line, or column 0 when already there. */
    fun moveLineStart(select: Boolean) {
        val line = doc.lineForOffset(selection.end)
        val start = doc.lineStart(line)
        var indentEnd = start
        while (indentEnd < doc.lineEnd(line) && (doc.charAt(indentEnd) == ' ' || doc.charAt(indentEnd) == '\t')) indentEnd++
        val target = if (selection.end != indentEnd) indentEnd else start
        if (select) updateSelectionAndComposing(TextRange(selection.start, target), composing) else setCaret(target)
    }

    fun moveLineEnd(select: Boolean) {
        val target = doc.lineEnd(doc.lineForOffset(selection.end))
        if (select) updateSelectionAndComposing(TextRange(selection.start, target), composing) else setCaret(target)
    }

    fun moveDocBoundary(dir: Int, select: Boolean) {
        val target = if (dir < 0) 0 else doc.length
        if (select) updateSelectionAndComposing(TextRange(selection.start, target), composing) else setCaret(target)
    }

    fun setCaret(offset: Int) {
        updateSelectionAndComposing(TextRange(offset.coerceIn(0, doc.length)), null)
    }

    /** Drag-selection: keep the current anchor ([TextRange.start]), move the live end. */
    fun extendSelectionTo(offset: Int) {
        updateSelectionAndComposing(TextRange(selection.start, offset.coerceIn(0, doc.length)), null)
    }

    /** Selection-handle drag: replace one edge, normalized so start stays the anchor. */
    fun setSelectionRange(start: Int, end: Int) {
        updateSelectionAndComposing(TextRange(start.coerceIn(0, doc.length), end.coerceIn(0, doc.length)), null)
    }

    fun selectAll() {
        updateSelectionAndComposing(TextRange(0, doc.length), null)
    }

    fun selectWordAt(offset: Int) {
        val r = wordRangeAt(doc.chars, offset.coerceIn(0, doc.length))
        if (!r.isEmpty()) updateSelectionAndComposing(TextRange(r.first, r.last + 1), null)
        else setCaret(offset)
    }

    fun selectedText(): String? =
        if (selection.collapsed) null else doc.substring(selection.min, selection.max)

    fun cutSelection(): String? {
        val t = selectedText() ?: return null
        replaceRange(selection.min, selection.max, "", TextRange(selection.min))
        return t
    }

    /**
     * Apply a completion accept: the main replacement plus optional additional edits (auto-import),
     * already expressed in pre-edit offsets, then land the caret at [finalSelection]. One notify.
     */
    fun applyEdits(edits: List<RangeEdit>, finalSelection: TextRange) {
        beginBatch()
        composing = null
        for (e in edits.sortedByDescending { it.start }) {
            replaceRange(e.start, e.end, e.text, TextRange(e.text.length + e.start))
        }
        updateSelectionAndComposing(finalSelection, null)
        endBatch()
    }

    // ---- IME bridge (called by the platform InputConnection) ----

    fun imeCommitText(text: String, newCursorPosition: Int) {
        val region = composing ?: selection
        // single-char direct commits (no composition) get the same smart edits as hardware typing
        if (composing == null && selection.collapsed && text.length == 1 && newCursorPosition == 1) {
            commitText(text)
            return
        }
        val rs = region.min
        val caret = imeCaret(rs, text.length, newCursorPosition)
        replaceRange(rs, region.max, text, TextRange(caret), null)
    }

    fun imeSetComposingText(text: String, newCursorPosition: Int) {
        val region = composing ?: selection
        val rs = region.min
        val caret = imeCaret(rs, text.length, newCursorPosition)
        val comp = if (text.isEmpty()) null else TextRange(rs, rs + text.length)
        replaceRange(rs, region.max, text, TextRange(caret), comp)
    }

    fun imeSetComposingRegion(start: Int, end: Int) {
        val s = min(start, end).coerceIn(0, doc.length)
        val e = max(start, end).coerceIn(0, doc.length)
        updateSelectionAndComposing(selection, if (s < e) TextRange(s, e) else null)
    }

    fun imeFinishComposing() {
        if (composing != null) updateSelectionAndComposing(selection, null)
    }

    fun imeDeleteSurrounding(before: Int, after: Int) {
        // the Gboard backspace fast path — and the one place pair-aware delete applies
        if (before == 1 && after == 0 && composing == null && selection.collapsed) {
            backspace()
            return
        }
        beginBatch()
        val selMax = selection.max
        val aEnd = (selMax + after).coerceAtMost(doc.length)
        if (aEnd > selMax) replaceRange(selMax, aEnd, "", TextRange(selection.start, selection.end), composing)
        val selMin = selection.min
        val bStart = (selMin - before).coerceAtLeast(0)
        if (bStart < selMin) {
            val del = selMin - bStart
            replaceRange(bStart, selMin, "", TextRange(selection.min - del, selection.max - del), null)
        }
        endBatch()
    }

    fun imeSetSelection(start: Int, end: Int) {
        updateSelectionAndComposing(TextRange(start, end), composing)
    }

    fun imeTextBeforeCursor(n: Int): String {
        val end = selection.min
        return doc.substring((end - n.coerceIn(0, MAX_IPC_TEXT)).coerceAtLeast(0), end)
    }

    fun imeTextAfterCursor(n: Int): String {
        val start = selection.max
        return doc.substring(start, (start + n.coerceIn(0, MAX_IPC_TEXT)).coerceAtMost(doc.length))
    }

    private fun imeCaret(regionStart: Int, insertedLen: Int, newCursorPosition: Int): Int =
        if (newCursorPosition > 0) regionStart + insertedLen + newCursorPosition - 1
        else regionStart + newCursorPosition

    private companion object {
        /** Cap for IME text queries — never ship megabytes across the binder (sora's maxIPCTextLength). */
        const val MAX_IPC_TEXT = 4096
    }
}

private fun TextRange.coercedIn(length: Int): TextRange =
    TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

private fun nextCharBoundary(text: CharSequence, i: Int): Int = when {
    i >= text.length -> text.length
    text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> i + 2
    else -> i + 1
}

private fun prevCharBoundary(text: CharSequence, i: Int): Int = when {
    i <= 0 -> 0
    text[i - 1].isLowSurrogate() && i >= 2 && text[i - 2].isHighSurrogate() -> i - 2
    else -> i - 1
}

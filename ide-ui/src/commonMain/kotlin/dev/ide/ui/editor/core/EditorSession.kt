package dev.ide.ui.editor.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.folding.FoldModel
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.editor.shiftComposePreviews
import dev.ide.ui.editor.shiftDiagnostics
import dev.ide.ui.editor.shiftFoldRegions
import dev.ide.ui.editor.shiftInlayHints
import dev.ide.ui.editor.shiftSemanticTokens
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

    /**
     * Type-aware semantic-highlight tokens anchored to this buffer. Like [diagnostics], the editor shifts them
     * in place on each edit ([shiftSemanticTokens]) so the coloring tracks the text between debounced passes;
     * the host supplies fresh authoritative results via [applySemanticTokens]. The render layer overlays them
     * on the lexical token spans.
     */
    var semanticTokens by mutableStateOf<List<UiSemanticToken>>(emptyList())
        private set

    /**
     * `@Preview` gutter markers anchored to this buffer. Like [diagnostics]/[semanticTokens] the editor shifts
     * their offsets in place on each edit ([shiftComposePreviews]) so the gutter icons track the function they
     * mark while typing, instead of sitting at a stale line until the debounced refetch. Host refills via
     * [applyComposePreviews].
     */
    var previewMarkers by mutableStateOf<List<UiComposePreview>>(emptyList())
        private set

    /**
     * Inlay hints (inferred `val`/lambda types, parameter names) anchored to this buffer. Like
     * [diagnostics]/[semanticTokens] the editor shifts their offsets in place on each edit
     * ([dev.ide.ui.editor.shiftInlayHints]) so the phantom text tracks its anchor between debounced daemon
     * passes; the host refills the authoritative set via [applyInlayHints].
     */
    var inlayHints by mutableStateOf<List<UiInlayHint>>(emptyList())
        private set

    /**
     * Foldable regions anchored to this buffer (imports, type/function bodies, comments) with each one's
     * collapsed/expanded toggle. Like [diagnostics] the editor shifts their offsets on each edit
     * ([dev.ide.ui.editor.shiftFoldRegions]) so a collapsed fold keeps covering its block while typing; the
     * host refills the authoritative set via [applyCodeFolds], preserving the user's current toggles. The
     * renderer/geometry read [foldModel], rebuilt lazily whenever the regions or the document change.
     */
    var foldRegions by mutableStateOf<List<FoldRegion>>(emptyList())
        private set

    /** Whether the [collapsedByDefault] folds have been applied once for this file (so a refetch doesn't re-collapse
     *  a region the user has since expanded). */
    private var defaultFoldsApplied = false

    private var foldModelCache: FoldModel? = null
    private var foldModelDoc: EditorDocument? = null
    private var foldModelRegions: List<FoldRegion>? = null

    /** The current folding projection (document ⇄ visual-row mapping + composites), rebuilt only when the
     *  regions or the document change. [FoldModel.EMPTY]-equivalent when nothing folds. */
    val foldModel: FoldModel
        get() {
            val d = doc; val r = foldRegions
            if (foldModelCache == null || foldModelDoc !== d || foldModelRegions !== r) {
                foldModelCache = FoldModel.build(d, r)
                foldModelDoc = d; foldModelRegions = r
            }
            return foldModelCache!!
        }

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
        /** Selection and/or composition changed (no text edit) — push `InputMethodManager.updateSelection`. */
        fun onStateChanged()
        /**
         * The buffer's text changed. [span] is the single contiguous edit, so the platform can push a partial
         * `updateExtractedText` (cheap — only the changed run is materialized); a null [span] (emitted once at
         * the end of a multi-edit batch) asks for a full-snapshot refresh. Default: a plain selection push, so a
         * listener that doesn't mirror text keeps behaving as it did before.
         */
        fun onTextChanged(span: EditSpan?) { onStateChanged() }
        /** The buffer was replaced externally — the IME must `restartInput`. */
        fun onRestartInput()
        /**
         * Whether an IME is continuously mirroring our text (an extracted-text monitor is active). When true a
         * smart edit needn't `restartInput` to resync: the per-edit [onTextChanged] push already keeps the IME's
         * model exact, so [resyncIme] skips the (disruptive) restart and only the legacy non-monitoring path uses it.
         */
        fun isSyncingExtractedText(): Boolean = false
    }

    private var batchDepth = 0
    private var pendingIme = false // an IME state push was deferred while a batch was open
    private var pendingImeText = false // ...and at least one of those deferred pushes was a text edit
    private var pendingRestart = false // an IME restart was deferred while a batch was open
    private var goalColumn = -1 // sticky column for consecutive vertical caret moves

    // ---- undo / redo ----
    // Each step stores the *inverse-able* edits (only the changed substrings, so memory is O(edited text), not
    // O(document)) plus the selection before/after. A batch (completion accept, IME multi-delete) records into
    // ONE step; consecutive single-char typing/backspacing coalesces into the step on top so undo removes a
    // run, not one char. undo/redo replay through [replaceRange] with [applyingUndo] set so they don't re-record.
    private val undoStack = ArrayDeque<UndoStep>()
    private val redoStack = ArrayDeque<UndoStep>()
    private var currentGroup: UndoStep? = null   // open while a batch accumulates into one step
    private var applyingUndo = false             // suppress recording while undo/redo replays edits
    private var coalesceTyping = false           // may the next 1-char edit merge into the top step?

    /** Whether [undo]/[redo] would do something — observable so a toolbar can enable/disable its buttons. */
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

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
        val removedText = if (applyingUndo) "" else doc.substring(s, e)
        val selBefore = selection
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
        if (semanticTokens.isNotEmpty()) semanticTokens = shiftSemanticTokens(semanticTokens, edit, doc.length)
        if (previewMarkers.isNotEmpty()) previewMarkers = shiftComposePreviews(previewMarkers, edit, doc.length)
        if (foldRegions.isNotEmpty()) foldRegions = shiftFoldRegions(foldRegions, edit, doc.length)
        if (inlayHints.isNotEmpty()) inlayHints = shiftInlayHints(inlayHints, edit, doc.length)
        if (!applyingUndo) recordEdit(s, removedText, text, selBefore)
        // Host hook for save-state only (mark dirty); no String is built. Fires per edit, including in a batch.
        onTextEdit?.invoke(edit)
        onSnippetEdit?.invoke(edit)
        notifyImeText(edit)
    }

    /** Swap in a fresh authoritative analysis (aligned to the current text). The host calls this debounced. */
    fun applyAnalysis(result: List<UiDiagnostic>) {
        diagnostics = result
    }

    /** Swap in fresh authoritative semantic-highlight tokens (aligned to the current text). Host calls debounced. */
    fun applySemanticTokens(result: List<UiSemanticToken>) {
        semanticTokens = result
    }

    /** Swap in fresh `@Preview` gutter markers (aligned to the current text). The host calls this debounced. */
    fun applyComposePreviews(result: List<UiComposePreview>) {
        previewMarkers = result
    }

    /** Swap in fresh inlay hints (aligned to the current text). The host calls this debounced (daemon pass). */
    fun applyInlayHints(result: List<UiInlayHint>) {
        inlayHints = result
    }

    /**
     * Adopt a freshly-computed set of foldable regions (host calls this debounced). The user's collapsed
     * toggles are PRESERVED across refetches: a new region overlapping a currently-collapsed one stays
     * collapsed. [collapsedByDefault] regions (imports) collapse only the FIRST time they appear for this file
     * (so re-expanding imports survives the next pass). Offsets come straight from the fresh parse.
     */
    fun applyCodeFolds(fresh: List<FoldRegion>) {
        val previouslyCollapsed = foldRegions.filter { it.collapsed }
        foldRegions = fresh.map { r ->
            val keepCollapsed = previouslyCollapsed.any { it.start == r.start && it.end == r.end } ||
                (r.collapsed && !defaultFoldsApplied) // collapsedByDefault, first time only
            r.copy(collapsed = keepCollapsed)
        }
        defaultFoldsApplied = true
    }

    /** Toggle the fold whose START is on document [line] (the gutter chevron / placeholder click). No-op when
     *  no fold starts there. Returns true if a fold was toggled. */
    fun toggleFoldAtLine(line: Int): Boolean {
        var toggled = false
        foldRegions = foldRegions.map { r ->
            if (!toggled && doc.lineForOffset(r.start) == line) { toggled = true; r.copy(collapsed = !r.collapsed) }
            else r
        }
        return toggled
    }

    /** Expand the fold covering document [offset] if any is collapsed there (used when the caret/selection
     *  lands inside hidden text, e.g. via go-to-definition). Returns true if something expanded. */
    fun expandFoldAt(offset: Int): Boolean {
        var changed = false
        foldRegions = foldRegions.map { r ->
            if (r.collapsed && offset > r.start && offset < r.end) { changed = true; r.copy(collapsed = false) } else r
        }
        return changed
    }

    private fun updateSelectionAndComposing(sel: TextRange, comp: TextRange?) {
        val ns = sel.coercedIn(doc.length)
        val nc = comp?.coercedIn(doc.length)?.takeIf { it.min < it.max }
        if (ns == selection && nc == composing) return
        selection = ns
        composing = nc
        goalColumn = -1
        coalesceTyping = false // a caret/selection move ends the current typing run → next type is a new undo step
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

    /** Like [notifyIme] but for a text edit: carries the [span] so the platform can push a partial extracted-text
     *  update. Coalesced across a batch into a single full-refresh push at [endBatch]. */
    private fun notifyImeText(span: EditSpan) {
        if (batchDepth > 0) {
            pendingIme = true
            pendingImeText = true
            return
        }
        imeListener?.onTextChanged(span)
    }

    /** IME batch edits (and multi-edit completion accepts): one IME push for the whole group, and ONE undo step. */
    fun beginBatch() {
        if (batchDepth == 0 && !applyingUndo) {
            currentGroup = UndoStep(ArrayList(), selection, selection)
            coalesceTyping = false
        }
        batchDepth++
    }

    fun endBatch() {
        if (batchDepth > 0 && --batchDepth == 0) {
            val g = currentGroup
            currentGroup = null
            if (g != null && g.edits.isNotEmpty()) {
                g.selAfter = selection
                redoStack.clear()
                undoStack.addLast(g)
                trimUndo()
                refreshUndoState()
            }
            if (pendingRestart) {
                // A restart supersedes the deferred selection/text pushes — the IME re-reads everything.
                pendingRestart = false
                pendingIme = false
                pendingImeText = false
                imeListener?.onRestartInput()
            } else if (pendingIme) {
                pendingIme = false
                val l = imeListener
                if (pendingImeText) { pendingImeText = false; l?.onTextChanged(null) } // null span → full refresh
                else l?.onStateChanged()
            }
        }
    }

    // ---- undo / redo ----

    private fun recordEdit(start: Int, removed: String, inserted: String, selBefore: TextRange) {
        val group = currentGroup
        if (group != null) {
            group.edits.add(UndoEdit(start, removed, inserted)) // inside a batch → one finalized step in endBatch
            coalesceTyping = false
            return
        }
        redoStack.clear()
        val top = undoStack.lastOrNull()
        if (!(coalesceTyping && top != null && tryCoalesce(top, start, removed, inserted))) {
            undoStack.addLast(UndoStep(arrayListOf(UndoEdit(start, removed, inserted)), selBefore, selection))
            trimUndo()
        }
        coalesceTyping = isCoalescable(removed, inserted)
        refreshUndoState()
    }

    /** Merge a single-char edit into [top] if it continues a forward typing run or a backspace run. */
    private fun tryCoalesce(top: UndoStep, start: Int, removed: String, inserted: String): Boolean {
        if (top.edits.size != 1) return false
        val ed = top.edits[0]
        if (removed.isEmpty() && inserted.length == 1 && ed.removed.isEmpty() && start == ed.start + ed.inserted.length) {
            ed.inserted += inserted // typing one more char right after the run
            top.selAfter = selection
            return true
        }
        if (inserted.isEmpty() && removed.length == 1 && ed.inserted.isEmpty() && start + removed.length == ed.start) {
            ed.start = start // backspacing one more char right before the run
            ed.removed = removed + ed.removed
            top.selAfter = selection
            return true
        }
        return false
    }

    private fun isCoalescable(removed: String, inserted: String): Boolean {
        val pureInsert = removed.isEmpty() && inserted.length == 1 && inserted[0] != '\n'
        val pureDelete = inserted.isEmpty() && removed.length == 1 && removed[0] != '\n'
        return pureInsert || pureDelete
    }

    /** Revert the most recent edit step. Returns false if there's nothing to undo (or a batch is open). */
    fun undo(): Boolean {
        if (currentGroup != null || undoStack.isEmpty()) return false
        val step = undoStack.removeLast()
        replayStep(step, undoing = true)
        redoStack.addLast(step)
        coalesceTyping = false
        refreshUndoState()
        return true
    }

    /** Re-apply the most recently undone step. Returns false if there's nothing to redo. */
    fun redo(): Boolean {
        if (currentGroup != null || redoStack.isEmpty()) return false
        val step = redoStack.removeLast()
        replayStep(step, undoing = false)
        undoStack.addLast(step)
        coalesceTyping = false
        refreshUndoState()
        return true
    }

    private fun replayStep(step: UndoStep, undoing: Boolean) {
        applyingUndo = true
        beginBatch() // coalesces the IME push; records nothing while applyingUndo is set
        composing = null
        if (undoing) {
            // invert edits in REVERSE application order: each stored start is valid in the state it restores
            for (i in step.edits.indices.reversed()) {
                val ed = step.edits[i]
                replaceRange(ed.start, ed.start + ed.inserted.length, ed.removed, TextRange(ed.start + ed.removed.length))
            }
            updateSelectionAndComposing(step.selBefore.coercedIn(doc.length), null)
        } else {
            for (ed in step.edits) {
                replaceRange(ed.start, ed.start + ed.removed.length, ed.inserted, TextRange(ed.start + ed.inserted.length))
            }
            updateSelectionAndComposing(step.selAfter.coercedIn(doc.length), null)
        }
        endBatch()
        applyingUndo = false
    }

    private fun trimUndo() {
        while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
    }

    private fun refreshUndoState() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
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
        semanticTokens = emptyList()
        previewMarkers = emptyList()
        foldRegions = emptyList()
        defaultFoldsApplied = false // re-apply collapse-by-default (imports) for the freshly-loaded buffer
        goalColumn = -1
        // a wholesale replace invalidates the inverse-edit history (offsets no longer mean anything)
        undoStack.clear(); redoStack.clear(); currentGroup = null; coalesceTyping = false
        refreshUndoState()
        editCount++
        textRevision++
        imeListener?.onRestartInput()
    }

    /**
     * Make the IME drop its internal cursor/composition model and re-read our post-edit text + selection.
     * The IME only learns our new selection offset (via `updateSelection`), never that the surrounding text
     * shifted by a different amount than the keystroke it delivered. After a smart edit (auto-close pair,
     * skip-over closer, newline + indent, pair-aware backspace, indent/dedent, forward-delete) its model
     * drifts, and a later absolute `setSelection`/`setComposingRegion` computed against that stale model lands
     * the caret on the wrong line. Restarting resyncs it — the same fix [applyEdits] uses on a completion
     * accept. NOT called on a plain insert or on a multi-char IME commit (the keyboard's own compose path),
     * where its model is already coherent and a restart would needlessly reset prediction/gesture state.
     *
     * Skipped entirely when an extracted-text monitor is active: that IME is kept exact by the per-edit
     * [ImeListener.onTextChanged] push (a partial `updateExtractedText`), so it never drifts and the restart
     * would be pure churn. The restart remains the fallback for IMEs that don't mirror our text.
     *
     * Deferred while a batch is open: a smart edit can fire from *inside* an IME batch (the Gboard
     * `deleteSurroundingText` fast path routes through [backspace]), and restarting input between the IME's
     * own batched ops would drop the rest of them. [endBatch] flushes the restart once the batch closes.
     */
    private fun resyncIme() {
        val l = imeListener ?: return
        if (l.isSyncingExtractedText()) return
        if (batchDepth > 0) {
            pendingRestart = true
            return
        }
        l.onRestartInput()
    }

    // ---- editing ops (keyboard + UI) ----

    /** Type [text] at the selection. Single chars get the smart-edit rules (auto-close, skip-over…). */
    fun commitText(text: String) {
        if (text.length == 1) {
            val selMin = selection.min
            val selMax = selection.max
            val edit = smartInsert(doc.chars, selMin, selMax, text[0], language)
            replaceRange(edit.start, edit.end, edit.text, TextRange(edit.caret))
            // A smart rule that didn't simply replace the selection with the typed char shifted the buffer by a
            // different amount than the IME delivered, so its model is now stale.
            if (edit.start != selMin || edit.end != selMax || edit.text != text || edit.caret != selMin + 1) {
                resyncIme()
            }
        } else {
            // The IME's own commit of a composed word lands here too — it matches the buffer, so no resync.
            val s = selection.min
            replaceRange(s, selection.max, text, TextRange(s + text.length))
        }
    }

    /**
     * Tab: indent to the next tab stop so code aligns to [tabWidth]-column boundaries (spaces, not a raw
     * `\t`). A collapsed caret (or a selection within one line) inserts just enough spaces to reach the next
     * stop from the caret's visual column; a multi-line selection indents every non-blank line one level and
     * keeps the block selected.
     */
    fun indent(tabWidth: Int = 4) {
        val sel = selection
        val firstLine = doc.lineForOffset(sel.min)
        var lastLine = doc.lineForOffset(sel.max)
        // A selection ending exactly at a line start doesn't include that trailing line.
        if (lastLine > firstLine && sel.max == doc.lineStart(lastLine)) lastLine--

        if (lastLine > firstLine) {
            beginBatch()
            val pad = " ".repeat(tabWidth)
            // Edit bottom-up so earlier line offsets stay valid as we splice.
            for (line in lastLine downTo firstLine) {
                val ls = doc.lineStart(line)
                if (doc.lineEnd(line) == ls) continue // leave blank lines un-indented
                replaceRange(ls, ls, pad, TextRange(ls))
            }
            updateSelectionAndComposing(TextRange(doc.lineStart(firstLine), doc.lineEnd(lastLine)), null)
            endBatch()
            resyncIme()
            return
        }

        val ls = doc.lineStart(firstLine)
        val col = visualColumn(ls, sel.min, tabWidth)
        val spaces = tabWidth - (col % tabWidth)
        val s = sel.min
        replaceRange(s, sel.max, " ".repeat(spaces), TextRange(s + spaces))
        resyncIme()
    }

    /**
     * Shift-Tab: remove one indent level (up to [tabWidth] leading spaces, or a single leading tab) from the
     * caret's line, or from every line a selection touches — keeping the block selected.
     */
    fun dedent(tabWidth: Int = 4) {
        val sel = selection
        val firstLine = doc.lineForOffset(sel.min)
        var lastLine = doc.lineForOffset(sel.max)
        if (lastLine > firstLine && sel.max == doc.lineStart(lastLine)) lastLine--
        val multi = lastLine > firstLine

        beginBatch()
        var removedOnFirst = 0
        for (line in lastLine downTo firstLine) {
            val ls = doc.lineStart(line)
            val n = leadingIndentToRemove(ls, tabWidth)
            if (n > 0) {
                if (line == firstLine) removedOnFirst = n
                replaceRange(ls, ls + n, "", TextRange(ls))
            }
        }
        if (multi) {
            updateSelectionAndComposing(TextRange(doc.lineStart(firstLine), doc.lineEnd(lastLine)), null)
        } else {
            val ls = doc.lineStart(firstLine)
            updateSelectionAndComposing(TextRange((sel.start - removedOnFirst).coerceAtLeast(ls)), null)
        }
        endBatch()
        resyncIme()
    }

    /** Visual column of [offset] within its line starting at [lineStartOffset], expanding tabs to [tabWidth]. */
    private fun visualColumn(lineStartOffset: Int, offset: Int, tabWidth: Int): Int {
        var col = 0
        val chars = doc.chars
        for (i in lineStartOffset until offset) {
            col += if (chars[i] == '\t') tabWidth - (col % tabWidth) else 1
        }
        return col
    }

    /** Number of leading chars to drop for one dedent on the line at [lineStartOffset]: one `\t`, else ≤[tabWidth] spaces. */
    private fun leadingIndentToRemove(lineStartOffset: Int, tabWidth: Int): Int {
        val chars = doc.chars
        if (lineStartOffset < doc.length && chars[lineStartOffset] == '\t') return 1
        var n = 0
        while (n < tabWidth && lineStartOffset + n < doc.length && chars[lineStartOffset + n] == ' ') n++
        return n
    }

    fun backspace(word: Boolean = false) {
        if (word && selection.collapsed) {
            val s = wordBoundaryLeft(doc.chars, selection.start)
            if (s < selection.start) {
                replaceRange(s, selection.start, "", TextRange(s))
                resyncIme() // a word delete removes more than the IME asked for
            }
            return
        }
        val selMin = selection.min
        val selMax = selection.max
        val edit = smartBackspace(doc.chars, selMin, selMax, language) ?: return
        replaceRange(edit.start, edit.end, edit.text, TextRange(edit.caret))
        // Pair-aware / blank-line backspace can delete more than the single char deleteSurroundingText(1,0) asked
        // for, so its result diverges from the IME's model.
        val literalStart = if (selMin == selMax) selMin - 1 else selMin
        if (edit.start != literalStart || edit.end != selMax || edit.text.isNotEmpty() || edit.caret != edit.start) {
            resyncIme()
        }
    }

    fun deleteForward(word: Boolean = false) {
        if (!selection.collapsed) {
            replaceRange(selection.min, selection.max, "", TextRange(selection.min))
            resyncIme()
            return
        }
        val p = selection.start
        val e = if (word) wordBoundaryRight(doc.chars, p) else nextCharBoundary(doc.chars, p)
        if (e > p) {
            replaceRange(p, e, "", TextRange(p))
            resyncIme()
        }
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

    /** Select the whole line containing [offset] (excluding the trailing newline) — the triple-tap gesture. */
    fun selectLineAt(offset: Int) {
        val line = doc.lineForOffset(offset.coerceIn(0, doc.length))
        updateSelectionAndComposing(TextRange(doc.lineStart(line), doc.lineEnd(line)), null)
    }

    /**
     * Move the caret to the next ([forward]) or previous diagnostic relative to the current caret, wrapping
     * around the buffer ends. Visits every diagnostic in offset order (errors and warnings alike). Returns
     * false (a no-op) when the file has no diagnostics.
     */
    fun goToDiagnostic(forward: Boolean): Boolean {
        if (diagnostics.isEmpty()) return false
        val offsets = diagnostics.map { it.startOffset.coerceIn(0, doc.length) }.distinct().sorted()
        val caret = selection.start
        val target = if (forward) offsets.firstOrNull { it > caret } ?: offsets.first()
        else offsets.lastOrNull { it < caret } ?: offsets.last()
        setCaret(target)
        return true
    }

    fun selectedText(): String? =
        if (selection.collapsed) null else doc.substring(selection.min, selection.max)

    fun cutSelection(): String? {
        val t = selectedText() ?: return null
        replaceRange(selection.min, selection.max, "", TextRange(selection.min))
        resyncIme()
        return t
    }

    // ---- line operations (keyboard + symbol-bar) ----

    /** The inclusive line range the current selection touches. A selection ending exactly at a line start
     *  doesn't include that trailing line (matching [indent]/[dedent]). */
    private fun selectedLineRange(): IntRange {
        val sel = selection
        val first = doc.lineForOffset(sel.min)
        var last = doc.lineForOffset(sel.max)
        if (last > first && sel.max == doc.lineStart(last)) last--
        return first..last
    }

    /**
     * Duplicate the selection, or — when the caret is collapsed — the whole current line. The copy lands
     * directly after the original: a collapsed caret moves to the same column on the new (lower) line; a
     * range selection re-selects the copy, so a repeated press keeps duplicating.
     */
    fun duplicateSelection() {
        val sel = selection
        if (sel.collapsed) {
            val line = doc.lineForOffset(sel.min)
            val ls = doc.lineStart(line)
            val le = doc.lineEnd(line)
            val lineText = doc.substring(ls, le)
            replaceRange(le, le, "\n" + lineText, TextRange(le + 1 + (sel.min - ls)))
        } else {
            val text = doc.substring(sel.min, sel.max)
            replaceRange(sel.max, sel.max, text, TextRange(sel.max, sel.max + text.length))
        }
        resyncIme()
    }

    /**
     * Move the line(s) the selection touches up ([dir] < 0) or down ([dir] > 0) by one, swapping with the
     * adjacent line and keeping the same text selected. A no-op at the corresponding buffer edge.
     */
    fun moveLines(dir: Int) {
        val range = selectedLineRange()
        val first = range.first
        val last = range.last
        if (dir < 0) {
            if (first == 0) return
            val prevStart = doc.lineStart(first - 1)
            val prevEnd = doc.lineEnd(first - 1)
            val prevText = doc.substring(prevStart, prevEnd)
            val blockText = doc.substring(doc.lineStart(first), doc.lineEnd(last))
            val delta = -((prevEnd - prevStart) + 1)
            replaceRange(
                prevStart, doc.lineEnd(last), blockText + "\n" + prevText,
                TextRange(selection.start + delta, selection.end + delta),
            )
        } else {
            if (last >= doc.lineCount - 1) return
            val blockStart = doc.lineStart(first)
            val blockText = doc.substring(blockStart, doc.lineEnd(last))
            val nextStart = doc.lineStart(last + 1)
            val nextEnd = doc.lineEnd(last + 1)
            val nextText = doc.substring(nextStart, nextEnd)
            val delta = (nextEnd - nextStart) + 1
            replaceRange(
                blockStart, nextEnd, nextText + "\n" + blockText,
                TextRange(selection.start + delta, selection.end + delta),
            )
        }
        resyncIme()
    }

    /** Delete the line(s) the selection touches, including the line break, leaving the caret at the start of
     *  the following line (or the new end of the buffer). */
    fun deleteLines() {
        val range = selectedLineRange()
        val first = range.first
        val last = range.last
        val start: Int
        val end: Int
        if (last < doc.lineCount - 1) {
            start = doc.lineStart(first)
            end = doc.lineStart(last + 1)
        } else {
            // Deleting through the last line: also drop the preceding newline so no blank tail line is left.
            start = if (first > 0) doc.lineEnd(first - 1) else doc.lineStart(first)
            end = doc.length
        }
        replaceRange(start, end, "", TextRange(start))
        resyncIme()
    }

    /** Join the next line up onto the caret's line (or fuse all lines a selection spans), collapsing each
     *  line break + the next line's leading indent into a single space. */
    fun joinLines() {
        val range = selectedLineRange()
        val first = range.first
        if (first >= doc.lineCount - 1) return // nothing below to join
        val joins = if (range.last > first) range.last - first else 1
        beginBatch()
        var caret = -1
        repeat(joins) {
            if (first >= doc.lineCount - 1) return@repeat
            val le = doc.lineEnd(first) // the '\n' ending the joined-onto line
            val nextEnd = doc.lineEnd(first + 1)
            var nextNonWs = doc.lineStart(first + 1)
            while (nextNonWs < nextEnd && (doc.charAt(nextNonWs) == ' ' || doc.charAt(nextNonWs) == '\t')) nextNonWs++
            // No separating space when the upper line is blank / already ends in space, or the lower line is blank.
            val upperBlank = le == doc.lineStart(first)
            val upperEndsSpace = le > 0 && (doc.charAt(le - 1) == ' ' || doc.charAt(le - 1) == '\t')
            val lowerBlank = nextNonWs == nextEnd
            val sep = if (upperBlank || upperEndsSpace || lowerBlank) "" else " "
            if (caret < 0) caret = le
            replaceRange(le, nextNonWs, sep, TextRange(le + sep.length))
        }
        updateSelectionAndComposing(TextRange(if (caret < 0) selection.min else caret), null)
        endBatch()
        resyncIme()
    }

    // ---- comment toggling ----

    private class CommentSyntax(val line: String?, val blockOpen: String?, val blockClose: String?)

    private fun commentSyntax(): CommentSyntax = when (language) {
        CodeLanguage.Java, CodeLanguage.Kotlin -> CommentSyntax("//", "/*", "*/")
        CodeLanguage.Xml -> CommentSyntax(null, "<!--", "-->")
        CodeLanguage.Proguard -> CommentSyntax("#", null, null)
        CodeLanguage.Plain -> CommentSyntax(null, null, null)
    }

    /** Toggle comments on the touched line(s): line comments by default, block comments when [preferBlock]
     *  (or when the language has no line comment, e.g. XML). Already-commented lines are uncommented. */
    fun toggleComment(preferBlock: Boolean = false) {
        val c = commentSyntax()
        when {
            !preferBlock && c.line != null -> toggleLineComment(c.line)
            c.blockOpen != null && c.blockClose != null -> toggleBlockComment(c.blockOpen, c.blockClose)
            c.line != null -> toggleLineComment(c.line)
        }
    }

    private fun firstNonWsOnLine(line: Int): Int {
        var i = doc.lineStart(line)
        val end = doc.lineEnd(line)
        while (i < end && (doc.charAt(i) == ' ' || doc.charAt(i) == '\t')) i++
        return i
    }

    private fun lineStartsWith(line: Int, token: String): Boolean {
        val at = firstNonWsOnLine(line)
        if (at + token.length > doc.lineEnd(line)) return false
        for (k in token.indices) if (doc.charAt(at + k) != token[k]) return false
        return true
    }

    private fun toggleLineComment(token: String) {
        val range = selectedLineRange()
        val first = range.first
        val last = range.last
        val wasCollapsed = selection.collapsed
        // Operate on non-blank lines; if the whole range is blank, comment the first line anyway.
        val nonBlank = (first..last).filter { firstNonWsOnLine(it) < doc.lineEnd(it) }
        val lines = if (nonBlank.isEmpty()) listOf(first) else nonBlank
        val allCommented = lines.all { lineStartsWith(it, token) }
        beginBatch()
        if (allCommented) {
            for (l in lines.sortedDescending()) {
                val at = firstNonWsOnLine(l)
                var removeEnd = at + token.length
                if (removeEnd < doc.lineEnd(l) && doc.charAt(removeEnd) == ' ') removeEnd++
                replaceRange(at, removeEnd, "", TextRange(at))
            }
        } else {
            // Insert at the common minimum indent column so the markers line up (IntelliJ-style).
            val col = lines.minOf { firstNonWsOnLine(it) - doc.lineStart(it) }
            for (l in lines.sortedDescending()) {
                val at = doc.lineStart(l) + col
                replaceRange(at, at, "$token ", TextRange(at))
            }
        }
        endBatch()
        if (wasCollapsed) updateSelectionAndComposing(TextRange(firstNonWsOnLine(first)), null)
        else updateSelectionAndComposing(TextRange(doc.lineStart(first), doc.lineEnd(last)), null)
        resyncIme()
    }

    private fun toggleBlockComment(open: String, close: String) {
        val sel = selection
        val start: Int
        val end: Int
        if (sel.collapsed) {
            val line = doc.lineForOffset(sel.min)
            start = firstNonWsOnLine(line)
            end = doc.lineEnd(line)
        } else {
            start = sel.min
            end = sel.max
        }
        val text = doc.substring(start, end)
        val inner = text.trim()
        val wrapped = inner.startsWith(open) && inner.endsWith(close) && inner.length >= open.length + close.length
        beginBatch()
        if (wrapped) {
            val oRel = text.indexOf(open)
            val cRel = text.lastIndexOf(close)
            // Remove the closer first (higher offset), each with one padding space if present.
            var cs = start + cRel
            val ce = cs + close.length
            if (cs > start && doc.charAt(cs - 1) == ' ') cs--
            replaceRange(cs, ce, "", TextRange(cs))
            val os = start + oRel
            var oe = os + open.length
            if (oe < doc.length && doc.charAt(oe) == ' ') oe++
            replaceRange(os, oe, "", TextRange(os))
            updateSelectionAndComposing(TextRange(os), null)
        } else {
            replaceRange(end, end, " $close", TextRange(end))
            replaceRange(start, start, "$open ", TextRange(start))
            updateSelectionAndComposing(TextRange(start, end + open.length + close.length + 2), null)
        }
        endBatch()
        resyncIme()
    }

    /**
     * Apply a completion accept: the main replacement plus optional additional edits (auto-import),
     * already expressed in pre-edit offsets, then land the caret at [finalSelection]. One notify.
     */
    fun applyEdits(edits: List<RangeEdit>, finalSelection: TextRange) {
        // A completion accepted while the IME was composing (the typed prefix is the composing region) replaces
        // that region programmatically. Clearing our own [composing] + pushing the new selection isn't enough:
        // the IME keeps its OWN composing buffer (the pre-accept prefix), so the next keystroke/Enter re-inserts
        // it. Force the IME to restart so it discards that buffer and re-reads the post-accept text/selection —
        // but only when a region was actually composing, to avoid an unnecessary restart on a plain edit
        // (auto-close bracket, block edit) where the IME state is already coherent.
        val wasComposing = composing != null
        beginBatch()
        composing = null
        for (e in edits.sortedByDescending { it.start }) {
            replaceRange(e.start, e.end, e.text, TextRange(e.text.length + e.start))
        }
        updateSelectionAndComposing(finalSelection, null)
        endBatch()
        if (wasComposing) imeListener?.onRestartInput()
    }

    // ---- IME bridge (called by the platform InputConnection) ----

    fun imeCommitText(text: String, newCursorPosition: Int) {
        // A bare newline commit (Enter on the soft keyboard, or an editor action) runs the editor's smart-Enter —
        // the same path the hardware Enter key takes — rather than pasting "\n" over the composing region (which
        // would drop the just-composed word). [commitText] clears any composition as it splices.
        if (text == "\n") {
            commitText("\n")
            return
        }
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
            val rev = textRevision
            backspace()
            // A smart rule that kept the text unchanged (backspace on an already-aligned closer) still looked
            // like a successful one-char delete to the IME, so its model is now off by one — resync it.
            if (textRevision == rev) resyncIme()
            return
        }
        // Deletion happens OUTSIDE the composing region as well as the selection (the framework contract:
        // deleteSurroundingText never touches the composing text), and the composition survives, shifted.
        beginBatch()
        val comp = composing
        val delBeforeEnd = min(selection.min, comp?.min ?: selection.min)
        val delAfterStart = max(selection.max, comp?.max ?: selection.max)
        val aEnd = (delAfterStart + after).coerceAtMost(doc.length)
        if (aEnd > delAfterStart) {
            replaceRange(delAfterStart, aEnd, "", TextRange(selection.start, selection.end), composing)
        }
        val bStart = (delBeforeEnd - before).coerceAtLeast(0)
        if (bStart < delBeforeEnd) {
            val del = delBeforeEnd - bStart
            val sel = selection
            val c = composing
            replaceRange(
                bStart, delBeforeEnd, "",
                TextRange(sel.start - del, sel.end - del),
                c?.let { TextRange(it.min - del, it.max - del) },
            )
        }
        endBatch()
    }

    fun imeSetSelection(start: Int, end: Int) {
        // Clamp before constructing: TextRange itself rejects negative offsets, and a misbehaving IME
        // sending them must not crash the editor.
        updateSelectionAndComposing(
            TextRange(start.coerceIn(0, doc.length), end.coerceIn(0, doc.length)), composing,
        )
    }

    fun imeTextBeforeCursor(n: Int): String {
        val end = selection.min
        return doc.substring((end - n.coerceIn(0, MAX_IPC_TEXT)).coerceAtLeast(0), end)
    }

    fun imeTextAfterCursor(n: Int): String {
        val start = selection.max
        return doc.substring(start, (start + n.coerceIn(0, MAX_IPC_TEXT)).coerceAtMost(doc.length))
    }

    // Clamped at 0: a large negative newCursorPosition near the document start must not produce a negative
    // offset (TextRange throws on those). The upper bound is coerced where the TextRange is applied.
    private fun imeCaret(regionStart: Int, insertedLen: Int, newCursorPosition: Int): Int =
        (if (newCursorPosition > 0) regionStart + insertedLen + newCursorPosition - 1
        else regionStart + newCursorPosition).coerceAtLeast(0)

    private companion object {
        /** Cap for IME text queries — never ship megabytes across the binder. */
        const val MAX_IPC_TEXT = 4096

        /** Undo-stack depth cap. With coalescing each step can be a whole run, so this is generous. */
        const val MAX_UNDO_STEPS = 300
    }
}

/** One reversible change: at [start], the [removed] text was replaced by [inserted]. Coalescing mutates these. */
private class UndoEdit(var start: Int, var removed: String, var inserted: String)

/** One undo unit: its edits in application order, plus the selection to restore before ([selBefore]) and after. */
private class UndoStep(val edits: MutableList<UndoEdit>, val selBefore: TextRange, var selAfter: TextRange)

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

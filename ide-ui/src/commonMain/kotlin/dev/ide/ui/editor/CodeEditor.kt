package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.editor.core.EditorImeHandle
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.editor.core.smartEnter
import dev.ide.ui.editor.core.textInputCodePoint
import dev.ide.ui.editor.core.wordRangeAt
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The code editor surface: the document is a line-indexed buffer ([EditorSession]/EditorDocument), a keystroke
 * re-tokenizes and re-shapes only the edited line, and rendering draws only the visible lines from a per-line
 * layout cache onto one canvas. Scrolling is two scroll offsets read in the draw phase, so a fling redraws
 * without recomposing; the soft keyboard talks straight to the session through a platform `InputConnection`.
 *
 * The surface is assembled from a few focused pieces so this composable stays under ART's per-method
 * instruction limit (see the note at the bottom of this file):
 * - [EditorRenderState] — text metrics, styles, the per-line render cache, gutter + fold-composite layouts.
 * - [EditorGeometry] — scroll offsets, the word-wrap projection, and viewport↔offset coordinate helpers.
 * - [EditorInteraction] — the caret glide animation and the touch/mouse selection chrome.
 * - [Modifier.editorInput] — the IME/focus/key/scroll/gesture plumbing.
 * - the overlay layers (diagnostics chips, selection toolbar, completion popup, lightbulb, …).
 *
 * Feature parity with the legacy editor: gutter with error/warning marks, current-line band, 2px accent caret,
 * bracket-match boxes, severity-colored squiggles, inline error chips, smart edits, and the live completion
 * popup. Plus touch selection handles + a floating Copy/Cut/Paste toolbar.
 */
@Composable
fun CodeEditor(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onNavigate: (path: String, offset: Int) -> Unit = { _, _ -> },
    onRenamed: (newPath: String?) -> Unit = {},
    /** Bump from the host (a toolbar Find button) to open the in-file find bar; 0 = no request. */
    findEpoch: Int = 0,
    /** Bump from the host (a toolbar/menu Reformat action) to reformat the whole file; 0 = no request. */
    formatEpoch: Int = 0,
    /** Editor text zoom; 1.0 = the theme's code size. Driven by pinch + Ctrl-+/-/0; hoisted so it persists across tabs. */
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    /** Tapped a `@Preview` gutter icon — the host switches to the Preview surface rendering this variant. */
    onPreview: (variantId: String) -> Unit = {},
    /** Whether typing auto-opens the completion popup (Settings → Completion); Ctrl-Space always works. */
    completionAutoPopup: Boolean = true,
    /** Debounce (ms) before an auto-popup completion request (Settings → Completion → Advanced). */
    completionDelayMs: Int = 110,
    /**
     * Scroll both axes at once with a single touch drag (Settings → Editor). Off = the classic
     * orientation-locked drag (one axis per gesture). Touch-only: desktop trackpad/wheel already pans 2D.
     */
    twoAxisScroll: Boolean = true,
    /** Whether a two-finger pinch zooms the code font (Settings → Editor); Ctrl-+/-/0 always works. */
    pinchZoom: Boolean = true,
    /**
     * Allow the soft keyboard's autocorrect / suggestions / auto-space (Settings → Editor → Keyboard). On by
     * default. Turning it OFF marks the IME field as raw code input. Android-only effect.
     */
    softKeyboardSuggestions: Boolean = true,
    /** Soft-wrap long lines at the viewport edge (Settings → Editor). Off = one row per line + h-scroll. */
    wordWrap: Boolean = false,
    /** Indent wrapped continuation rows to the line's own indent (Settings → Editor); only when [wordWrap]. */
    wrapIndent: Boolean = true,
    /** Render programming ligatures (`->`, `!=`, …) when the code font provides them (Settings → Editor; on). */
    fontLigatures: Boolean = true,
    /**
     * The editor is covered by an app-level overlay (the file-tree / build-console sheet on a phone, the command
     * palette, a destination sheet). The floating popups are separate Popup windows that would otherwise sit ON
     * TOP of that overlay, so they're torn down while obscured — even when the overlay doesn't steal focus.
     */
    obscured: Boolean = false,
) {
    // Source code is intrinsically left-to-right: the gutter sits at the left edge and lines flow right. On an
    // RTL system locale Compose flips `LocalLayoutDirection`, which would mirror the text shaping + popup
    // anchoring. Pin the whole editor subtree to LTR so it renders identically regardless of device language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        CodeEditorContent(
            path,
            session,
            backend,
            modifier,
            onSave,
            onNavigate,
            onRenamed,
            findEpoch,
            formatEpoch,
            fontScale,
            onFontScaleChange,
            onPreview,
            completionAutoPopup,
            completionDelayMs,
            twoAxisScroll,
            pinchZoom,
            softKeyboardSuggestions,
            wordWrap,
            wrapIndent,
            fontLigatures,
            obscured,
        )
    }
}

@Composable
private fun CodeEditorContent(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onNavigate: (path: String, offset: Int) -> Unit = { _, _ -> },
    onRenamed: (newPath: String?) -> Unit = {},
    findEpoch: Int = 0,
    formatEpoch: Int = 0,
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    onPreview: (variantId: String) -> Unit = {},
    completionAutoPopup: Boolean = true,
    completionDelayMs: Int = 110,
    twoAxisScroll: Boolean = true,
    pinchZoom: Boolean = true,
    softKeyboardSuggestions: Boolean = true,
    wordWrap: Boolean = false,
    wrapIndent: Boolean = true,
    fontLigatures: Boolean = true,
    obscured: Boolean = false,
) {
    val colors = Ca.colors
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The soft keyboard is raised only through this handle (on a deliberate tap) — never on focus alone.
    val editorIme = remember { EditorImeHandle() }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val editorSession = session
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val typography = Ca.type
    val zoom = clampFontScale(fontScale)
    val liveScale = rememberUpdatedState(zoom) // read inside the pinch gesture (pointerInput captures once)

    // ---- state holders: text metrics + render cache, viewport geometry, and per-tab interaction state ----
    val renderState = rememberEditorRenderState(session, measurer, density, colors, typography, zoom, fontLigatures)
    val geometry = rememberEditorGeometry(session, renderState, editorIme, wordWrap, wrapIndent)
    val interaction = rememberEditorInteraction(session, geometry, wordWrap)
    val metrics = renderState.metrics
    val gutterWidthPx = renderState.gutterWidthPx
    // Read the viewport so this composable re-subscribes to resize (keeps the geometry per-frame values fresh)
    // and so handleKey can size a Page Up/Down move.
    val viewport = geometry.viewport.value

    // ---- focus / caret blink ----
    // `isFocused` is deliberately un-keyed: it must persist across a tab switch, since the focusable node keeps
    // its real focus and `onFocusChanged` would not re-fire to restore it. `engaged` gates the floating popups.
    var isFocused by remember { mutableStateOf(false) }
    val engaged = isFocused && !obscured
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(editorSession.editCount, editorSession.selection.start, isFocused) {
        blinkOn = true // caret solid through every edit or cursor move; blink only at rest
        while (isFocused) {
            delay(530.milliseconds)
            blinkOn = !blinkOn
        }
    }

    // The editor pane's window bounds, so the completion popup can size the list to the room below the caret.
    var paneTopInWindow by remember(path) { mutableFloatStateOf(0f) }
    var paneBottomInWindow by remember(path) { mutableFloatStateOf(0f) }

    // ---- feature controllers (state + async behaviour live in the *Controller classes) ----
    val completion = rememberCompletionController(path, editorSession, backend)
    completion.autoPopupEnabled = completionAutoPopup
    completion.delayMs = completionDelayMs
    // Active snippet/template expansion (tab-stop stepping), or null. Reset when the file changes.
    var snippet by remember(path) { mutableStateOf<SnippetSession?>(null) }
    val sig = rememberSignatureHelpController(path, backend)
    val acts = rememberEditorActionsController(path, editorSession, backend) { completion.dismiss() }
    val find = rememberFindReplaceController(path, editorSession)
    var gotoLineOpen by remember(path) { mutableStateOf(false) }
    var quickDoc by remember(path) { mutableStateOf<UiQuickDoc?>(null) }
    var rename by remember(path) { mutableStateOf<RenameUiState?>(null) }
    var renameBusy by remember(path) { mutableStateOf(false) }
    var renameError by remember(path) { mutableStateOf<String?>(null) }

    fun showQuickDoc() {
        val caret = editorSession.selection.start
        val text = editorSession.doc.text
        scope.launch {
            quickDoc = runCatching { backend.editor.quickDocAt(path, text, caret) }.getOrNull()
        }
    }

    fun startRename() {
        if (renameBusy) return
        val text = editorSession.doc.text
        val caret = editorSession.selection.start
        scope.launch {
            val target = runCatching { backend.editor.prepareRename(path, text, caret) }.getOrNull()
            if (target != null) {
                renameError = null
                rename = RenameUiState(caret, target.oldName, target.kind, target.oldName)
            }
        }
    }

    fun commitRename() {
        val r = rename ?: return
        if (renameBusy || r.newName.isBlank() || r.newName == r.oldName) {
            rename = null; return
        }
        renameBusy = true; renameError = null
        val text = editorSession.doc.text
        scope.launch {
            val result = runCatching { backend.editor.rename(path, text, r.offset, r.newName) }
                .getOrElse { UiRenameResult(false, it.message ?: "Rename failed") }
            renameBusy = false
            if (result.success) {
                rename = null; onRenamed(result.newPath)
            } else renameError = result.message
        }
    }

    // Read the document + selection straight off the session (snapshot reads → this body recomposes on edit),
    // deriving everything from the rope's O(log N) access; the body never forces the full-text materialization.
    val doc = editorSession.doc
    val diagnostics = editorSession.diagnostics // the session owns and auto-shifts these; rendered here
    val docLength = doc.length
    val caretOffset = editorSession.selection.start.coerceIn(0, docLength)
    // Language-specific word chars (XML namespace `:` and resource-ref `@?+/.-`) so the popup survives them.
    val wordExtra = extraWordChars(path)
    val liveCompletion = completion.current?.takeIf { it.coversCaret(doc.chars, caretOffset, wordExtra) }
    val activePrefix = liveCompletion?.let { doc.substring(it.tokenStart, caretOffset) } ?: ""
    val displayed = liveCompletion?.filtered(activePrefix) ?: emptyList()
    val showPopup = !completion.dismissed && displayed.isNotEmpty()
    val safeSelected = completion.selected.coerceIn(0, (displayed.size - 1).coerceAtLeast(0))

    // Keep the popup WINDOW mounted across the 1-frame gaps a keystroke opens up (see CompletionController).
    val onToken = liveCompletion != null
    val hasItems = displayed.isNotEmpty()
    LaunchedEffect(completion.dismissed, onToken, hasItems) {
        completion.updatePopupVisibility(onToken, hasItems)
    }
    SideEffect {
        val live = liveCompletion
        if (live != null && hasItems) completion.snapshotShown(live.tokenStart, displayed, activePrefix)
    }

    // Apply completion [edits] but keep the viewport visually stationary when they insert line(s) ABOVE the
    // visible area — the off-screen auto-import case. We anchor on the completion line ([anchorLine], captured
    // pre-edit): if any edit lands above the first visible line, nudge the scroll by the anchor's visual-line
    // delta so the visible region stays put.
    fun applyEditsKeepingViewport(edits: List<RangeEdit>, finalSel: TextRange, anchorLine: Int) {
        val d = editorSession.doc
        val topLine = geometry.lineAtY(0f).coerceIn(0, (d.lineCount - 1).coerceAtLeast(0))
        val topVisibleStart = d.lineStart(topLine)
        val insertsAboveViewport = edits.any { it.start < topVisibleStart && '\n' in it.text }
        val visualBefore = editorSession.foldModel.visualForDocLine(anchorLine)
        editorSession.applyEdits(edits, finalSel)
        if (insertsAboveViewport) {
            val anchorAfter = editorSession.doc.lineForOffset(finalSel.min)
            val delta =
                (editorSession.foldModel.visualForDocLine(anchorAfter) - visualBefore) * metrics.lineHeight
            if (delta > 0f) geometry.vOffset.floatValue =
                (geometry.vOffset.floatValue + delta).coerceIn(0f, geometry.maxV())
        }
    }

    // Accept [picked], or — for the keyboard path — the currently-selected item. Callers that already know the
    // item (a click/tap on a row) MUST pass it: `safeSelected` is captured at composition time, so a same-frame
    // `selected = …; accept()` would still read the stale selection.
    fun accept(picked: UiCompletionItem? = null) {
        val s = liveCompletion ?: return
        val item = picked ?: displayed.getOrNull(safeSelected) ?: return
        val chars = editorSession.doc.chars
        val len = chars.length
        val mainStart = s.tokenStart.coerceIn(0, len)
        val anchorLine = editorSession.doc.lineForOffset(mainStart) // the completion line, for viewport stability
        // Replace the WHOLE identifier token under the caret, not just the typed prefix.
        var mainEnd = caretOffset.coerceIn(mainStart, len)
        while (mainEnd < len && isIdentifierChar(chars[mainEnd], wordExtra)) mainEnd++

        // If the item is exactly the token already present, the replace would be a no-op — append a space to
        // acknowledge it and advance the caret, unless something non-space already follows.
        val noOp = item.insertText == chars.subSequence(mainStart, mainEnd).toString() &&
            item.additionalEdits.isEmpty() && item.caret == null
        val nextIsSpace = mainEnd < len && chars[mainEnd].isWhitespace()
        val insert = if (noOp && !nextIsSpace) item.insertText + " " else item.insertText

        val edits = ArrayList<RangeEdit>()
        edits.add(RangeEdit(mainStart, mainEnd, insert, mainStart + insert.length))
        for (e in item.additionalEdits) {
            val st = e.start.coerceIn(0, len)
            edits.add(RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length))
        }
        // The text edit ends in an identifier char; without this the revision trigger would immediately reopen
        // the popup. Keep it closed until the user types again.
        completion.suppressNext()
        completion.noteAccepted(item) // acceptance-frequency ranking learns this pick
        val snip = item.snippet
        if (snip != null) {
            var base = mainStart
            for (e in item.additionalEdits) {
                val st = e.start.coerceIn(0, len)
                if (st <= mainStart) base += e.newText.length - (e.end.coerceIn(st, len) - st)
            }
            applyEditsKeepingViewport(edits, TextRange(base), anchorLine)
            snippet = SnippetSession.start(editorSession, base, snip)
            completion.dismiss()
            return
        }
        // caret lands inside the inserted text (the item decides); edits above shift it by their delta
        val within = (item.caret?.offset ?: insert.length).coerceIn(0, insert.length)
        var caret = mainStart + within
        for (e in item.additionalEdits) {
            val st = e.start.coerceIn(0, len)
            if (st <= mainStart) caret += e.newText.length - (e.end.coerceIn(st, len) - st)
        }
        // XML attribute-value completion: hop the caret past the existing closing quote (Android-Studio behavior).
        if (item.caret == null && wordExtra.isNotEmpty() && mainEnd < len && (chars[mainEnd] == '"' || chars[mainEnd] == '\'')) {
            caret += 1
        }
        val selLen = item.caret?.selectionLength ?: 0
        val sel = if (selLen > 0) TextRange(caret, caret + selLen) else TextRange(caret)
        applyEditsKeepingViewport(edits, sel, anchorLine)
        completion.dismiss()
    }

    // Smart Enter (Shift+Enter): finish the current line, then open an indented new line — IntelliJ's "Complete
    // Statement". The decision is a pure function ([smartEnter]); we just apply its edit.
    fun completeStatement() {
        completion.dismiss()
        val edit = smartEnter(editorSession.doc.chars, editorSession.selection.start, editorSession.language)
        editorSession.applyEdits(listOf(edit), TextRange(edit.caret))
    }

    // Reformat Code: ask the backend for the minimal edits to reformat the whole buffer, or just the selection
    // when `[rangeStart, rangeEnd)` is non-empty, then splice them in. The caret is kept on its logical spot and
    // the viewport stays anchored on the caret's line. A no-op returns nothing and does nothing.
    suspend fun runFormat(rangeStart: Int, rangeEnd: Int) {
        completion.dismiss()
        val text = editorSession.doc.text
        val caretBefore = editorSession.selection.start.coerceIn(0, editorSession.doc.length)
        val anchorLine = editorSession.doc.lineForOffset(caretBefore)
        val raw = runCatching {
            if (rangeEnd > rangeStart) backend.editor.formatRange(path, text, rangeStart, rangeEnd)
            else backend.editor.formatDocument(path, text)
        }.getOrNull().orEmpty()
        if (raw.isEmpty()) return
        val len = editorSession.doc.length
        val edits = raw.map { e ->
            val st = e.start.coerceIn(0, len)
            RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length)
        }
        var caret = caretBefore
        for (e in edits) if (e.start <= caret) caret += e.text.length - (e.end - e.start)
        applyEditsKeepingViewport(edits, TextRange(caret.coerceAtLeast(0)), anchorLine)
    }

    // keep the per-line render cache aligned with line splices (a render concern, owned by this surface)
    SideEffect {
        editorSession.onLinesShifted = { from, delta -> renderState.renderCache.shiftKeys(from, delta) }
        // Active template session re-anchors its tab stops on each edit (typing inside a placeholder).
        editorSession.onSnippetEdit = { span -> snippet?.onEdit(span) }
    }

    // completion triggering — fires only when the buffer's *text* actually advances (textRevision bumps on text
    // edits, never on caret moves). The baseline is captured at mount so switching to an already-edited tab
    // doesn't spuriously pop the popup.
    var lastSeenRev by remember(path) { mutableIntStateOf(editorSession.textRevision) }
    LaunchedEffect(editorSession.textRevision) {
        val rev = editorSession.textRevision
        if (rev == lastSeenRev) return@LaunchedEffect // mount / no real edit since the last handled one
        lastSeenRev = rev
        interaction.handlesVisible = false // typing puts the touch chrome away (Android convention)
        completion.selected = 0
        // This revision is accept()'s own edit; swallow it once so the popup stays closed until the next keystroke.
        if (completion.consumeSuppressedTrigger()) return@LaunchedEffect
        val d = editorSession.doc
        val caret = editorSession.selection.start
        val before = if (caret in 1..d.length) d.charAt(caret - 1) else null

        // Linked tag editing (XML): as the open tag's name is edited, rewrite its matching close tag to match.
        if (editorSession.language == CodeLanguage.Xml) {
            val sync = XmlEditing.linkedTagRenameEdit(d.chars, caret)
            if (sync != null) {
                editorSession.applyEdits(listOf(sync), TextRange(caret))
                return@LaunchedEffect
            }
        }

        when {
            // A new member-access context (`.`) always needs a fresh candidate set from the backend.
            before == '.' ->
                if (completion.autoPopupEnabled) completion.reopen() else completion.dismiss()
            // Extending an identifier: if the live popup session already covers this token with a complete,
            // locally-filterable set, the client-side filter narrows it instantly — so skip the backend re-query.
            before != null && isIdentifierChar(before, wordExtra) ->
                if (!canNarrowLocally(completion.current, completion.dismissed, d.chars, caret, wordExtra)) {
                    if (completion.autoPopupEnabled) completion.reopen() else completion.dismiss()
                }

            else -> completion.dismiss()
        }
    }

    // code-action availability — debounced on the selection + text revision, so the lightbulb appears when the
    // caret rests on (or selects) something actionable.
    LaunchedEffect(path, editorSession.selection, editorSession.textRevision) {
        acts.refreshAvailability(isFocused)
    }

    // Tear down the floating popups when the editor loses focus or is covered by an overlay.
    LaunchedEffect(engaged) {
        if (!engaged) {
            completion.dismiss()
            sig.dismiss()
            acts.closeMenu()
            acts.closeSheet()
        }
    }

    // signature help — re-resolve whenever the caret moves or the buffer changes (or Ctrl/Cmd-P bumps sigEpoch).
    LaunchedEffect(path, editorSession.textRevision, editorSession.selection, sig.epoch, isFocused) {
        sig.resolve(isFocused, editorSession)
    }

    LaunchedEffect(findEpoch) { if (findEpoch > 0) find.openBar(replace = false) }
    LaunchedEffect(formatEpoch) { if (formatEpoch > 0) runFormat(0, 0) }

    // recompute find matches when the query/options change or the buffer edits (debounced).
    LaunchedEffect(find.open, find.query, find.options, editorSession.textRevision) {
        if (find.open && find.query.isNotEmpty()) delay(120.milliseconds)
        find.recompute()
    }

    // Fetch the file's declarations (debounced) for sticky headers — kept off the keystroke path.
    LaunchedEffect(path, editorSession.textRevision) {
        delay(300.milliseconds)
        geometry.editorStructure.value =
            runCatching { backend.editor.fileStructure(path, editorSession.doc.text) }.getOrDefault(emptyList())
    }

    LaunchedEffect(path) { runCatching { focus.requestFocus() } }

    // ---- per-line diagnostic segments (recomputed per edit — O(diagnostics), they are few) ----
    // Keyed on the document *instance* (an edit swaps it; a caret move doesn't), so the key compare is O(1).
    val diagByLine = remember(diagnostics, doc) { mapDiagnosticsToLines(diagnostics, doc) }
    val bracketPair = remember(doc, editorSession.selection) {
        matchingBracket(doc.chars, editorSession.selection.start)
    }

    // ---- highlight occurrences of the identifier under the caret (textual, whole-word) ----
    // Suppressed while the find bar is open (find owns matches).
    val occurrenceWord = run {
        val sel = editorSession.selection
        if (find.open) null else {
            val r = wordRangeAt(doc.chars, sel.start)
            if (r.isEmpty()) null else {
                val w = doc.substring(r.first, r.last + 1)
                // Identifiers only; and when there IS a selection it must be exactly the word.
                val isIdent = w.isNotEmpty() && (w[0].isLetter() || w[0] == '_' || w[0] == '$')
                if (isIdent && (sel.collapsed || (sel.min == r.first && sel.max == r.last + 1))) w else null
            }
        }
    }
    val occurrences = remember(doc, occurrenceWord) {
        val w = occurrenceWord ?: return@remember emptyList<Match>()
        findMatches(doc.text, w, FindOptions(caseSensitive = true, wholeWord = true))
            .takeIf { it.size >= 2 } // only meaningful when it appears more than once
            ?: emptyList()
    }

    // ---- keyboard handling ----
    fun handleKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        quickDoc = null // any editing/navigation key dismisses the quick-doc popup
        val word = ev.isCtrlPressed || ev.isAltPressed
        val select = ev.isShiftPressed
        val shortcut = ev.isCtrlPressed || ev.isMetaPressed
        val pageLines = (viewport.height / metrics.lineHeight).toInt().coerceAtLeast(1)
        interaction.lastInputWasTouch = false
        when (ev.key) {
            Key.DirectionLeft -> {
                if (ev.isMetaPressed) editorSession.moveLineStart(select) else editorSession.moveHorizontal(-1, select, word)
                return true
            }

            Key.DirectionRight -> {
                if (ev.isMetaPressed) editorSession.moveLineEnd(select) else editorSession.moveHorizontal(1, select, word)
                return true
            }

            Key.DirectionUp -> {
                if (ev.isMetaPressed) editorSession.moveDocBoundary(-1, select) else editorSession.moveVertical(-1, select)
                return true
            }

            Key.DirectionDown -> {
                if (ev.isMetaPressed) editorSession.moveDocBoundary(1, select) else editorSession.moveVertical(1, select)
                return true
            }

            Key.MoveHome -> {
                if (ev.isCtrlPressed) editorSession.moveDocBoundary(-1, select) else editorSession.moveLineStart(select)
                return true
            }

            Key.MoveEnd -> {
                if (ev.isCtrlPressed) editorSession.moveDocBoundary(1, select) else editorSession.moveLineEnd(select)
                return true
            }

            Key.PageUp -> {
                editorSession.moveVertical(-pageLines, select); return true
            }

            Key.PageDown -> {
                editorSession.moveVertical(pageLines, select); return true
            }

            Key.Backspace -> {
                editorSession.backspace(word); return true
            }

            Key.Delete -> {
                editorSession.deleteForward(word); return true
            }

            Key.Enter, Key.NumPadEnter -> {
                // Shift+Enter = complete statement (IntelliJ's Smart Enter): finish the line then open a new one.
                if (ev.isShiftPressed && !shortcut && !ev.isAltPressed) completeStatement()
                else editorSession.commitText("\n")
                return true
            }

            Key.Tab -> {
                if (!shortcut && !ev.isAltPressed) {
                    if (ev.isShiftPressed) editorSession.dedent() else editorSession.indent()
                    return true
                }
                return false
            }
        }
        if (shortcut) {
            when (ev.key) {
                Key.A -> {
                    editorSession.selectAll(); return true
                }

                Key.C -> {
                    editorSession.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }; return true
                }

                Key.X -> {
                    editorSession.cutSelection()?.let { clipboard.setText(AnnotatedString(it)) }; return true
                }

                Key.V -> {
                    clipboard.getText()?.text?.let { if (it.isNotEmpty()) editorSession.commitText(it) }
                    return true
                }
                // Undo (⌘/Ctrl-Z), redo (⌘/Ctrl-Shift-Z or Ctrl-Y). Dismiss the popup/snippet first.
                Key.Z -> {
                    completion.dismiss(); snippet = null
                    if (ev.isShiftPressed) editorSession.redo() else editorSession.undo(); return true
                }

                Key.Y -> {
                    completion.dismiss(); snippet = null; editorSession.redo(); return true
                }
                // Zoom: ⌘/Ctrl with +/-/0 (mirrors the pinch gesture).
                Key.Equals, Key.Plus, Key.NumPadAdd -> {
                    onFontScaleChange(clampFontScale(fontScale * 1.1f)); return true
                }

                Key.Minus, Key.NumPadSubtract -> {
                    onFontScaleChange(clampFontScale(fontScale / 1.1f)); return true
                }

                Key.Zero -> {
                    onFontScaleChange(1f); return true
                }
            }
            return false
        }

        // Printable-character insert. Whether a key is text is decided per-platform (Android consults the native
        // KeyEvent; desktop mirrors the utf16CodePoint path). Returns the code point to insert, or -1.
        val cp = textInputCodePoint(ev)
        if (cp >= 0) {
            editorSession.commitText(codePointToString(cp))
            return true
        }
        return false
    }

    // Editor command chords, handled on the Preview pass so they win over the default key path + completion keys.
    fun onPreviewKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        // Reformat code (⌘/Ctrl-Alt-L, IntelliJ): the selection if any, else the whole file.
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.isAltPressed && ev.key == Key.L) {
            val sel = editorSession.selection
            scope.launch { if (!sel.collapsed) runFormat(sel.min, sel.max) else runFormat(0, 0) }
            return true
        }
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.S) {
            // Reformat-on-save (Settings → Code Style) reformats first, then saves the result.
            if (runCatching { backend.settings.settings().formatOnSave }.getOrDefault(false)) {
                scope.launch { runFormat(0, 0); onSave() }
            } else onSave()
            return true
        }
        // Find (⌘/Ctrl-F) / find+replace (⌘/Ctrl-R); seed the query from the current selection.
        if ((ev.isCtrlPressed || ev.isMetaPressed) && (ev.key == Key.F || ev.key == Key.R)) {
            val seed = editorSession.selectedText()?.takeIf { it.isNotEmpty() && '\n' !in it }
            find.openBar(replace = ev.key == Key.R, seed = seed)
            completion.dismiss()
            return true
        }
        // Go to line (⌘/Ctrl-G): open the line-jump prompt.
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.G) {
            gotoLineOpen = true; completion.dismiss(); return true
        }
        // Quick documentation (⌘/Ctrl-Q, IntelliJ); Esc dismisses an open doc popup first.
        if (quickDoc != null && ev.key == Key.Escape) {
            quickDoc = null; return true
        }
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Q) {
            showQuickDoc(); completion.dismiss(); return true
        }
        // Go to definition (⌘/Ctrl-B): resolve the resource/symbol at the caret and jump to it.
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.B) {
            val text = editorSession.doc.text
            val caret = editorSession.selection.start
            scope.launch {
                runCatching { backend.editor.definitionAt(path, text, caret) }.getOrNull()
                    ?.let { onNavigate(it.path, it.offset) }
            }
            return true
        }
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Spacebar) {
            completion.reopen(immediate = true); return true
        }
        // Parameter info (Ctrl/Cmd-P): force the signature-help panel even if it was dismissed.
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.P) {
            sig.triggerExplicit(); return true
        }
        // Rename (F2, or Shift-F6 a la IntelliJ): prompt for a new name → project-wide rename.
        if (ev.key == Key.F2 || (ev.isShiftPressed && ev.key == Key.F6)) {
            startRename(); return true
        }
        // Next / previous diagnostic (F8 / Shift-F8, a la VS Code), wrapping around the buffer.
        if (ev.key == Key.F8) {
            editorSession.goToDiagnostic(forward = !ev.isShiftPressed); return true
        }
        // Comment toggle: ⌘/Ctrl-/ (line), +Shift (block).
        if ((ev.isCtrlPressed || ev.isMetaPressed) && (ev.key == Key.Slash)) {
            editorSession.toggleComment(preferBlock = ev.isShiftPressed)
            completion.dismiss(); return true
        }
        // Duplicate line/selection: ⌘/Ctrl-D.
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.D && !ev.isShiftPressed && !ev.isAltPressed) {
            editorSession.duplicateSelection(); return true
        }
        // Delete line(s): ⌘/Ctrl-Shift-K. Join lines: ⌘/Ctrl-Shift-J.
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.isShiftPressed && ev.key == Key.K) {
            editorSession.deleteLines(); completion.dismiss(); return true
        }
        if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.isShiftPressed && ev.key == Key.J) {
            editorSession.joinLines(); return true
        }
        // Move line(s) up/down: Alt-Shift-Up/Down (intercepted before the default vertical move).
        if (ev.isAltPressed && ev.isShiftPressed && (ev.key == Key.DirectionUp || ev.key == Key.DirectionDown)) {
            editorSession.moveLines(if (ev.key == Key.DirectionUp) -1 else 1)
            return true
        }
        // Code actions: Alt+Enter (or Ctrl/Cmd-.) opens the lightbulb menu.
        if ((ev.isAltPressed && ev.key == Key.Enter) || ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Period)) {
            if (acts.available.isNotEmpty()) acts.openMenu()
            return true
        }
        if (acts.menuOpen) {
            return when (ev.key) {
                Key.Escape -> {
                    acts.closeMenu(); true
                }

                Key.DirectionDown -> {
                    acts.moveSelection(1); true
                }

                Key.DirectionUp -> {
                    acts.moveSelection(-1); true
                }

                Key.Enter, Key.Tab -> {
                    acts.applyAt(acts.menuSelected); true
                }

                else -> false
            }
        }
        // Active template (snippet) session steers Tab/Shift-Tab/Escape when the completion popup isn't up.
        val sn = snippet
        if (sn != null && !showPopup) {
            when (ev.key) {
                Key.Tab -> {
                    if (ev.isShiftPressed) sn.prev() else if (!sn.next()) snippet = null
                    return true
                }

                Key.Escape -> {
                    sn.finish(); snippet = null; return true
                }

                else -> Unit
            }
        }
        // Esc closes the (informational) signature-help panel when no completion popup is open.
        if (sig.help != null && !sig.dismissed && !showPopup && ev.key == Key.Escape) {
            sig.dismiss(); return true
        }
        if (!showPopup) return false
        return when (ev.key) {
            Key.Escape -> {
                completion.dismiss(); true
            }

            Key.DirectionDown -> {
                completion.selected = (safeSelected + 1).coerceAtMost((displayed.size - 1).coerceAtLeast(0)); true
            }

            Key.DirectionUp -> {
                completion.selected = (safeSelected - 1).coerceAtLeast(0); true
            }

            Key.Tab, Key.Enter -> {
                accept(); true
            }

            else -> false
        }
    }

    Box(
        modifier
            .background(colors.editorBg)
            .clipToBounds()
            .onGloballyPositioned {
                paneTopInWindow = it.positionInWindow().y
                paneBottomInWindow = it.positionInWindow().y + it.size.height
            },
    ) {
        Box(
            Modifier
                .editorInput(
                    session = editorSession,
                    geometry = geometry,
                    interaction = interaction,
                    acts = acts,
                    completion = completion,
                    focus = focus,
                    editorIme = editorIme,
                    scope = scope,
                    softKeyboardSuggestions = softKeyboardSuggestions,
                    metrics = metrics,
                    gutterWidthPx = gutterWidthPx,
                    wrapActive = geometry.wrapActive,
                    pinchZoom = pinchZoom,
                    liveScale = liveScale,
                    onFontScaleChange = onFontScaleChange,
                    useTwoAxisScroll = twoAxisScroll && isMobilePlatform,
                    scroll2D = geometry.scroll2D,
                    vScroll = geometry.vScroll,
                    hScroll = geometry.hScroll,
                    onViewportSize = { geometry.viewport.value = it },
                    onContentPositioned = { geometry.contentInWindow.value = it },
                    onFocusedChange = { isFocused = it },
                    onDismissQuickDoc = { quickDoc = null },
                    onPreviewKey = ::onPreviewKey,
                    onKey = ::handleKey,
                )
                .drawBehind {
                    drawEditor(
                        session = editorSession,
                        metrics = metrics,
                        gutterWidth = gutterWidthPx,
                        vOff = geometry.vOffset.floatValue,
                        hOff = geometry.hOffset.floatValue,
                        layoutFor = renderState::layoutFor,
                        compositeLayoutFor = renderState::compositeLayoutFor,
                        rawToVisual = renderState.renderCache::rawToVisual,
                        foldModel = editorSession.foldModel,
                        vlayout = geometry.vlayout,
                        wrap = geometry.wrapActive,
                        foldableStartLines = renderState.foldableStartLines,
                        foldStripWidth = renderState.foldStripPx,
                        hoveredLine = interaction.hoveredLine,
                        numberLayout = renderState::numberLayout,
                        diagByLine = diagByLine,
                        bracketPair = bracketPair,
                        findMatches = if (find.open) find.matches else emptyList(),
                        currentMatch = find.currentIndex,
                        occurrences = occurrences,
                        structure = geometry.editorStructure.value,
                        colors = EditorDrawColors(
                            background = colors.editorBg,
                            currentLine = colors.currentLine,
                            caret = colors.accent,
                            selection = colors.accent.copy(alpha = 0.30f),
                            gutterText = colors.gutterText,
                            gutterCurrent = colors.textSecondary,
                            gutterBorder = colors.separator,
                            error = colors.error,
                            warning = colors.warning,
                            info = colors.info,
                            muted = colors.textTertiary,
                            composing = colors.textSecondary,
                            indentGuide = colors.hairline,
                            findMatch = colors.warning.copy(alpha = 0.28f),
                            findCurrent = colors.accent.copy(alpha = 0.5f),
                            occurrence = colors.textSecondary.copy(alpha = 0.18f),
                        ),
                        caretVisible = isFocused && (blinkOn || !editorSession.selection.collapsed),
                        caretContent = interaction.caretContent, // animated, content-space; read here → redraw per frame
                        handlesVisible = interaction.handlesVisible && interaction.lastInputWasTouch,
                        handleColor = colors.accent,
                    )
                },
        )

        DiagnosticChipsLayer(
            session = editorSession,
            render = renderState,
            diagnostics = diagnostics,
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
            wordWrap = wordWrap,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            hOffset = geometry.hOffset,
            onOpenSheet = { acts.openSheet(it) },
        )

        SelectionToolbarLayer(
            session = editorSession,
            geometry = geometry,
            interaction = interaction,
            acts = acts,
            onDocs = { showQuickDoc() },
        )

        // completion popup, anchored at the token start (extracted so ART can compile these emission blocks).
        CompletionPopupLayer(
            completion = completion,
            engaged = engaged,
            docLength = docLength,
            caretGeometry = { geometry.caretGeometry(it) },
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
            paneTopInWindow = paneTopInWindow,
            paneBottomInWindow = paneBottomInWindow,
            safeSelected = safeSelected,
            onAccept = { accept(it) },
        )

        SignatureHelpLayer(
            sig = sig,
            engaged = engaged,
            caretOffset = caretOffset,
            caretGeometry = { geometry.caretGeometry(it) },
            gutterWidthPx = gutterWidthPx,
        )
        LightbulbLayer(
            acts = acts,
            showPopup = showPopup,
            engaged = engaged,
            caretOffset = caretOffset,
            caretGeometry = { geometry.caretGeometry(it) },
            gutterWidthPx = gutterWidthPx,
        )

        PreviewGutterIconsLayer(
            session = editorSession,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            docLength = docLength,
            onPreview = onPreview,
        )

        CodeActionsMenuLayer(
            acts = acts,
            engaged = engaged,
            caretOffset = caretOffset,
            caretGeometry = { geometry.caretGeometry(it) },
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
        )

        // go-to-line prompt — a small centered card; jumps the caret (the into-view effect scrolls to it)
        if (gotoLineOpen) {
            GoToLinePopup(
                lineCount = editorSession.doc.lineCount,
                onGo = { line, col ->
                    val d = editorSession.doc
                    val l = (line - 1).coerceIn(0, d.lineCount - 1)
                    val off = (d.lineStart(l) + (col - 1).coerceAtLeast(0)).coerceAtMost(d.lineEnd(l))
                    editorSession.setCaret(off)
                    gotoLineOpen = false
                    runCatching { focus.requestFocus() }
                },
                onCancel = { gotoLineOpen = false; runCatching { focus.requestFocus() } },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // quick-documentation popup — a floating card; dismissed by Esc, a tap, or a navigation/edit key.
        quickDoc?.let { QuickDocPopup(it, Modifier.align(Alignment.TopCenter)) }

        // rename prompt — a small centered card over the editor
        rename?.let { r ->
            RenamePopup(
                state = r,
                busy = renameBusy,
                error = renameError,
                onChange = { rename = r.copy(newName = it) },
                onCommit = { commitRename() },
                onCancel = {
                    if (!renameBusy) {
                        rename = null; renameError = null
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // diagnostic sheet — full (scrollable) message + that diagnostic's fixes, docked at the pane bottom
        if (engaged) acts.sheet?.let { d ->
            DiagnosticSheet(
                severity = d.severity,
                unused = d.unused,
                message = d.message,
                actions = acts.sheetActions,
                onPick = { acts.applySheetFix(it) },
                onDismiss = { acts.closeSheet() },
            )
        }

        // find / replace bar, docked at the top of the editor
        if (find.open) {
            FindReplaceBar(
                query = find.query,
                replace = find.replaceWith,
                replaceMode = find.replaceMode,
                options = find.options,
                matchCount = find.matches.size,
                currentIndex = if (find.matches.isEmpty()) -1 else find.currentIndex,
                regexError = find.regexError,
                onQueryChange = { find.query = it },
                onReplaceChange = { find.replaceWith = it },
                onToggleReplaceMode = { find.replaceMode = !find.replaceMode },
                onOptionsChange = { find.options = it },
                onPrev = { find.goto(find.currentIndex - 1) },
                onNext = { find.goto(find.currentIndex + 1) },
                onReplaceOne = { find.replaceCurrent() },
                onReplaceAll = { find.replaceAll() },
                onClose = { find.open = false; runCatching { focus.requestFocus() } },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

// The caret-anchored popup emission blocks, extracted from CodeEditorContent so each compiles as its own
// method. CodeEditorContent was over ART's per-method JIT/AOT instruction limit (~17k), so on device it stayed
// interpreted — every recomposition ran slow. Splitting the emission blocks out lets ART compile them. Behaviour
// is identical: each is called at the same site with the same state; the anchor geometry is read at composition
// time via the [caretGeometry] lambda (no deferred layout-phase reads), so recomposition triggers are unchanged.

/** Completion popup, anchored at the token start below the caret line. Mounted on `popupVisible` (the keep-
 *  alive latch) and rendered from `shown` (the last good state) so a keystroke's transient session swap /
 *  filter miss doesn't blink the window shut. */
@Composable
private fun CompletionPopupLayer(
    completion: CompletionController,
    engaged: Boolean,
    docLength: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    paneTopInWindow: Float,
    paneBottomInWindow: Float,
    safeSelected: Int,
    onAccept: (UiCompletionItem?) -> Unit,
) {
    val shown = completion.shown
    if (completion.popupVisible && shown != null && engaged) {
        val density = LocalDensity.current
        val anchor = shown.tokenStart.coerceIn(0, docLength)
        val (_, anchorX, anchorTop) = caretGeometry(anchor)
        val lineBottomPx = anchorTop + metrics.lineHeight
        val gapPx = with(density) { 6.dp.roundToPx() }
        val marginPx = with(density) { 8.dp.roundToPx() }
        val positionProvider = remember(anchorX, lineBottomPx, gapPx, marginPx) {
            CompletionPopupPositionProvider(
                anchorX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                lineBottomPx.roundToInt(),
                gapPx,
                marginPx,
            )
        }
        // room between the caret line and the pane bottom (which already sits above the keyboard)
        val caretBottomY = paneTopInWindow + lineBottomPx
        val roomBelowDp = with(density) { (paneBottomInWindow - caretBottomY - gapPx - marginPx).toDp() }

        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { completion.dismiss() },
            // Non-focusable (typing must reach the editor); click-outside dismissal disabled because every
            // soft-keyboard tap is a touch outside the popup window and would otherwise blink it shut.
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
        ) {
            BoxWithConstraints {
                val compact = maxWidth < 600.dp
                val popupWidth = if (compact) (maxWidth * 0.85f).coerceIn(240.dp, 340.dp) else 440.dp
                // Fill the room below the caret so the list auto-expands. Bounded by a generous ceiling.
                val listMax = roomBelowDp.coerceIn(MinListHeight, MaxListHeight)
                val items = shown.items
                CompletionList(
                    items = items,
                    selectedIndex = safeSelected.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                    prefix = shown.prefix,
                    width = popupWidth,
                    maxListHeight = listMax,
                    docsBeside = !compact,
                    onPick = { item ->
                        completion.selected = items.indexOf(item).coerceAtLeast(0)
                        onAccept(item) // accept the tapped row, not the (stale) currently-selected index
                    },
                    onHover = { completion.selected = it },
                )
            }
        }
    }
}

/** Signature-help (parameter-info) panel, floated ABOVE the caret line, independent of the completion popup. */
@Composable
private fun SignatureHelpLayer(
    sig: SignatureHelpController,
    engaged: Boolean,
    caretOffset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    gutterWidthPx: Float,
) {
    val sigHelp = sig.help
    if (sigHelp != null && !sig.dismissed && engaged && sigHelp.signatures.isNotEmpty()) {
        val density = LocalDensity.current
        val (_, sigX, sigTop) = caretGeometry(caretOffset)
        val gapPx = with(density) { 6.dp.roundToPx() }
        val positionProvider = remember(sigX, sigTop, gapPx) {
            AboveAnchorPositionProvider(
                sigX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                sigTop.roundToInt(),
                gapPx,
            )
        }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { sig.dismiss() },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
        ) {
            SignatureHelpPopup(sigHelp, mobile = isMobilePlatform)
        }
    }
}

/** Lightbulb floating just ABOVE the caret — only when the caret is on a diagnostic that has fixes, the
 *  completion popup isn't showing, and the fix menu isn't already open. Tap → the fix list. */
@Composable
private fun LightbulbLayer(
    acts: EditorActionsController,
    showPopup: Boolean,
    engaged: Boolean,
    caretOffset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    gutterWidthPx: Float,
) {
    if (acts.available.isNotEmpty() && acts.caretDiagnostic != null && !showPopup && !acts.menuOpen && engaged) {
        val density = LocalDensity.current
        val (_, bulbX, bulbTop) = caretGeometry(caretOffset)
        val gapPx = with(density) { 6.dp.roundToPx() }
        val positionProvider = remember(bulbX, bulbTop, gapPx) {
            AboveAnchorPositionProvider(
                bulbX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                bulbTop.roundToInt(),
                gapPx,
            )
        }
        Popup(popupPositionProvider = positionProvider) {
            FloatingLightbulb(onClick = { acts.openMenu() })
        }
    }
}

/** Code-actions menu, anchored below the caret line (same position machinery as the completion popup). */
@Composable
private fun CodeActionsMenuLayer(
    acts: EditorActionsController,
    engaged: Boolean,
    caretOffset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
) {
    if (acts.menuOpen && acts.available.isNotEmpty() && engaged) {
        val density = LocalDensity.current
        val (_, anchorX, anchorTop) = caretGeometry(caretOffset)
        val lineBottomPx = anchorTop + metrics.lineHeight
        val gapPx = with(density) { 6.dp.roundToPx() }
        val marginPx = with(density) { 8.dp.roundToPx() }
        val positionProvider = remember(anchorX, lineBottomPx, gapPx, marginPx) {
            CompletionPopupPositionProvider(
                anchorX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                lineBottomPx.roundToInt(),
                gapPx,
                marginPx,
            )
        }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { acts.closeMenu() },
        ) {
            BoxWithConstraints {
                val compact = maxWidth < 600.dp
                val popupWidth = if (compact) (maxWidth * 0.9f).coerceIn(240.dp, 340.dp) else 360.dp
                CodeActionsMenu(
                    actions = acts.available,
                    selectedIndex = acts.menuSelected.coerceIn(0, (acts.available.size - 1).coerceAtLeast(0)),
                    width = popupWidth,
                    onPick = { acts.applyAt(it) },
                )
            }
        }
    }
}

package dev.ide.ui.editor

import dev.ide.ui.editor.core.MergeConflict
import dev.ide.ui.backend.UiFeatures
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import dev.ide.ui.backend.UiDiagnostic
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import dev.ide.ui.platform.PlatformBackHandler
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.IntState
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ide.ui.LtrContent
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiMenuGroup
import dev.ide.ui.backend.UiActionItem
import dev.ide.ui.backend.UiNavKind
import dev.ide.ui.backend.UiNavOption
import dev.ide.ui.backend.UiNavTarget
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.clipForClipboard
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.MEASURER_CACHE_ENTRIES
import dev.ide.ui.ext.AppCommandHost
import dev.ide.ui.ext.KeymapHost
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.editor.core.isLarge
import dev.ide.ui.editor.core.mapOffsetThroughEdits
import dev.ide.ui.editor.core.smartEnter
import dev.ide.ui.editor.core.textInputCodePoint
import dev.ide.ui.editor.core.wordRangeAt
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.rename_failed
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material3.MaterialTheme
import dev.ide.ui.theme.Season
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import dev.ide.ui.backend.UiTextEdit

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
    /** Bump from the host (the Optimize Imports menu action) to reorganize imports; 0 = no request. */
    optimizeImportsEpoch: Int = 0,
    /** Bump from the host (an app shortcut that returns to the editor) to take keyboard focus; 0 = no request. */
    focusEpoch: Int = 0,
    /** Editor text zoom; 1.0 = the theme's code size. Driven by pinch + Ctrl-+/-/0; hoisted so it persists across tabs. */
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    /** Tapped a `@Preview` gutter icon — the host switches to the Preview surface rendering this variant. */
    onPreview: (variantId: String) -> Unit = {},
    /**
     * Invoke a plugin editor action (one contributed to the `EDITOR` place) by its registry id, for the
     * selection it was listed at. The host routes it through the shared action dispatcher so its effects
     * apply exactly as they would from the toolbar or palette. Left at the default no-op, plugin editor
     * actions still list but do nothing, so the host must wire this for them to work.
     */
    onEditorAction: suspend (actionId: String, selStart: Int, selEnd: Int) -> Unit = { _, _, _ -> },
    /** Applies a code action's edits to files other than this one; see [rememberEditorActionsController]. */
    onOtherFileEdits: suspend (Map<String, List<UiTextEdit>>) -> Unit = { },
    /** Whether typing auto-opens the completion popup (Settings → Completion); Ctrl-Space always works. */
    completionAutoPopup: Boolean = true,
    /** Debounce (ms) before an auto-popup completion request (Settings → Completion → Advanced). */
    completionDelayMs: Int = 110,
    /**
     * Scroll both axes at once with a single touch drag (Settings → Editor). Off = the classic
     * orientation-locked drag (one axis per gesture). Touch-only, hence the default: `scrollable2D` has no
     * mouse-wheel handling, so a desktop editor must stay on the orientation-locked pair. The host ANDs the
     * setting with the platform; passing it explicitly is what lets a test drive the touch path.
     */
    twoAxisScroll: Boolean = isMobilePlatform,
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
    /** Show a draggable bar along the bottom edge while a line runs past the view (Settings → Editor). */
    horizontalScrollbar: Boolean = true,
    /** Show who last changed the caret's line after its end, in a Git project (Settings → Editor; on). */
    inlineBlame: Boolean = true,
    /** Render programming ligatures (`->`, `!=`, …) when the code font provides them (Settings → Editor; on). */
    fontLigatures: Boolean = true,
    /** How the caret is drawn (Settings → Editor → Caret style). */
    caretStyle: CaretStyle = CaretStyle.Line,
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
    LtrContent {
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
            optimizeImportsEpoch,
            focusEpoch,
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
            horizontalScrollbar,
            inlineBlame,
            fontLigatures,
            caretStyle,
            obscured,
            onEditorAction,
            onOtherFileEdits,
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
    optimizeImportsEpoch: Int = 0,
    focusEpoch: Int = 0,
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    onPreview: (variantId: String) -> Unit = {},
    completionAutoPopup: Boolean = true,
    completionDelayMs: Int = 110,
    twoAxisScroll: Boolean = isMobilePlatform,
    pinchZoom: Boolean = true,
    softKeyboardSuggestions: Boolean = true,
    wordWrap: Boolean = false,
    wrapIndent: Boolean = true,
    horizontalScrollbar: Boolean = true,
    inlineBlame: Boolean = true,
    fontLigatures: Boolean = true,
    caretStyle: CaretStyle = CaretStyle.Line,
    obscured: Boolean = false,
    onEditorAction: suspend (actionId: String, selStart: Int, selEnd: Int) -> Unit = { _, _, _ -> },
    /** Applies a code action's edits to files other than this one; see [rememberEditorActionsController]. */
    onOtherFileEdits: suspend (Map<String, List<UiTextEdit>>) -> Unit = { },
) {
    val colors = Ca.colors
    val editorColors = Ide.editorColors
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The soft keyboard is raised only through this handle (on a deliberate tap) — never on focus alone.
    // It belongs to the SESSION, not to this composable: the accessory bar outside the editor dismisses the
    // keyboard through the same handle, and a tab switch must not leave the bar holding a stale one.
    val editorIme = session.ime
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val editorSession = session
    val measurer = rememberTextMeasurer(cacheSize = MEASURER_CACHE_ENTRIES)
    val typography = Ca.type
    val zoom = clampFontScale(fontScale)
    val liveScale = rememberUpdatedState(zoom) // read inside the pinch gesture (pointerInput captures once)

    // ---- state holders: text metrics + render cache, viewport geometry, and per-tab interaction state ----
    val renderState =
        rememberEditorRenderState(session, measurer, density, colors, editorColors, typography, zoom, fontLigatures)
    val geometry = rememberEditorGeometry(session, renderState, editorIme, wordWrap, wrapIndent)
    // Publish the visible lines for viewport-first work. Read in a snapshot flow, not in composition, so a
    // scroll frame does not recompose the editor.
    LaunchedEffect(session, geometry) {
        snapshotFlow { geometry.visibleLineRange() }.collect { session.viewportLines = it }
    }
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
    // Where the code area starts below the sticky headers, as the editor's last draw left it for the caret's.
    val caretClip = remember { CaretClip() }
    LaunchedEffect(editorSession.editCount, editorSession.selection.start, isFocused) {
        blinkOn = true // caret solid through every edit or cursor move; blink only at rest
        while (isFocused) {
            delay(530.milliseconds)
            blinkOn = !blinkOn
        }
    }
    // The candle caret's clock. Ticked at ~16 fps rather than every frame: a flame only has to look alive, and
    // this redraws just the caret layer. Stops whenever the editor is unfocused or another style is chosen.
    val flameTime = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(caretStyle, isFocused) {
        if (caretStyle != CaretStyle.Candle || !isFocused) return@LaunchedEffect
        while (true) {
            delay(60.milliseconds)
            flameTime.floatValue += 0.06f
        }
    }
    // Embers the candle throws off on each edit, on the same clock (so they only move while the flame does).
    val embers = remember { EmberField() }
    val lastEmberEdit = remember { intArrayOf(editorSession.editCount) }
    LaunchedEffect(editorSession.editCount) {
        val edits = editorSession.editCount
        if (edits == lastEmberEdit[0]) return@LaunchedEffect
        lastEmberEdit[0] = edits
        if (caretStyle != CaretStyle.Candle || !isFocused) return@LaunchedEffect
        // The flame's tip sits about a fifth of a line above the caret's line top (see drawCandle).
        val caret = interaction.caretContent
        embers.spawn(caret.x, caret.y - metrics.lineHeight * 0.15f, flameTime.floatValue)
    }
    // The Halloween overlays (cobweb, ghost, slime drips) only during the season, with Seasonal touches on.
    val halloween = LocalSeason.current == Season.Halloween


    // ---- feature controllers (state + async behaviour live in the *Controller classes) ----
    // A read-only buffer (a decompiled / library-source view) never mutates, so completion has nothing to
    // commit into — suppress the auto-popup so it doesn't hover over inert text.
    val readOnly = !editorSession.editable
    // The three-finger undo/redo gesture's confirmation label.
    val undoFeedback = remember(editorSession) { UndoGestureFeedback() }
    // Past the large-file threshold the memory-heavy features are suppressed (the daemon passes are gated in
    // EditorDaemonEffect; completion, the whole-file occurrence scan, and the structure fetch are gated below)
    // so a big file doesn't exhaust the heap on a low-RAM device. Syntax highlighting and editing stay on.
    val largeFile = editorSession.doc.isLarge()
    val completion = rememberCompletionController(path, editorSession, backend)
    completion.autoPopupEnabled = completionAutoPopup && !readOnly && !largeFile
    completion.delayMs = completionDelayMs
    // Active snippet/template expansion (tab-stop stepping), or null. Reset when the file changes.
    var snippet by remember(path) { mutableStateOf<SnippetSession?>(null) }
    val sig = rememberSignatureHelpController(path, backend)
    // The plugin painters and layers for this file, resolved in composition (both registries are
    // Compose-observable) so the draw phase and the overlay Box each read one already-filtered list.
    val painters = rememberEditorPainters(path)
    val acts = rememberEditorActionsController(
        path, editorSession, backend, onEditorAction, onOtherFileEdits,
    ) { completion.dismiss() }
    // Animate the "expand selection" gesture: the logical selection snaps to the enclosing node immediately,
    // while the DRAWN highlight grows from the previous range to it over a short tween (1f = settled). Keyed on
    // the request token so each expansion (and each step of a chain) restarts the growth from the new base.
    val expandProgress = remember { Animatable(1f) }
    val expandAnimTokenState = remember { mutableIntStateOf(-1) }
    var expandAnimToken by expandAnimTokenState
    LaunchedEffect(acts.selectionExpand?.token) {
        val e = acts.selectionExpand ?: return@LaunchedEffect
        expandAnimToken = e.token // mark this request's animation as started (drawBehind treats not-yet-started as 0f)
        expandProgress.snapTo(0f)
        expandProgress.animateTo(1f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
    }
    val find = rememberFindReplaceController(path, editorSession)
    // The prompts, menus and popups over the editor (go-to-line, quick doc, rename, Go to menu, context menu,
    // hover problem popup). Held together so [EditorDialogsLayer] can render them; the locals below keep their
    // names for the code here that opens and closes them.
    val popups = remember(path) { EditorPopupState() }
    var gotoLineOpen by popups::gotoLineOpen
    var quickDoc by popups::quickDoc
    // Paste from History (Ctrl/⌘-Shift-V, the caret toolbar's clock, the symbol bar's Clipboard key).
    var pasteHistoryOpen by remember(editorSession) { mutableStateOf(false) }
    val seenPasteRequests = remember(editorSession) { intArrayOf(editorSession.pasteHistoryRequests) }
    LaunchedEffect(editorSession.pasteHistoryRequests) {
        if (editorSession.pasteHistoryRequests != seenPasteRequests[0]) {
            seenPasteRequests[0] = editorSession.pasteHistoryRequests
            pasteHistoryOpen = true
        }
    }
    var rename by popups::rename
    var renameBusy by popups::renameBusy
    var renameError by popups::renameError
    var navMenu by popups::navMenu
    // Mouse: the hover popups, the Ctrl/⌘ link and the right-click menu (see EditorMouseSupport).
    val hover = remember(path) { EditorHoverState() }
    var contextMenuAt by popups::contextMenuAt
    var hoverSheetGroup by popups::hoverSheetGroup

    /** Run one source go-to for [kind] (a keyboard shortcut): exactly one target navigates immediately; 0 or
     *  many open the dropdown (a "nothing found" note, or a target picker). */
    fun runNav(kind: UiNavKind) {
        backend.diagnostics.countFeature(UiFeatures.navigation(kind.name))
        val text = editorSession.doc.text
        val caret = editorSession.selection.start
        scope.launch {
            val targets = runCatching { backend.editor.navigationTargets(path, text, caret, kind) }.getOrDefault(emptyList())
            if (targets.size == 1) {
                navMenu = null
                onNavigate(targets[0].path, targets[0].offset)
            } else {
                navMenu = NavMenuState.Results(targets)
            }
        }
    }

    /** Open the Go-to menu (the long-press "Go to" affordance): resolve which actions APPLY at the caret and
     *  show only those. */
    fun openNavMenu() {
        val text = editorSession.doc.text
        val caret = editorSession.selection.start
        scope.launch {
            val resolved = runCatching { backend.editor.navigationOptions(path, text, caret) }.getOrDefault(emptyList())
            // Touch has no rename shortcut, so the menu is where a renameable symbol offers it.
            val renameable = runCatching { backend.editor.prepareRename(path, text, caret) }.getOrNull()
            // Nor a Find Usages one. Usages are a project-wide search, too slow to run for every menu that opens,
            // so a symbol (one that resolves to a declaration, or can be renamed) gets the entry with no targets
            // yet and the search runs when it is picked.
            val symbol = renameable != null || resolved.any { it.kind == UiNavKind.DECLARATION }
            val options = if (symbol && resolved.none { it.kind == UiNavKind.USAGES }) {
                (resolved + UiNavOption(UiNavKind.USAGES, emptyList())).sortedBy { it.kind.ordinal }
            } else resolved
            navMenu = NavMenuState.Menu(options, renameable)
            // Resolve the code actions too. Nothing is resolved proactively off a diagnostic line, so
            // without this the menu's Quick fixes / Intentions / Actions sections would be empty exactly
            // where the user opened it to find them.
            acts.resolveContextMenu()
        }
    }

    fun showQuickDoc() {
        backend.diagnostics.countFeature(UiFeatures.QUICK_DOC)
        val caret = editorSession.selection.start
        val text = editorSession.doc.text
        scope.launch {
            quickDoc = runCatching { backend.editor.quickDocAt(path, text, caret) }.getOrNull()
        }
    }

    // Resting the mouse on code: after HOVER_DELAY_MS a problem under the pointer opens its popup, and
    // otherwise the symbol's documentation shows beside it. Moving within the shown span keeps it; leaving
    // hides it after a short grace, unless the pointer went into the popup.
    val obscuredNow by rememberUpdatedState(obscured)
    LaunchedEffect(hover) {
        hover.moves.collectLatest { p ->
            val shown = hover.info
            if (shown != null && p != null && p.offset in shown.start until shown.end) return@collectLatest
            if (shown != null) {
                delay(HOVER_HIDE_GRACE_MS)
                if (hover.popupHovered) return@collectLatest
                hover.info = null
            }
            if (p == null || p.offset < 0 || obscuredNow) return@collectLatest
            delay(HOVER_DELAY_MS)
            val problem = editorSession.diagnostics
                .filter { p.offset >= it.startOffset && p.offset < it.endOffset }
                .minByOrNull { it.severity.ordinal }
            if (problem != null) {
                if (acts.sheetGroup.isNotEmpty()) return@collectLatest // a clicked problem popup is already open
                acts.openSheet(problem)
                hoverSheetGroup = acts.sheetGroup
                hover.info = HoverInfo.Problem(problem, problem.startOffset, maxOf(problem.endOffset, problem.startOffset + 1))
                return@collectLatest
            }
            val word = identifierRangeAt(editorSession.doc.chars, p.offset) ?: return@collectLatest
            val doc = runCatching { backend.editor.quickDocAt(path, editorSession.doc.text, p.offset) }.getOrNull()
                ?: return@collectLatest
            hover.info = HoverInfo.Doc(doc, word.first, word.last + 1)
        }
    }
    // A hidden hover takes the problem popup it opened with it (not one a click opened since).
    LaunchedEffect(hover) {
        snapshotFlow { hover.info }.collect { info ->
            val group = hoverSheetGroup
            if (info == null && group != null) {
                hoverSheetGroup = null
                if (acts.sheetGroup === group) acts.closeSheet()
            }
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

    val renameFailedMsg = stringResource(Res.string.rename_failed)
    fun commitRename() {
        val r = rename ?: return
        if (renameBusy || r.newName.isBlank() || r.newName == r.oldName) {
            rename = null; return
        }
        renameBusy = true; renameError = null
        val text = editorSession.doc.text
        scope.launch {
            val result = runCatching { backend.editor.rename(path, text, r.offset, r.newName) }
                .getOrElse { UiRenameResult(false, it.message ?: renameFailedMsg) }
            renameBusy = false
            if (result.success) {
                backend.diagnostics.countFeature(UiFeatures.RENAME)
                rename = null; onRenamed(result.newPath)
            } else renameError = result.message
        }
    }

    // Read the document + selection straight off the session (snapshot reads → this body recomposes on edit),
    // deriving everything from the rope's O(log N) access; the body never forces the full-text materialization.
    val doc = editorSession.doc
    // Merge-conflict blocks (from a Git merge or a store update that kept the user's edits): tinted, with a
    // resolve row on each. The parser's errors inside a block are about the markers, not the code, so they hide.
    val conflicts = rememberMergeConflicts(editorSession, doc)
    // The buffer's changes since the last commit, for the gutter's change stripes.
    val changeMarkers = rememberChangeMarkers(path, editorSession, doc, backend, editorSession.doc.isLarge())
    // The session owns and auto-shifts these; rendered here.
    val diagnostics = remember(editorSession.diagnostics, conflicts) {
        withoutConflictDiagnostics(editorSession.diagnostics, conflicts)
    }
    val docLength = doc.length
    val caretOffset = editorSession.selection.start.coerceIn(0, docLength)
    // Language-specific word chars (XML namespace `:` and resource-ref `@?+/.-`) so the popup survives them.
    val wordExtra = extraWordChars(path)
    val liveCompletion = completion.current?.takeIf { it.coversCaret(doc.chars, caretOffset, wordExtra) }
    val activePrefix = liveCompletion?.let { doc.substring(it.tokenStart, caretOffset) } ?: ""
    val displayed = completion.displayedFor(liveCompletion, activePrefix)
    val showPopup = !completion.dismissed && displayed.isNotEmpty()
    val safeSelected = completion.selected.coerceIn(0, (displayed.size - 1).coerceAtLeast(0))

    // Keep the popup WINDOW mounted across the 1-frame gaps a keystroke opens up (see CompletionController).
    val onToken = liveCompletion != null
    val hasItems = displayed.isNotEmpty()
    LaunchedEffect(completion.dismissed, onToken, hasItems) {
        completion.updatePopupVisibility(onToken, hasItems)
    }
    // The state this composition renders, handed straight to the popup so a keystroke's narrowed list (and the
    // first open) shows in the same frame; the SideEffect keeps it as the last good state for the keep-alive.
    val shownNow = if (liveCompletion != null && hasItems) completion.shownForDisplayed() else null
    SideEffect {
        if (shownNow != null) completion.snapshotShown(shownNow)
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
        // If the caret is inside a live-template field, accepting a completion fills the field and then advances
        // to the next stop — so selecting a value for a field flows straight into the next one, the same step
        // Enter/Tab make. Skipped when the accepted item starts its OWN template (that new session begins at its
        // first stop instead). Captured before the edit; applied at the plain-completion return path below.
        val activeTemplate = snippet
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
        // Inside an XML attribute value the token sits in quotes; "acknowledge with a space" is wrong there
        // (it produces android:x="false "). The caret should hop OUT of the quotes instead (see below), so
        // suppress the no-op space when the next char is a closing quote in a quote-hopping (XML) language.
        val nextIsCloseQuote = mainEnd < len && (chars[mainEnd] == '"' || chars[mainEnd] == '\'') && wordExtra.isNotEmpty()
        val insert = if (noOp && !nextIsSpace && !nextIsCloseQuote) item.insertText + " " else item.insertText

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
        // Filled a template field with the accepted value → mirror it and step to the next stop. The accept edit
        // already re-anchored the session via onSnippetEdit, so `next()` selects the correct following field.
        if (activeTemplate != null && snippet === activeTemplate) {
            if (!activeTemplate.next()) snippet = null
        }
    }

    // Smart Enter (Shift+Enter): finish the current line, then open an indented new line — IntelliJ's "Complete
    // Statement". The decision is a pure function ([smartEnter]); we just apply its edit.
    fun completeStatement() {
        completion.dismiss()
        val edit = smartEnter(editorSession.doc.chars, editorSession.selection.start, editorSession.language)
        editorSession.applyEdits(listOf(edit), TextRange(edit.caret))
    }

    // Apply the minimal edits a backend source-transform (Reformat, Optimize Imports) returns for the whole
    // buffer, splicing them in while keeping the caret on its logical spot and the viewport anchored on the
    // caret's line. A no-op ([compute] returns nothing) does nothing.
    suspend fun applyBufferEdits(compute: suspend (text: String) -> List<dev.ide.ui.backend.UiTextEdit>) {
        completion.dismiss()
        val text = editorSession.doc.text
        val caretBefore = editorSession.selection.start.coerceIn(0, editorSession.doc.length)
        val anchorLine = editorSession.doc.lineForOffset(caretBefore)
        val raw = runCatching { compute(text) }.getOrNull().orEmpty()
        if (raw.isEmpty()) return
        val len = editorSession.doc.length
        val edits = raw.map { e ->
            val st = e.start.coerceIn(0, len)
            RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length)
        }
        applyEditsKeepingViewport(edits, TextRange(mapOffsetThroughEdits(caretBefore, edits)), anchorLine)
    }

    // Reformat Code: the minimal edits to reformat the whole buffer, or just the selection when
    // `[rangeStart, rangeEnd)` is non-empty.
    suspend fun runFormat(rangeStart: Int, rangeEnd: Int) = applyBufferEdits { text ->
        backend.diagnostics.countFeature(UiFeatures.FORMAT)
        if (rangeEnd > rangeStart) backend.editor.formatRange(path, text, rangeStart, rangeEnd)
        else backend.editor.formatDocument(path, text)
    }

    // Optimize Imports: reorder / de-duplicate / wildcard-collapse / drop-unused the file's imports.
    suspend fun runOptimizeImports() = applyBufferEdits { text -> backend.editor.optimizeImports(path, text) }

    // keep the per-line render cache aligned with line splices (a render concern, owned by this surface)
    SideEffect {
        editorSession.onLinesShifted = { from, delta -> renderState.renderCache.shiftKeys(from, delta) }
        // Active template session re-anchors its tab stops on each edit (typing inside a placeholder).
        editorSession.onSnippetEdit = { span -> snippet?.onEdit(span) }
        // Soft-keyboard Tab (the touch symbol bar) accepts the highlighted completion when the popup is up,
        // mirroring the hardware-Tab path (onPreviewKey); the caller falls back to indent when this returns false.
        editorSession.acceptCompletionIfShowing = { if (showPopup) { accept(); true } else false }
        // Soft-keyboard Enter in a live template steps to the next field instead of inserting a newline — the IME
        // path (commitText "\n" / performEditorAction) bypasses onPreviewKey, so it consults this. Mirrors the
        // hardware-Enter template handling: accept a showing completion first (accept() then advances the
        // template), else step to the next stop. Returns false with no active template → the IME newlines as usual.
        editorSession.advanceTemplateOnEnter = {
            val sn = snippet
            if (sn == null) false
            else {
                if (showPopup) accept() else if (!sn.next()) snippet = null
                true
            }
        }
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

        // A member access (`.`) or an annotation start (`@`, Kotlin/Java) opens a fresh candidate set; an
        // identifier character extends the current token (narrowed client-side when the live set still covers
        // it, so the keystroke path skips a backend re-query); anything else ends the session.
        when (completionKeystroke(before, wordExtra)) {
            CompletionKeystroke.Reopen ->
                if (completion.autoPopupEnabled) completion.reopen() else completion.dismiss()
            CompletionKeystroke.Extend ->
                if (!canNarrowLocally(completion.current, completion.dismissed, d.chars, caret, wordExtra)) {
                    if (completion.autoPopupEnabled) completion.reopen() else completion.dismiss()
                }
            CompletionKeystroke.Dismiss -> completion.dismiss()
        }
    }

    // code-action availability — debounced on the selection + text revision, so the lightbulb appears when the
    // caret rests on (or selects) something actionable. Also keyed on [diagnostics]: the daemon delivers a fresh
    // analysis asynchronously (no selection/text change of its own), and the lightbulb is gated on the caret
    // sitting on a diagnostic — so without this key, a fix that only materializes once analysis lands (e.g. the
    // auto-import quick-fix) would never re-resolve after being dismissed until an unrelated caret move or edit
    // (undo) happened to re-fire the effect.
    LaunchedEffect(path, editorSession.selection, editorSession.textRevision, editorSession.diagnostics) {
        acts.refreshAvailability(isFocused)
    }
    // Typing closes the problem popup/sheet: what it shows was resolved against the text before the edit.
    LaunchedEffect(editorSession.textRevision) { acts.closeSheetIfEdited() }

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
    LaunchedEffect(optimizeImportsEpoch) { if (optimizeImportsEpoch > 0) runOptimizeImports() }
    // Only a request made while this editor is showing: one bumped earlier must not make every editor that
    // opens afterwards take focus (from the Search panel that opened it, say).
    val mountFocusEpoch = remember { focusEpoch }
    LaunchedEffect(focusEpoch) { if (focusEpoch > mountFocusEpoch) runCatching { focus.requestFocus() } }

    // recompute find matches when the query/options change or the buffer edits (debounced).
    LaunchedEffect(find.open, find.query, find.options, editorSession.textRevision) {
        if (find.open && find.query.isNotEmpty()) delay(120.milliseconds)
        find.recompute()
    }

    // Fetch the file's declarations (debounced) for sticky headers — kept off the keystroke path.
    LaunchedEffect(path, editorSession.textRevision, largeFile) {
        if (largeFile) { geometry.editorStructure.value = emptyList(); return@LaunchedEffect }
        delay(300.milliseconds)
        geometry.editorStructure.value =
            runCatching { backend.editor.fileStructure(path, editorSession.doc.awaitText()) }.getOrDefault(emptyList())
    }

    // Gutter swatches for the colors/drawables the buffer's resource references resolve to (debounced, like the
    // structure fetch). Only files that can name an Android resource ask.
    LaunchedEffect(path, editorSession.textRevision, largeFile) {
        val swatchable = path.endsWith(".xml") || path.endsWith(".kt") || path.endsWith(".java")
        if (largeFile || !swatchable) { editorSession.applyResourceSwatches(emptyList()); return@LaunchedEffect }
        delay(400.milliseconds)
        editorSession.applyResourceSwatches(
            runCatching { backend.editor.resourceSwatches(path, editorSession.doc.awaitText()) }.getOrDefault(emptyList()),
        )
    }

    LaunchedEffect(path) { runCatching { focus.requestFocus() } }

    // ---- per-line diagnostic segments (recomputed per edit — O(diagnostics), they are few) ----
    // Keyed on the document *instance* (an edit swaps it; a caret move doesn't), so the key compare is O(1).
    val diagByLine = remember(diagnostics, doc) { mapDiagnosticsToLines(diagnostics, doc) }
    // The draw palette depends only on the theme, so build it once per theme instead of allocating a fresh
    // EditorDrawColors (17 fields + the alpha-blended copies) on every drawBehind frame during a fling.
    // Every field is the active color scheme's value where it has one and the theme's where it does not,
    // so an untouched install keeps tracking the accent (Material You included) and a scheme that pins its
    // own background/selection/gutter gets them. The scheme was resolved once at the theme, so this is a
    // handful of map lookups, not a walk up any fallback chain.
    val drawColors = remember(colors, editorColors) {
        EditorDrawColors(
            background = editorColors.background(colors.editorBg),
            currentLine = editorColors.currentLine(colors.currentLine),
            caret = editorColors.caret(colors.accent),
            selection = editorColors.selection(colors.accent.copy(alpha = 0.30f)),
            gutterText = editorColors.gutterText(colors.gutterText),
            gutterCurrent = editorColors.gutterCurrent(colors.textSecondary),
            gutterBorder = editorColors.gutterBorder(colors.separator),
            error = editorColors.error(colors.error),
            warning = editorColors.warning(colors.warning),
            info = editorColors.info(colors.info),
            muted = colors.textTertiary,
            composing = editorColors.composing(colors.textSecondary),
            indentGuide = editorColors.indentGuide(colors.hairline),
            findMatch = editorColors.findMatch(colors.warning.copy(alpha = 0.28f)),
            findCurrent = editorColors.findCurrent(colors.accent.copy(alpha = 0.5f)),
            occurrence = editorColors.occurrence(colors.textSecondary.copy(alpha = 0.18f)),
            occurrenceWrite = editorColors.occurrenceWrite(colors.syntax.keyword.copy(alpha = if (colors.isDark) 0.34f else 0.22f)),
            templateField = editorColors.templateField(colors.accent.copy(alpha = 0.16f)),
            conflictCurrent = colors.success,
            conflictIncoming = colors.info,
        )
    }
    val bracketPair = remember(doc, editorSession.selection) {
        matchingBracket(doc.chars, editorSession.selection.start)
    }

    // ---- highlight usages of the identifier under the caret (resolved where the language can, else textual) ----
    // Suppressed while the find bar is open (find owns matches).
    val occurrenceWord = run {
        val sel = editorSession.selection
        if (find.open) null else {
            val r = wordRangeAt(doc.chars, sel.start)
            if (r.isEmpty()) null else {
                val w = doc.substring(r.first, r.last + 1)
                // Identifiers only; and when there IS a selection it must be exactly the word.
                val isIdent = w.isNotEmpty() && (w[0].isLetter() || w[0] == '_' || w[0] == '$')
                if (isIdent && (sel.collapsed || (sel.min == r.first && sel.max == r.last + 1))) CaretWord(w, r.first) else null
            }
        }
    }
    val occurrences = rememberOccurrences(editorSession, doc, occurrenceWord, largeFile, path, backend)

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
                    if (ev.isShiftPressed) editorSession.dedent() else if (!editorSession.tabOut()) editorSession.indent()
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
                    // Cap the payload so a multi-MB selection can't blow the clipboard's Binder transaction.
                    editorSession.selectedText()?.let(clipboard::copyFromEditor); return true
                }

                Key.X -> {
                    editorSession.cutSelection()?.let(clipboard::copyFromEditor); return true
                }

                Key.V -> {
                    // Ctrl/⌘-Shift-V: Paste from History, as in IntelliJ.
                    if (ev.isShiftPressed) pasteHistoryOpen = true
                    else clipboard.textForPaste()?.let(editorSession::commitText)
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

    /**
     * Run the editor command [id], or report that this editor does not provide it.
     *
     * The bodies are the ones that used to sit inside `onPreviewKey`'s chain of key conditions; only the way
     * they are reached changed, from "these modifiers and this key" to "this command id". Which keys reach
     * which command is now data (`EDITOR_KEY_DEFAULTS`, overridable by the user and by a plugin), and the
     * variants that used to be branches on a modifier are separate ids.
     */
    /** Open the editor's context menu at [pos] (pane coordinates): a right-click, or the Menu key at the caret. */
    fun openContextMenu(pos: Offset) {
        completion.dismiss()
        hover.dismiss()
        contextMenuAt = pos
        scope.launch { acts.resolveContextMenu() }
    }

    fun runEditorCommand(id: String): Boolean {
        when (id) {
            // The keyboard's way to the context menu: under the caret, as IntelliJ places it.
            EditorCommands.SHOW_CONTEXT_MENU -> {
                val (_, x, top) = geometry.caretGeometry(editorSession.selection.end)
                openContextMenu(Offset(x, top + metrics.lineHeight))
            }

            // Reformat: the selection if any, else the whole file.
            EditorCommands.REFORMAT -> {
                val sel = editorSession.selection
                scope.launch { if (!sel.collapsed) runFormat(sel.min, sel.max) else runFormat(0, 0) }
            }

            EditorCommands.OPTIMIZE_IMPORTS -> scope.launch { runOptimizeImports() }

            // Reformat-on-save (Settings → Code Style) is applied by the save path itself (AppState.save), so
            // it covers every save trigger — this command, the toolbar button, autosave — uniformly.
            EditorCommands.SAVE -> onSave()

            EditorCommands.FIND, EditorCommands.REPLACE -> {
                val seed = editorSession.selectedText()?.takeIf { it.isNotEmpty() && '\n' !in it }
                find.openBar(replace = id == EditorCommands.REPLACE, seed = seed)
                completion.dismiss()
            }

            EditorCommands.GO_TO_LINE -> {
                gotoLineOpen = true
                completion.dismiss()
            }

            EditorCommands.QUICK_DOC -> {
                showQuickDoc()
                completion.dismiss()
            }

            // A single target jumps; several open a picker. Declaration also resolves an Android resource
            // reference.
            EditorCommands.GO_TO_DECLARATION -> runNav(UiNavKind.DECLARATION)
            EditorCommands.GO_TO_IMPLEMENTATION -> runNav(UiNavKind.IMPLEMENTATION)
            EditorCommands.GO_TO_TYPE_DECLARATION -> runNav(UiNavKind.TYPE_DECLARATION)
            EditorCommands.GO_TO_SUPER -> runNav(UiNavKind.SUPER)
            EditorCommands.FIND_USAGES -> runNav(UiNavKind.USAGES)
            EditorCommands.GO_TO_RELATED -> runNav(UiNavKind.RELATED)

            EditorCommands.COMPLETE_CODE -> if (!readOnly) completion.reopen(immediate = true)

            // Force the signature-help panel even if it was dismissed.
            EditorCommands.PARAMETER_INFO -> sig.triggerExplicit()

            EditorCommands.RENAME, EditorCommands.RENAME_ALTERNATE -> startRename()

            EditorCommands.NEXT_DIAGNOSTIC -> editorSession.goToDiagnostic(forward = true)
            EditorCommands.PREVIOUS_DIAGNOSTIC -> editorSession.goToDiagnostic(forward = false)

            EditorCommands.TOGGLE_COMMENT, EditorCommands.TOGGLE_BLOCK_COMMENT -> {
                editorSession.toggleComment(preferBlock = id == EditorCommands.TOGGLE_BLOCK_COMMENT)
                completion.dismiss()
            }

            EditorCommands.DUPLICATE_LINE -> editorSession.duplicateSelection()

            EditorCommands.DELETE_LINE -> {
                editorSession.deleteLines()
                completion.dismiss()
            }

            EditorCommands.JOIN_LINES -> editorSession.joinLines()
            EditorCommands.TOGGLE_BOOKMARK -> editorSession.toggleBookmark()

            EditorCommands.MOVE_LINE_UP -> editorSession.moveLines(-1)
            EditorCommands.MOVE_LINE_DOWN -> editorSession.moveLines(1)

            EditorCommands.CODE_ACTIONS, EditorCommands.CODE_ACTIONS_ALTERNATE ->
                if (acts.available.isNotEmpty()) acts.openMenu()

            // A command this editor does not provide: a plugin's own, bound to a key that reached us. Left
            // unhandled so the key falls through rather than being swallowed.
            else -> return false
        }
        return true
    }

    // Editor commands, handled on the Preview pass so they win over the default key path + completion keys.
    fun onPreviewKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        // Any key hides a hover popup, as in IntelliJ; Escape is spent doing it.
        if (hover.info != null) {
            hover.dismiss()
            if (ev.key == Key.Escape) return true
        }
        // Escape closes the pointer's problem popup, like a click outside it.
        if (ev.key == Key.Escape && acts.sheetGroup.isNotEmpty()) {
            acts.closeSheet()
            return true
        }
        // Escape dismisses an open quick-doc popup before anything else can claim it, as it did when this was
        // a chain of conditions: the popup is modal-ish, and its Escape is not a command.
        if (quickDoc != null && ev.key == Key.Escape) {
            quickDoc = null
            return true
        }
        // The keymap owns the named commands. What it does not claim falls through to the modal handlers
        // below (the code-action menu, a live template, the completion popup), which own their keys while they
        // are open and are not rebindable for that reason.
        when (val outcome = resolveEditorCommand(ev)) {
            // A Global binding resolves here too (the editor is inside the IDE). The editor does not run those
            // itself; it hands them to the screen's app-command runner, so the key is resolved exactly once,
            // which the keymap's chord state needs.
            is KeymapHost.Outcome.Command ->
                if (runEditorCommand(outcome.actionId) || AppCommandHost.run(outcome.actionId)) return true
            // The first half of a chord: consume it so it does not also reach the buffer.
            is KeymapHost.Outcome.Pending -> return true
            KeymapHost.Outcome.None -> Unit
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
        // Active template (snippet) session steers Tab/Shift-Tab/Enter/Escape when the completion popup isn't up.
        val sn = snippet
        if (sn != null && !showPopup) {
            when (ev.key) {
                Key.Tab -> {
                    if (ev.isShiftPressed) sn.prev() else if (!sn.next()) snippet = null
                    return true
                }

                // In a live template, plain Enter advances to the next field (like Tab) instead of splitting the
                // line — so a template reads as a fill-in-the-blanks flow. Consumed here in the preview handler so
                // `handleKey`'s newline never fires. Shift-Enter (Smart Enter) is left to fall through unchanged.
                Key.Enter, Key.NumPadEnter -> if (!ev.isShiftPressed) {
                    if (!sn.next()) snippet = null
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
            // Before the clip, so the loupe may rise above the editor's top edge (over the breadcrumbs) to show a top line.
            .editorLoupe(interaction, metrics.lineHeight, background = drawColors.background, outline = colors.separator)
            .clipToBounds()
            // Outermost input: three-finger undo/redo sees the touches before the editor's own gestures do.
            .then(if (readOnly) Modifier else Modifier.undoSwipe(editorSession, undoFeedback)),
    ) {
        EditorCanvasLayer(
            editorSession = editorSession,
            path = path,
            geometry = geometry,
            interaction = interaction,
            renderState = renderState,
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
            wrapActive = geometry.wrapActive,
            acts = acts,
            completion = completion,
            find = find,
            hover = hover,
            focus = focus,
            scope = scope,
            softKeyboardSuggestions = softKeyboardSuggestions,
            pinchZoom = pinchZoom,
            liveScale = liveScale,
            onFontScaleChange = onFontScaleChange,
            twoAxisScroll = twoAxisScroll,
            caretClip = caretClip,
            expandProgress = expandProgress,
            expandAnimToken = expandAnimTokenState,
            diagByLine = diagByLine,
            bracketPair = bracketPair,
            occurrences = occurrences,
            conflicts = conflicts,
            templateFields = { snippet?.fieldRanges().orEmpty() },
            painters = painters,
            drawColors = drawColors,
            handleColor = colors.accent,
            halloween = halloween,
            onFocusedChange = { isFocused = it },
            onDismissQuickDoc = { quickDoc = null },
            onPreviewKey = ::onPreviewKey,
            onKey = ::handleKey,
            onContextMenu = ::openContextMenu,
            onLinkClick = { offset ->
                editorSession.setCaret(offset)
                runNav(UiNavKind.DECLARATION)
            },
        )

        // The caret, in a layer of its own (see [drawCaret]). The blink is read in the layer block, so it changes
        // the layer's alpha without drawing anything again; the animated position is read in the draw, so a
        // glide redraws only the caret.
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    // A candle does not blink; its flame flickering is the at-rest motion.
                    val lit = blinkOn || caretStyle == CaretStyle.Candle
                    alpha = when {
                        // Under a floating cursor the caret marks where the text will take it: steady and faint.
                        editorSession.floatingCaret != null -> 0.45f
                        isFocused && lit && editorSession.selection.collapsed -> 1f
                        else -> 0f
                    }
                }
                .drawBehind {
                    if (!editorSession.selection.collapsed) return@drawBehind
                    drawCaret(
                        caretContent = interaction.caretContent,
                        vOff = geometry.vOffset.floatValue,
                        hOff = geometry.hOffset.floatValue,
                        gutterWidth = gutterWidthPx,
                        codeTop = caretClip.codeTop,
                        lineHeight = metrics.lineHeight,
                        color = drawColors.caret,
                        style = caretStyle,
                        charWidth = metrics.charWidth,
                        flameTime = if (caretStyle == CaretStyle.Candle) flameTime.floatValue else 0f,
                    )
                    if (caretStyle == CaretStyle.Candle && !embers.isEmpty()) {
                        clipRect(left = gutterWidthPx, top = caretClip.codeTop) {
                            with(embers) {
                                draw(flameTime.floatValue, geometry.vOffset.floatValue, geometry.hOffset.floatValue, metrics.lineHeight)
                            }
                        }
                    }
                },
        )

        // The symbol bar trackpad's floating cursor: wherever the finger has it, between lines or past a line's
        // end, with the faint caret above showing where in the text that lands.
        Spacer(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val floating = editorSession.floatingCaret ?: return@drawBehind
                    val doc = editorSession.doc
                    val line = kotlin.math.floor(floating.line).toInt().coerceIn(0, doc.lineCount - 1)
                    val onText = floating.column.toInt().coerceAtMost(doc.lineLength(line))
                    val (_, textX, top) = geometry.caretGeometry(doc.lineStart(line) + onText)
                    val x = textX + (floating.column - onText) * metrics.charWidth
                    val y = top + (floating.line - line) * metrics.lineHeight
                    val w = 3.dp.toPx()
                    val h = metrics.lineHeight * 1.15f
                    clipRect(left = gutterWidthPx, top = caretClip.codeTop) {
                        drawRoundRect(
                            drawColors.caret,
                            topLeft = Offset(x - w / 2f, y - (h - metrics.lineHeight) / 2f),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
                        )
                    }
                },
        )

        // Halloween: a cobweb in the corner of an idle editor, and once a session a ghost drifting across it. Both
        // are overlays keyed on edits and caret moves, so typing brushes them away and the code layer never redraws.
        val activity = editorSession.editCount to editorSession.selection.start
        EditorCobweb(
            activity = activity,
            enabled = halloween,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.TopEnd),
        )
        EditorGhost(
            activity = activity,
            enabled = halloween,
            background = drawColors.background,
            modifier = Modifier.matchParentSize(),
        )

        // The outline scrubber; on a file too large for an outline it is still a fast-scroll thumb, just without ticks.
        FastScrollLayer(
            session = editorSession,
            geometry = geometry,
            thumbColor = colors.textSecondary,
            trackColor = colors.separator,
            classTickColor = colors.accent,
            memberTickColor = colors.textTertiary,
        )

        if (horizontalScrollbar) {
            HorizontalScrollbarLayer(
                geometry = geometry,
                gutterWidthPx = gutterWidthPx,
                thumbColor = colors.textTertiary,
                trackColor = colors.separator,
            )
        }

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
            onChipExtent = { geometry.chipExtent.floatValue = it },
            visibleLinesOf = { geometry.visibleLineRange() },
        )

        PreviewGutterIconsLayer(
            session = editorSession,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            docLength = docLength,
            onPreview = onPreview,
        )

        PluginEditorLayers(
            session = editorSession,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            hOffset = geometry.hOffset,
            gutterWidthPx = gutterWidthPx,
            path = path,
            backend = backend,
            visibleLinesOf = { geometry.visibleLineRange() },
        )

        PluginGutterMarksLayer(
            session = editorSession,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            docLength = docLength,
            colors = colors,
            onInvoke = acts::invokeGutterAction,
            backend = backend,
        )

        if (inlineBlame) {
            InlineBlameLayer(
                path = path,
                session = editorSession,
                backend = backend,
                markers = changeMarkers,
                caretOffset = caretOffset,
                caretGeometry = { geometry.caretGeometry(it) },
                metrics = metrics,
                largeFile = largeFile,
            )
        }

        BookmarksGutterLayer(
            session = editorSession,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            gutterWidthPx = gutterWidthPx,
            color = colors.accent,
        )

        ChangeMarkersLayer(
            session = editorSession,
            markers = changeMarkers,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            gutterWidthPx = gutterWidthPx,
            foldStripPx = renderState.foldStripPx,
        )

        MergeConflictLayer(
            session = editorSession,
            conflicts = conflicts,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            hOffset = geometry.hOffset,
            gutterWidthPx = gutterWidthPx,
            currentColor = colors.success,
            incomingColor = colors.info,
        )

        ResourceSwatchGutterLayer(
            session = editorSession,
            metrics = metrics,
            vlayout = geometry.vlayout,
            vOffset = geometry.vOffset,
            docLength = docLength,
            onOpen = onNavigate,
        )

        CodeActionsMenuLayer(
            acts = acts,
            engaged = engaged,
            caretOffset = caretOffset,
            caretGeometry = { geometry.caretGeometry(it) },
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
        )

        // The overlays placed in the pane rather than as popups (see [aboveLineInPane]). After the gutter and plugin
        // layers so they draw and take taps over them, before the cards below, which cover them; the bulb last.
        SignatureHelpLayer(
            sig = sig,
            engaged = engaged,
            caretOffset = caretOffset,
            caretGeometry = { geometry.caretGeometry(it) },
            lineHeightPx = metrics.lineHeight,
            gutterWidthPx = gutterWidthPx,
        )
        // Literal tweak chip (number scrub / color pick / boolean flip) in a file with @Preview composables. Gives way
        // to the completion popup and the quick-fix bulb/menu, and stacks above the touch selection toolbar.
        LiteralTweakLayer(
            session = editorSession,
            visible = engaged && !showPopup && !acts.menuOpen &&
                !(acts.available.isNotEmpty() && acts.caretDiagnostic != null),
            caretGeometry = { geometry.caretGeometry(it) },
            lineHeightPx = metrics.lineHeight,
            gutterWidthPx = gutterWidthPx,
            liftPx = if (interaction.handlesVisible && interaction.lastInputWasTouch) {
                interaction.selectionToolbarHeightPx + with(LocalDensity.current) { 8.dp.roundToPx() }
            } else 0,
        )
        UndoGestureLabel(undoFeedback)
        SelectionToolbarLayer(
            session = editorSession,
            geometry = geometry,
            interaction = interaction,
            lineHeightPx = metrics.lineHeight,
            onDocs = { showQuickDoc() },
            onMenu = { interaction.handlesVisible = false; openNavMenu() },
            onPasteHistory = { interaction.handlesVisible = false; pasteHistoryOpen = true },
        )
        PasteHistoryLayer(
            open = pasteHistoryOpen,
            caretOffset = caretOffset,
            caretGeometry = { geometry.caretGeometry(it) },
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
            onPick = { text ->
                pasteHistoryOpen = false
                // The picked entry becomes the clipboard's too, so a plain paste after it repeats it.
                clipboard.copyFromEditor(text)
                editorSession.commitText(text)
            },
            onDismiss = { pasteHistoryOpen = false },
        )
        LightbulbLayer(
            acts = acts,
            interaction = interaction,
            showPopup = showPopup,
            engaged = engaged,
            caretOffset = caretOffset,
            caretGeometry = { geometry.caretGeometry(it) },
            lineHeightPx = metrics.lineHeight,
            gutterWidthPx = gutterWidthPx,
        )

        // completion list, under the token start. Drawn in the pane, after the gutter, plugin and in-pane layers so it
        // covers them (as the popup window it replaced did); the dialogs, menus and find bar below cover it.
        CompletionPopupLayer(
            completion = completion,
            shownNow = shownNow,
            onToken = onToken,
            engaged = engaged,
            docLength = docLength,
            caretGeometry = { geometry.caretGeometry(it) },
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
            safeSelected = safeSelected,
            onAccept = { accept(it) },
        )

        EditorDialogsLayer(
            popups = popups,
            editorSession = editorSession,
            acts = acts,
            find = find,
            hover = hover,
            interaction = interaction,
            geometry = geometry,
            metrics = metrics,
            gutterWidthPx = gutterWidthPx,
            engaged = engaged,
            obscured = obscured,
            readOnly = readOnly,
            caretOffset = caretOffset,
            focus = focus,
            scope = scope,
            commands = object : EditorDialogCommands {
                override fun onCommitRename() = commitRename()
                override fun onStartRename() = startRename()
                override fun onRunNav(kind: UiNavKind) = runNav(kind)
                override fun onNavUsed(kind: UiNavKind) = backend.diagnostics.countFeature(UiFeatures.navigation(kind.name))
                override fun onEditorCommand(id: String) = runEditorCommand(id)
                override fun onNavigateTo(path: String, offset: Int) = onNavigate(path, offset)
            },
        )
    }
}


/**
 * The code canvas: the input chain (IME, keys, gestures, hover, pinch) and the one `drawBehind` that paints the
 * text, gutter and in-text overlays. Extracted from [CodeEditorContent] so ART can compile that method (see the
 * note above [CompletionPopupLayer]). Non-skippable on purpose: it reads holder fields that are plain vars
 * refreshed on each recomposition of the parent, so it must recompose exactly when the inline block did. The
 * expand-selection token and the template fields are still read in the draw phase, as before.
 */
@NonSkippableComposable
@Composable
private fun EditorCanvasLayer(
    editorSession: EditorSession,
    path: String,
    geometry: EditorGeometry,
    interaction: EditorInteraction,
    renderState: EditorRenderState,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    wrapActive: Boolean,
    acts: EditorActionsController,
    completion: CompletionController,
    find: FindReplaceController,
    hover: EditorHoverState,
    focus: FocusRequester,
    scope: CoroutineScope,
    softKeyboardSuggestions: Boolean,
    pinchZoom: Boolean,
    liveScale: State<Float>,
    onFontScaleChange: (Float) -> Unit,
    twoAxisScroll: Boolean,
    caretClip: CaretClip,
    expandProgress: Animatable<Float, AnimationVector1D>,
    expandAnimToken: IntState,
    diagByLine: Map<Int, List<DiagSeg>>,
    bracketPair: Pair<Int, Int>?,
    occurrences: OccurrenceTint,
    conflicts: List<MergeConflict>,
    templateFields: () -> List<IntRange>,
    painters: EditorPainters,
    drawColors: EditorDrawColors,
    handleColor: Color,
    halloween: Boolean,
    onFocusedChange: (Boolean) -> Unit,
    onDismissQuickDoc: () -> Unit,
    onPreviewKey: (KeyEvent) -> Boolean,
    onKey: (KeyEvent) -> Boolean,
    onContextMenu: (Offset) -> Unit,
    onLinkClick: (Int) -> Unit,
) {
    var pointerOverGutter by remember { mutableStateOf(false) }
    Box(
        Modifier
            .editorInput(
                session = editorSession,
                geometry = geometry,
                interaction = interaction,
                acts = acts,
                completion = completion,
                focus = focus,
                editorIme = editorSession.ime,
                scope = scope,
                softKeyboardSuggestions = softKeyboardSuggestions,
                metrics = metrics,
                gutterWidthPx = gutterWidthPx,
                wrapActive = wrapActive,
                pinchZoom = pinchZoom,
                liveScale = liveScale,
                onFontScaleChange = onFontScaleChange,
                useTwoAxisScroll = twoAxisScroll,
                scroll2D = geometry.scroll2D,
                vScroll = geometry.vScroll,
                hScroll = geometry.hScroll,
                onViewportSize = { geometry.viewport.value = it },
                onContentPositioned = { geometry.contentInWindow.value = it },
                onFocusedChange = onFocusedChange,
                onDismissQuickDoc = onDismissQuickDoc,
                onPreviewKey = onPreviewKey,
                onKey = onKey,
                hover = hover,
                onContextMenu = onContextMenu,
                onLinkClick = onLinkClick,
            )
            // A text (I-beam) cursor over the code, and the normal arrow over the gutter, as in IntelliJ. Reads
            // the hover position on the Initial pass without consuming it, so the editor's input is untouched.
            .pointerInput(gutterWidthPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                            pointerOverGutter = event.changes.first().position.x < gutterWidthPx
                        }
                    }
                }
            }
            .pointerHoverIcon(
                when {
                    hover.link != null -> PointerIcon.Hand // Ctrl/⌘ held over an identifier: a link
                    pointerOverGutter -> PointerIcon.Default
                    else -> PointerIcon.Text
                },
            )
            .drawBehind {
                // Interpolate the drawn selection span for an in-flight expand animation (reads snapshot
                // state → this draw re-runs per frame while it animates). Before the tween has started for
                // this request, treat progress as 0 (draw the pre-expand span) so the final range never
                // flashes for a frame. Null once settled.
                val expand = acts.selectionExpand
                val animatedSel: Pair<Int, Int>? = if (expand != null) {
                    val ep = if (expandAnimToken.intValue == expand.token) expandProgress.value else 0f
                    if (ep < 1f) {
                        val mn = expand.fromMin + ((expand.toMin - expand.fromMin) * ep).toInt()
                        val mx = expand.fromMax + ((expand.toMax - expand.fromMax) * ep).toInt()
                        mn to mx
                    } else null
                } else null
                caretClip.codeTop = drawEditor(
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
                    occurrences = occurrences.reads,
                    occurrenceWrites = occurrences.writes,
                    conflicts = conflicts,
                    templateFields = templateFields(),
                    decoByLine = renderState.decoByLine,
                    filePath = path,
                    painters = painters,
                    indentColsFor = renderState::indentColsFor,
                    stickyHeadersFor = { renderState.stickyHeadersFor(geometry.editorStructure.value, it) },
                    colors = drawColors,
                    handlesVisible = interaction.handlesVisible && interaction.lastInputWasTouch,
                    handleColor = handleColor,
                    animatedSelection = animatedSel,
                    spookyErrors = halloween,
                )
            },
    )
}

/** What [EditorDialogsLayer] asks of the editor that owns it: the editor's own operations, by name. */
private interface EditorDialogCommands {
    fun onCommitRename()
    fun onStartRename()
    fun onRunNav(kind: UiNavKind)
    /** A Go to menu entry was picked that navigates without running [onRunNav] (its targets were resolved). */
    fun onNavUsed(kind: UiNavKind)
    fun onEditorCommand(id: String): Boolean
    fun onNavigateTo(path: String, offset: Int)
}

/**
 * The prompts, menus and popups over the editor: go-to-line, quick doc, rename, the problem popup/sheet, hover
 * docs and the Ctrl/⌘ link, the right-click menu, the Go to menu and the find bar. Extracted from
 * [CodeEditorContent] so ART can compile that method; non-skippable so it recomposes exactly when the inline
 * block did (it reads holder fields that are plain vars).
 */
@NonSkippableComposable
@Composable
private fun BoxScope.EditorDialogsLayer(
    popups: EditorPopupState,
    editorSession: EditorSession,
    acts: EditorActionsController,
    find: FindReplaceController,
    hover: EditorHoverState,
    interaction: EditorInteraction,
    geometry: EditorGeometry,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    engaged: Boolean,
    obscured: Boolean,
    readOnly: Boolean,
    caretOffset: Int,
    focus: FocusRequester,
    scope: CoroutineScope,
    commands: EditorDialogCommands,
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var gotoLineOpen by popups::gotoLineOpen
    val quickDoc = popups.quickDoc
    var rename by popups::rename
    val renameBusy = popups.renameBusy
    var renameError by popups::renameError
    var navMenu by popups::navMenu
    var contextMenuAt by popups::contextMenuAt
    val hoverSheetGroup = popups.hoverSheetGroup

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
    quickDoc?.let { QuickDocPopup(it, Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) }

    // rename prompt — a small centered card over the editor
    rename?.let { r ->
        RenamePopup(
            state = r,
            busy = renameBusy,
            error = renameError,
            onChange = { rename = r.copy(newName = it) },
            onCommit = { commands.onCommitRename() },
            onCancel = {
                if (!renameBusy) {
                    rename = null; renameError = null
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    // diagnostic sheet: every diagnostic in the tapped area, plus the selected one's full (scrollable)
    // message and its fixes, docked at the pane bottom
    // With a mouse it is a popup anchored at the problem; on touch the bottom sheet, sized for a thumb.
    val fromHover = hoverSheetGroup != null && hoverSheetGroup === acts.sheetGroup
    if ((engaged || fromHover) && acts.sheetGroup.isNotEmpty()) {
        if (!isMobilePlatform || !interaction.lastInputWasTouch) {
            val anchor = (acts.sheetGroup.getOrNull(acts.sheetSelected) ?: acts.sheetGroup.first()).startOffset
            DiagnosticPopup(
                diagnostics = acts.sheetGroup,
                selected = acts.sheetSelected,
                actions = acts.sheetActions,
                onSelect = { acts.selectSheet(it) },
                onPick = { acts.applySheetFix(it) },
                onDismiss = { acts.closeSheet() },
                position = rememberLinePosition(
                    anchor.coerceIn(0, editorSession.doc.length), { geometry.caretGeometry(it) },
                    metrics.lineHeight, gutterWidthPx,
                ),
                modifier = if (fromHover) Modifier.trackPopupHover(hover) else Modifier,
            )
        } else {
            DiagnosticSheet(
                diagnostics = acts.sheetGroup,
                selected = acts.sheetSelected,
                actions = acts.sheetActions,
                onSelect = { acts.selectSheet(it) },
                onPick = { acts.applySheetFix(it) },
                onDismiss = { acts.closeSheet() },
            )
        }
    }

    // The hovered symbol's documentation, beside it.
    (hover.info as? HoverInfo.Doc)?.let { info ->
        if (!obscured) HoverDocPopup(info, hover, { geometry.caretGeometry(it) }, metrics.lineHeight, gutterWidthPx)
    }
    // The Ctrl/⌘ link under the pointer.
    hover.link?.let { LinkUnderline(it, { off -> geometry.caretGeometry(off) }, metrics.lineHeight) }
    // The right-click menu, at the pointer.
    contextMenuAt?.let { at ->
        EditorContextMenu(
            at = at,
            hasSelection = !editorSession.selection.collapsed,
            readOnly = readOnly,
            pluginMenu = acts.editorMenu,
            // Resolved again on pick: moving the caret to the click re-runs the editor's own availability
            // pass, which can clear what the right-click resolved.
            onShowActions = {
                scope.launch {
                    acts.resolveContextMenu()
                    if (acts.available.isNotEmpty()) acts.openMenu()
                }
            },
            onCommand = { commands.onEditorCommand(it) },
            onCut = { editorSession.cutSelection()?.let(clipboard::copyFromEditor) },
            onCopy = { editorSession.selectedText()?.let(clipboard::copyFromEditor) },
            onPaste = { clipboard.textForPaste()?.let(editorSession::commitText) },
            onSelectAll = { editorSession.selectAll() },
            onPluginAction = { acts.invokeMenuAction(it.id) },
            onDismiss = {
                contextMenuAt = null
                focus.requestFocus()
            },
        )
    }

    // source-navigation dropdown — the Go-to menu (from long-press / shortcuts) and its target picker,
    // anchored under the caret like the code-actions menu.
    NavMenuLayer(
        state = navMenu,
        actions = acts.available,
        menu = acts.editorMenu,
        onMenuAction = { item -> navMenu = null; acts.invokeMenuAction(item.id) },
        engaged = engaged,
        caretOffset = caretOffset,
        caretGeometry = { geometry.caretGeometry(it) },
        metrics = metrics,
        gutterWidthPx = gutterWidthPx,
        onOption = { opt ->
            if (opt.kind == UiNavKind.USAGES && opt.targets.isEmpty()) {
                navMenu = null
                commands.onRunNav(UiNavKind.USAGES) // the lazily-searched entry (see openNavMenu)
            } else if (opt.targets.size == 1) {
                navMenu = null
                commands.onNavUsed(opt.kind)
                commands.onNavigateTo(opt.targets[0].path, opt.targets[0].offset)
            } else {
                commands.onNavUsed(opt.kind)
                navMenu = NavMenuState.Results(opt.targets)
            }
        },
        onAction = { a -> val i = acts.available.indexOf(a); navMenu = null; if (i >= 0) acts.applyAt(i) },
        onPick = { navMenu = null; commands.onNavigateTo(it.path, it.offset) },
        onRename = { navMenu = null; commands.onStartRename() },
        onDismiss = { navMenu = null },
    )

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

// The caret-anchored popup emission blocks, extracted from CodeEditorContent so each compiles as its own
// method. CodeEditorContent was over ART's per-method JIT/AOT instruction limit (~17k), so on device it stayed
// interpreted — every recomposition ran slow. Splitting the emission blocks out lets ART compile them. Behaviour
// is identical: each is called at the same site with the same state; the anchor geometry is read at composition
// time via the [caretGeometry] lambda (no deferred layout-phase reads), so recomposition triggers are unchanged.

/** Completion popup, anchored at the token start below the caret line. Mounted on `popupVisible` (the keep-
 *  alive latch) and rendered from `shown` (the last good state) so a keystroke's transient session swap /
 *  filter miss doesn't blink the window shut. */
@Composable
private fun BoxScope.CompletionPopupLayer(
    completion: CompletionController,
    shownNow: ShownCompletion?,
    onToken: Boolean,
    engaged: Boolean,
    docLength: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    safeSelected: Int,
    onAccept: (UiCompletionItem?) -> Unit,
) {
    // What the keep-alive latch will settle on for this frame (see [CompletionController.updatePopupVisibility]),
    // read now so the popup opens with its first items instead of a frame later; the latch still holds the
    // window open across a transient empty list.
    val visible = when {
        completion.dismissed || !onToken -> false
        shownNow != null -> true
        else -> completion.popupVisible
    }
    val shown = shownNow ?: completion.shown
    if (visible && shown != null && engaged) {
        val density = LocalDensity.current
        val anchor = shown.tokenStart.coerceIn(0, docLength)
        val gapPx = with(density) { 6.dp.roundToPx() }
        val marginPx = with(density) { 8.dp.roundToPx() }
        // System Back closes the list first, as it did when the list was a popup window.
        PlatformBackHandler(enabled = true) { completion.dismiss() }
        // The pane's own bounds (it already sits above the keyboard). In the pane rather than a `Popup`: see
        // [belowLineInPane].
        BoxWithConstraints(Modifier.matchParentSize()) {
            val compact = maxWidth < 600.dp
            val popupWidth = if (compact) (maxWidth * 0.85f).coerceIn(240.dp, 340.dp) else 440.dp
            // Fill the room below the caret line so the list auto-expands, bounded by a generous ceiling. With
            // only a sliver below (the caret near the pane bottom) and more room above, size it to the room above
            // instead: [belowLineInPane] then places it above the line.
            val (_, _, anchorTop) = caretGeometry(anchor)
            val roomBelowPx = constraints.maxHeight - (anchorTop + metrics.lineHeight) - gapPx - marginPx
            val roomAbovePx = anchorTop - gapPx - marginPx
            val roomPx = if (roomBelowPx < with(density) { ComfortableListHeight.toPx() } && roomAbovePx > roomBelowPx) {
                roomAbovePx
            } else roomBelowPx
            val listMax = with(density) { roomPx.toDp() }.coerceIn(MinListHeight, MaxListHeight)
            val items = shown.items
            Box(
                Modifier.belowLineInPane(
                    anchor = { caretGeometry(anchor).let { (_, x, top) -> x to top } },
                    lineHeightPx = metrics.lineHeight,
                    gapPx = gapPx,
                    marginPx = marginPx,
                    minX = gutterWidthPx.roundToInt(),
                ),
            ) {
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
    lineHeightPx: Float,
    gutterWidthPx: Float,
) {
    val sigHelp = sig.help
    if (sigHelp != null && !sig.dismissed && engaged && sigHelp.signatures.isNotEmpty()) {
        val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
        // In the pane, not a `Popup` (see [aboveLineInPane]): it stays up for as long as the caret is in a call.
        SignatureHelpPopup(
            sigHelp,
            modifier = Modifier.aboveLineInPane(
                anchor = { caretGeometry(caretOffset).let { (_, x, top) -> x to top } },
                lineHeightPx = lineHeightPx,
                gapPx = gapPx,
                minX = gutterWidthPx.roundToInt(),
            ),
        )
    }
}

/** Lightbulb floating just ABOVE the caret — only when the caret is on a diagnostic that has fixes, the
 *  completion popup isn't showing, and the fix menu isn't already open. Tap → the fix list.
 *
 *  In the pane, not a `Popup` (see [aboveLineInPane]): the bulb stays up for as long as the caret rests on
 *  an error. */
@Composable
private fun LightbulbLayer(
    acts: EditorActionsController,
    interaction: EditorInteraction,
    showPopup: Boolean,
    engaged: Boolean,
    caretOffset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    lineHeightPx: Float,
    gutterWidthPx: Float,
) {
    if (acts.available.isNotEmpty() && acts.caretDiagnostic != null && !showPopup && !acts.menuOpen && engaged) {
        val density = LocalDensity.current
        val gapPx = with(density) { 6.dp.roundToPx() }
        // The touch selection toolbar anchors above this same line; when it's up, stack the bulb above it
        // (toolbar height + its 8dp gap) so a quick-fix like auto-import stays reachable.
        val toolbarLift =
            if (interaction.handlesVisible && interaction.lastInputWasTouch) {
                interaction.selectionToolbarHeightPx + with(density) { 8.dp.roundToPx() }
            } else 0
        FloatingLightbulb(
            onClick = { acts.openMenu() },
            modifier = Modifier.aboveLineInPane(
                anchor = { caretGeometry(caretOffset).let { (_, x, top) -> x to top } },
                lineHeightPx = lineHeightPx,
                gapPx = gapPx,
                minX = gutterWidthPx.roundToInt(),
                liftPx = toolbarLift,
            ),
        )
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

/** The source-navigation dropdown — the Go-to menu and its target picker — anchored under the caret line, the
 *  same position machinery as the code-actions menu. Dismisses on an outside tap / Esc. */
@Composable
private fun NavMenuLayer(
    state: NavMenuState?,
    actions: List<UiAction>,
    menu: UiMenuGroup,
    onMenuAction: (UiActionItem) -> Unit,
    engaged: Boolean,
    caretOffset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    onOption: (UiNavOption) -> Unit,
    onAction: (UiAction) -> Unit,
    onPick: (UiNavTarget) -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == null || !engaged) return
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
    Popup(popupPositionProvider = positionProvider, onDismissRequest = onDismiss) {
        BoxWithConstraints {
            val width = if (maxWidth < 600.dp) (maxWidth * 0.9f).coerceIn(240.dp, 360.dp) else 360.dp
            NavMenu(state, actions, width, onOption, onAction, onPick, menu, onMenuAction, onRename)
        }
    }
}

/** Whole-word matches of [word] in [doc], and the exact document and word they were computed from. */
/** Where the code area starts below the sticky headers, written by the editor's draw and read by the caret's,
 *  which runs after it in the same frame. Not snapshot state: a draw must not write state another draw reads. */
private class CaretClip {
    var codeTop = 0f
}

/** The identifier under the caret: its text and where it starts. */
private data class CaretWord(val text: String, val start: Int)

/** The usages tinted for the identifier under the caret: [reads], and [writes] (assignments, declarations) in
 *  their own tint. Both sorted and non-overlapping. */
internal class OccurrenceTint(val reads: List<Match>, val writes: List<Match>) {
    companion object {
        val EMPTY = OccurrenceTint(emptyList(), emptyList())
    }
}

/**
 * One settled answer. [resolved] marks a language backend's answer, which holds for any caret on one of its
 * ranges (they are all the same symbol); a textual one holds for any caret on the same [word].
 */
private class OccurrenceResult(val doc: EditorDocument, val word: String, val resolved: Boolean, val tint: OccurrenceTint)

/** How long the caret and the text must stay put before the identifier under the caret is highlighted. */
private const val OCCURRENCE_SETTLE_MS = 300L

/** The files whose backend can resolve the usages of a symbol in place; elsewhere only the textual scan runs. */
private fun resolvesUsages(path: String): Boolean = path.endsWith(".kt") || path.endsWith(".kts") || path.endsWith(".java")

/**
 * The usages of the identifier under the caret ([word]) in [doc], for the occurrence tint, once the caret and
 * the text have settled for [OCCURRENCE_SETTLE_MS], off the composition and off the main thread, so a keystroke
 * inside an identifier never pays it.
 *
 * In a Kotlin or Java file the backend is asked first, and its answer is the resolved one: only the names that
 * are this symbol (another function's local `x` stays unlit) with writes apart, as IntelliJ shows them. When it
 * has nothing to say (an unresolved name, a backend without the query) the whole-word textual scan stands in.
 * A result is shown only for the exact document it was computed from, so while typing the old highlights drop
 * at once instead of drawing at stale offsets. Empty when there is no word, on a large file, or with fewer than
 * two uses.
 */
@Composable
private fun rememberOccurrences(
    session: EditorSession, doc: EditorDocument, word: CaretWord?, largeFile: Boolean, path: String, backend: IdeBackend,
): OccurrenceTint {
    var result by remember(session) { mutableStateOf<OccurrenceResult?>(null) }
    LaunchedEffect(doc, word, largeFile) {
        if (largeFile || word == null) return@LaunchedEffect // a whole-file scan is too costly on a large buffer
        // A caret moving between two uses of the symbol already shown keeps that answer: nothing to ask.
        result?.let { r -> if (r.doc === doc && r.resolved && r.covers(word.start)) return@LaunchedEffect }
        delay(OCCURRENCE_SETTLE_MS)
        val text = doc.awaitText() // shared with the analysis pass, which reads the same revision
        val usages = if (!resolvesUsages(path)) null else try {
            backend.editor.usageHighlights(path, text, word.start)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
        // The backend's answer counts only when it is about the name under the caret.
        val resolved = usages?.takeIf { u -> u.any { word.start >= it.startOffset && word.start < it.endOffset } }
        result = if (resolved != null) {
            val tint = if (resolved.size < 2) OccurrenceTint.EMPTY // a lone use: nothing to point out
            else OccurrenceTint(
                reads = resolved.filter { !it.write }.map { Match(it.startOffset, it.endOffset) },
                writes = resolved.filter { it.write }.map { Match(it.startOffset, it.endOffset) },
            )
            OccurrenceResult(doc, word.text, resolved = true, tint)
        } else {
            val found = withContext(Dispatchers.Default) {
                findMatches(text, word.text, FindOptions(caseSensitive = true, wholeWord = true))
                    .takeIf { it.size >= 2 } // only meaningful when it appears more than once
                    ?: emptyList()
            }
            OccurrenceResult(doc, word.text, resolved = false, OccurrenceTint(found, emptyList()))
        }
    }
    val r = result ?: return OccurrenceTint.EMPTY
    if (largeFile || word == null || r.doc !== doc) return OccurrenceTint.EMPTY
    val holds = if (r.resolved) r.covers(word.start) else r.word == word.text
    return if (holds) r.tint else OccurrenceTint.EMPTY
}

/** Whether a resolved answer includes the name starting at [start] (so it is about the same symbol). An empty
 *  answer (a lone use) holds only for the name it was asked about, which [word] no longer tells; drop it. */
private fun OccurrenceResult.covers(start: Int): Boolean =
    tint.reads.any { start >= it.start && start < it.end } || tint.writes.any { start >= it.start && start < it.end }

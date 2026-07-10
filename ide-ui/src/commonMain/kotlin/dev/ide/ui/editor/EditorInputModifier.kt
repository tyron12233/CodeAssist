package dev.ide.ui.editor

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.Scrollable2DState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.scrollable2D
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.EditorImeHandle
import dev.ide.ui.editor.core.EditorImeOptions
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.editorTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The editor's input plumbing as a single [Modifier] chain: platform IME bridge + focus + key events, pinch-to-
 * zoom, scrolling (2D or orientation-locked), mouse-hover fold hints, and the touch/mouse tap + drag-to-select
 * gestures. Extracted from [CodeEditor] so its emission composable stays under ART's per-method instruction
 * limit; the drawing pass ([drawEditor]) stays with the render state in [CodeEditor].
 *
 * Not `@Composable`: every modifier used here is a plain node (no composition needed), so this builder carries
 * no group scaffolding of its own. The gesture blocks read live geometry through [geometry]'s methods; the
 * pointer-input keys ([metrics]/[gutterWidthPx]/[wrapActive]) are the explicit values the gestures captured, so
 * a zoom re-launches them exactly as before.
 */
internal fun Modifier.editorInput(
    session: EditorSession,
    geometry: EditorGeometry,
    interaction: EditorInteraction,
    acts: EditorActionsController,
    completion: CompletionController,
    focus: FocusRequester,
    editorIme: EditorImeHandle,
    scope: CoroutineScope,
    softKeyboardSuggestions: Boolean,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    wrapActive: Boolean,
    pinchZoom: Boolean,
    liveScale: State<Float>,
    onFontScaleChange: (Float) -> Unit,
    useTwoAxisScroll: Boolean,
    scroll2D: Scrollable2DState,
    vScroll: ScrollableState,
    hScroll: ScrollableState,
    onViewportSize: (IntSize) -> Unit,
    onContentPositioned: (Offset) -> Unit,
    onFocusedChange: (Boolean) -> Unit,
    onDismissQuickDoc: () -> Unit,
    onPreviewKey: (KeyEvent) -> Boolean,
    onKey: (KeyEvent) -> Boolean,
): Modifier = this
    .fillMaxSize()
    .onSizeChanged { onViewportSize(it) }
    .onGloballyPositioned { onContentPositioned(it.positionInWindow()) }
    .editorTextInput(session, editorIme, EditorImeOptions(softKeyboardSuggestions))
    .focusRequester(focus)
    .onFocusChanged { onFocusedChange(it.isFocused) }
    .focusable()
    .onPreviewKeyEvent(onPreviewKey)
    .onKeyEvent(onKey)
    // pinch-to-zoom: a 2-finger gesture scales the editor font. Watched on the Initial pass (outer→inner) so a
    // pinch is claimed for zoom BEFORE the scroll containers below treat the two-finger movement as a pan. Acts
    // ONLY when ≥2 fingers are down, so single-finger scroll/selection still flow to the detectors below.
    .then(
        if (!pinchZoom) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        val z = event.calculateZoom()
                        if (z != 1f) onFontScaleChange(clampFontScale(liveScale.value * z))
                        // Consume the whole 2-finger gesture (even on a no-zoom frame) so it stays a pure pinch —
                        // the scrollable/tap detectors below see consumed changes and skip it.
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
    )
    // Scroll: either one 2D state (free diagonal pan, touch) or the orientation-locked pair (the classic
    // one-axis-per-gesture drag + desktop wheel/trackpad).
    .then(
        if (useTwoAxisScroll) Modifier.scrollable2D(scroll2D)
        else Modifier
            .scrollable(vScroll, Orientation.Vertical, reverseDirection = true)
            .scrollable(hScroll, Orientation.Horizontal, reverseDirection = true),
    )
    // Mouse hover (desktop): track the hovered line so an expandable fold shows its chevron on hover. Observation
    // only — never consumes, so it doesn't disturb taps/scroll/drag.
    .pointerInput(session, metrics, gutterWidthPx, wrapActive) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull()
                when {
                    change?.type != PointerType.Mouse -> {}
                    event.type == PointerEventType.Exit -> interaction.hoveredLine = -1
                    event.type == PointerEventType.Move || event.type == PointerEventType.Enter -> {
                        val ln = geometry.lineAtY(change.position.y)
                        if (ln != interaction.hoveredLine) interaction.hoveredLine = ln
                    }
                }
            }
        }
    }
    // Keyed on metrics/gutterWidth too: a gesture block captures the geometry helpers once when it launches, so
    // it must re-launch when a zoom rescales the line metrics — otherwise a post-zoom tap maps through stale
    // lineHeight to the wrong line.
    .pointerInput(session, metrics, gutterWidthPx, wrapActive) {
        var longPressed = false
        detectTapGestures(
            // Place the caret in onPress (fires on the first finger-lift) instead of onTap — when onDoubleTap is
            // set, onTap is held back by the double-tap timeout (~300ms), which is the lag. tryAwaitRelease()
            // returns on that first up; false if the gesture became a scroll (cancelled).
            onPress = { pos ->
                longPressed = false
                val pressMark = TimeSource.Monotonic.markNow()
                val released = tryAwaitRelease()
                val nearArm = interaction.tripleArmed && (pos - interaction.tripleArmPos).getDistance() < 60f
                val doubleTapSecondTap =
                    nearArm && interaction.tripleArmMark?.let { (it - pressMark).isPositive() } == true
                if (released && !longPressed && !doubleTapSecondTap) {
                    focus.requestFocus()
                    onDismissQuickDoc() // a tap in the editor dismisses an open quick-doc popup
                    // Third quick tap near the double-tap → select the whole line.
                    val triple = nearArm
                    // A tap on a gutter error/warning glyph opens that line's diagnostic sheet.
                    val gutterDiag =
                        if (pos.x < gutterWidthPx) acts.diagnosticOnLine(geometry.lineAtY(pos.y)) else null
                    val stickyHit = geometry.stickyHeaderHit(pos)
                    when {
                        stickyHit != null -> { // tapped a pinned sticky header → jump to that declaration
                            session.setCaret(stickyHit.nameOffset.coerceIn(0, session.doc.length))
                            if (interaction.lastInputWasTouch) editorIme.show()
                        }

                        geometry.foldActionAt(pos) -> {} // toggled/expanded a fold (gutter chevron or placeholder)
                        triple -> {
                            interaction.tripleArmed = false; interaction.tripleArmJob?.cancel()
                            session.selectLineAt(geometry.offsetAt(pos))
                            if (interaction.lastInputWasTouch) interaction.handlesVisible = true
                        }

                        gutterDiag != null -> acts.openSheet(gutterDiag)
                        else -> {
                            val newCaret = geometry.offsetAt(pos)
                            // Re-tap the existing caret position to TOGGLE the Paste/Select-all toolbar; a first
                            // tap just places the caret. Tapping a new spot hides it.
                            val prev = session.selection
                            val reTap = prev.collapsed && prev.start == newCaret
                            session.setCaret(newCaret)
                            if (interaction.lastInputWasTouch) {
                                interaction.handlesVisible = reTap && !interaction.handlesVisible
                                editorIme.show() // explicit tap → raise the keyboard
                            }
                        }
                    }
                }
            },
            onDoubleTap = { pos ->
                focus.requestFocus()
                session.selectWordAt(geometry.offsetAt(pos))
                if (interaction.lastInputWasTouch) interaction.handlesVisible = true
                // arm triple-tap: a quick third tap nearby (within the window below) selects the line
                interaction.tripleArmed = true
                interaction.tripleArmPos = pos
                interaction.tripleArmMark = TimeSource.Monotonic.markNow()
                interaction.tripleArmJob?.cancel()
                interaction.tripleArmJob = scope.launch { delay(320.milliseconds); interaction.tripleArmed = false }
            },
            onLongPress = { pos ->
                longPressed = true
                focus.requestFocus()
                // Long-press → select the word under the finger and raise the selection chrome (handles + the
                // floating toolbar), the standard Android text gesture.
                completion.dismiss()
                session.selectWordAt(geometry.offsetAt(pos))
                if (interaction.lastInputWasTouch) {
                    interaction.handlesVisible = true
                    editorIme.show() // explicit long-press → raise the keyboard
                }
            },
        )
    }
    // Innermost pointer handler: it sees events first on the Main pass, so it can claim a mouse drag (and the
    // touch selection-handle drags) BEFORE the scroll containers above swallow the movement. Mouse gestures are
    // owned end-to-end here; touch taps fall through unconsumed to detectTapGestures.
    .pointerInput(session, metrics, gutterWidthPx, wrapActive) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            if (down.type == PointerType.Mouse) {
                interaction.lastInputWasTouch = false
                focus.requestFocus()
                down.consume() // keep the scroll containers + detectTapGestures out of it
                // Count consecutive clicks ourselves: 1 → caret, 2 → word, 3 → line, then wrap.
                val near = (down.position - interaction.mouseLastClickPos).getDistance() < 24f
                interaction.mouseClicks =
                    if (near && down.uptimeMillis - interaction.mouseLastClickMs <= 300L) (interaction.mouseClicks % 3) + 1 else 1
                interaction.mouseLastClickMs = down.uptimeMillis
                interaction.mouseLastClickPos = down.position
                val anchor = geometry.offsetAt(down.position)
                // A click on a gutter error/warning glyph opens that line's diagnostic sheet.
                val gutterDiag = if (down.position.x < gutterWidthPx)
                    acts.diagnosticOnLine(geometry.lineAtY(down.position.y)) else null
                when {
                    interaction.mouseClicks == 1 && geometry.foldActionAt(down.position) -> {} // fold chevron / placeholder
                    gutterDiag != null && interaction.mouseClicks == 1 -> acts.openSheet(gutterDiag)
                    interaction.mouseClicks == 2 -> session.selectWordAt(anchor)
                    interaction.mouseClicks == 3 -> session.selectLineAt(anchor)
                    else -> session.setCaret(anchor)
                }
                // Anchor the drag at the current selection start so a drag after a double/triple click still
                // extends from where the click landed.
                val dragAnchor = session.selection.start
                drag(down.id) { change ->
                    session.setSelectionRange(dragAnchor, geometry.offsetAt(change.position))
                    change.consume()
                }
                return@awaitEachGesture
            }
            // Touch: only claim the gesture for a selection-handle drag; otherwise leave it unconsumed so the
            // scrollables and detectTapGestures (tap/double-tap) still run.
            interaction.lastInputWasTouch = true
            val handleRadius = 14.dp.toPx()
            fun handleCenter(offset: Int): Offset {
                val (_, x, top) = geometry.caretGeometry(offset)
                return Offset(x, top + metrics.lineHeight + handleRadius * 0.6f)
            }

            val sel = session.selection
            val hit: Char? = when {
                interaction.handlesVisible && !sel.collapsed &&
                    (down.position - handleCenter(sel.min)).getDistance() < handleRadius -> 'a'

                interaction.handlesVisible && !sel.collapsed &&
                    (down.position - handleCenter(sel.max)).getDistance() < handleRadius -> 'b'

                interaction.handlesVisible && sel.collapsed &&
                    (down.position - handleCenter(sel.start)).getDistance() < handleRadius -> 'c'

                else -> null
            }
            if (hit != null) {
                down.consume()
                // The handle sits ~a line below its anchor, so the finger covers the row below. Lift the
                // hit-point back up to the anchored line so dragging tracks what you see.
                val lift = metrics.lineHeight * 0.5f + handleRadius * 0.6f
                drag(down.id) { change ->
                    val off = geometry.offsetAt(change.position.copy(y = change.position.y - lift))
                    when (hit) {
                        'a' -> session.setSelectionRange(session.selection.max, off)
                        'b' -> session.setSelectionRange(session.selection.min, off)
                        else -> session.setCaret(off)
                    }
                    change.consume()
                }
            }
        }
    }

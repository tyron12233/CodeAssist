package dev.ide.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Per-tab editor interaction state: the caret glide animation, the hovered line (desktop), the touch selection
 * chrome (handles/toolbar), and the triple-tap + mouse click-count tracking. Pulled out of [CodeEditor] to keep
 * its emission composable under ART's per-method instruction limit.
 *
 * [rememberEditorInteraction] keys this on the [EditorSession], so every field resets when the tab changes —
 * matching the original `remember(path)`/`remember(session)` keys (`path` and `session` co-vary per tab). The
 * three flags that were deliberately UN-keyed in the original (`isFocused`, `blinkOn`, `lastInputWasTouch`) stay
 * in [CodeEditor]: `isFocused` in particular must persist across a tab switch, since the focusable node keeps
 * its real focus and `onFocusChanged` would not re-fire to restore the flag.
 */
@Stable
internal class EditorInteraction(initialRevision: Int) {
    // Caret position animation: the caret glides to its new spot instead of teleporting. Tracked in content
    // space (scroll-independent) so a scroll doesn't fight the animation; the renderer subtracts the scroll.
    val caretAnim = Animatable(Offset.Zero, Offset.VectorConverter)
    var caretAnimReady by mutableStateOf(false)
    // The last text revision the caret animation reacted to — lets it tell a typing-driven caret advance (text
    // changed) from a navigation move (arrows/click/go-to), so typing snaps and only navigation glides.
    var caretAnimRev by mutableIntStateOf(initialRevision)

    /** Whether the last input was a touch (vs. mouse/keyboard) — gates the touch selection chrome. */
    var lastInputWasTouch by mutableStateOf(false)

    // The document line the mouse is hovering (desktop) — drives showing an expandable fold chevron on hover.
    // -1 when the pointer is a touch or has left the editor.
    var hoveredLine by mutableIntStateOf(-1)
    var handlesVisible by mutableStateOf(false)

    // Measured height of the floating touch selection toolbar (px) while it's shown — the lightbulb anchors
    // above the same caret line, so it reads this to stack itself above the toolbar instead of under it.
    var selectionToolbarHeightPx by mutableIntStateOf(0)

    // triple-tap → select line: a double-tap "arms" this for a brief window; the next quick tap nearby then
    // selects the whole line instead of placing the caret.
    var tripleArmed by mutableStateOf(false)
    var tripleArmPos by mutableStateOf(Offset.Zero)
    var tripleArmJob by mutableStateOf<Job?>(null)
    // detectTapGestures runs onPress for the second tap of a double-tap too, AFTER onDoubleTap arms — so the
    // triple branch requires the press to have begun after this mark (a genuine third tap).
    var tripleArmMark by mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null)

    // Mouse click-count tracking (single → caret, double → word, triple → line). We count clicks ourselves so
    // the mouse path can own the whole gesture (click + drag-to-select) and consume the drag.
    var mouseClicks by mutableIntStateOf(0)
    var mouseLastClickMs by mutableLongStateOf(0L)
    var mouseLastClickPos by mutableStateOf(Offset.Zero)
}

@Composable
internal fun rememberEditorInteraction(
    session: EditorSession,
    geometry: EditorGeometry,
    wordWrap: Boolean,
): EditorInteraction {
    val state = remember(session) { EditorInteraction(session.textRevision) }

    // Content-space caret target; the animation is keyed on it so it re-runs when the caret moves.
    val caretTarget = geometry.caretTargetContent()
    val viewportHeight = geometry.viewport.value.height
    val textRevision = session.textRevision
    LaunchedEffect(caretTarget) {
        // Snap on the first placement (file open) and across off-screen jumps (go-to-symbol, PageUp/Down) — a
        // glide across the whole document reads as a glitch; glide only for moves within a viewport.
        val far = viewportHeight > 0 && abs(caretTarget.y - state.caretAnim.value.y) > viewportHeight
        // Typing advances the caret on (nearly) every keystroke; gliding then keeps a 60fps spring redraw loop
        // running the whole time someone types — costly on a phone. Snap when the buffer changed (typing/edit),
        // and reserve the glide for pure caret moves (arrows, taps, go-to).
        val edited = textRevision != state.caretAnimRev
        state.caretAnimRev = textRevision
        // Word wrap: snap, don't glide. A single caret move can cross several wrapped sub-rows of one line, so
        // the content-space glide would sweep diagonally across rows; IntelliJ snaps the caret too.
        if (!state.caretAnimReady || far || edited || wordWrap) {
            state.caretAnimReady = true
            state.caretAnim.snapTo(caretTarget)
        } else {
            state.caretAnim.animateTo(
                caretTarget,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
            )
        }
    }
    return state
}

/** The animated content-space caret position (read in the draw phase → redraws per frame). */
internal val EditorInteraction.caretContent: Offset get() = caretAnim.value

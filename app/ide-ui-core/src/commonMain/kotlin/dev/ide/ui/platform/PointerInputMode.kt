package dev.ide.ui.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Whether the user is driving the UI with a mouse or trackpad rather than a finger. It starts from the platform
 * ([isMobilePlatform] means touch) and then follows the last pointer event, so a tablet or phone with a mouse
 * connected gets hover tooltips, hover-revealed actions and the denser desktop sizing, and goes back to touch
 * sizing as soon as the screen is touched.
 *
 * Only the pointer-dependent parts of the UI read this. Behaviour that belongs to the platform rather than the
 * pointer (system Back, edge-swipe drawers, permission prompts, screen transitions) stays on [isMobilePlatform].
 */
val LocalMouseInput = compositionLocalOf { !isMobilePlatform }

/** The pointer in use is a finger or a stylus: size controls for touch and show what hover would reveal. */
val touchInput: Boolean
    @Composable @ReadOnlyComposable
    get() = !LocalMouseInput.current

/**
 * Where the pointer event being dispatched came from, as far as the toolkit's [PointerType] can't say.
 *
 * Android reports some mice as a stylus: the emulator's pointer (a virtio tablet with `source=MOUSE|STYLUS`)
 * and some desktop-mode tablets deliver `TOOL_TYPE_STYLUS`, so Compose calls them [PointerType.Stylus]. The
 * platform knows better from the event's input source, and [pointerSourceTracking] records it here before the
 * event reaches the UI underneath. Main thread only; nothing else writes it.
 */
object PointerSource {
    /** The current event came from a mouse or trackpad input source, whatever its tool type. */
    var mouseSourced: Boolean = false
}

/**
 * Records [PointerSource] for every event that reaches this node, ahead of the nodes inside it. A no-op where
 * the toolkit's pointer type is already right (desktop, iOS).
 */
expect fun Modifier.pointerSourceTracking(): Modifier

/**
 * This change is driven by a mouse or trackpad: [PointerType.Mouse], or a "stylus" that the platform says came
 * from a mouse source (see [PointerSource]). Use this rather than comparing to [PointerType.Mouse], or the
 * Android emulator and some tablets get the touch gestures.
 */
val PointerInputChange.isMouse: Boolean
    get() = type == PointerType.Mouse || (type == PointerType.Stylus && PointerSource.mouseSourced)

/**
 * Provides [LocalMouseInput] to [content], updated from every pointer event that reaches it. Watches on the
 * Initial pass and never consumes, so it takes nothing from the UI underneath. Also the root of
 * [pointerSourceTracking], so [isMouse] is right everywhere in the window.
 */
@Composable
fun PointerInputModeHost(content: @Composable () -> Unit) {
    var mouse by remember { mutableStateOf(!isMobilePlatform) }
    Box(
        Modifier.fillMaxSize().pointerSourceTracking().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when {
                        change.isMouse -> mouse = true
                        change.type != PointerType.Unknown -> mouse = false
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalMouseInput provides mouse) { content() }
    }
}

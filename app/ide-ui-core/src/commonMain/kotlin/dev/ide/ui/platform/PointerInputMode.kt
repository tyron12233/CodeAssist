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
 * Provides [LocalMouseInput] to [content], updated from every pointer event that reaches it. Watches on the
 * Initial pass and never consumes, so it takes nothing from the UI underneath.
 */
@Composable
fun PointerInputModeHost(content: @Composable () -> Unit) {
    var mouse by remember { mutableStateOf(!isMobilePlatform) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.changes.firstOrNull()?.type) {
                        PointerType.Mouse -> mouse = true
                        PointerType.Touch, PointerType.Stylus, PointerType.Eraser -> mouse = false
                        else -> {}
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalMouseInput provides mouse) { content() }
    }
}

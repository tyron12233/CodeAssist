package dev.ide.ui.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A right-click with a mouse (a Chromebook, a tablet with a mouse, desktop mode). Fires on release over the
 * element, like the desktop's.
 *
 * Watched on the Initial pass and consumed: on Android a secondary-button press is a "down" to the tap
 * detectors, so a `clickable` around this would otherwise treat a right-click as a click too.
 */
actual fun Modifier.secondaryClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (!enabled) this else this.pointerInput(onClick) {
        awaitEachGesture {
            val press = awaitPointerEvent(PointerEventPass.Initial)
            if (press.type != PointerEventType.Press || !press.buttons.isSecondaryPressed) return@awaitEachGesture
            press.changes.forEach { it.consume() }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.buttons.areAnyPressed) continue
                val change = event.changes.firstOrNull() ?: break
                val p = change.position
                if (p.x >= 0 && p.y >= 0 && p.x < size.width && p.y < size.height) onClick()
                break
            }
        }
    }

package dev.ide.ui.platform

import android.view.InputDevice
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy

/**
 * Reads the input source off the raw [android.view.MotionEvent]. The spy watches on the Initial pass from the
 * root, so the flag is set before any node inside sees the same event.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.pointerSourceTracking(): Modifier =
    this.motionEventSpy { PointerSource.mouseSourced = it.isFromSource(InputDevice.SOURCE_MOUSE) }

package dev.ide.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import dev.ide.ui.platform.LocalMouseInput
import dev.ide.ui.platform.PointerInputModeHost
import kotlin.test.Test
import kotlin.test.assertEquals

/** [PointerInputModeHost] follows the pointer in use: a touch switches the UI to touch, a mouse back again. */
class PointerInputModeTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun followsTheLastPointer() {
        var mouse: Boolean? = null
        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            PointerInputModeHost { mouse = LocalMouseInput.current }
        }
        try {
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            frame()
            assertEquals(true, mouse, "a desktop host starts in mouse mode")

            val p = Offset(100f, 100f)
            scene.sendPointerEvent(PointerEventType.Press, p, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, p, type = PointerType.Touch)
            frame()
            assertEquals(false, mouse, "a touch switches to touch mode")

            scene.sendPointerEvent(PointerEventType.Move, Offset(110f, 100f), type = PointerType.Mouse)
            frame()
            assertEquals(true, mouse, "moving a mouse switches back")
        } finally {
            scene.close()
        }
    }
}

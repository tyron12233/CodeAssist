package dev.ide.android.layouteditor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dragged view's ghost follows the finger in screen coordinates. Under Arabic it was placed with a
 * direction-aware offset, so it started at the far edge and moved left when the finger moved right.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DragGhostRtlTest {

    /** The horizontal extent of what the ghost painted, with the finger at [x]. */
    private fun ghostSpan(direction: LayoutDirection, x: Float): IntRange {
        val drag = DragState().apply {
            payload = DragPayload.Move(parseTestLayout("<Button/>").root!!)
            position = Offset(x, 200f)
        }
        val scene = ImageComposeScene(600, 400, Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme { Box(Modifier.fillMaxSize()) { DragGhost(drag, Offset.Zero) } }
            }
        }
        try {
            val image = scene.render()
            val bitmap = Bitmap().also { it.allocN32Pixels(image.width, image.height); image.readPixels(it, 0, 0) }
            // The row through the middle of the ghost (it is drawn 64 above the finger, 44 tall).
            val painted = (0 until image.width).filter { (bitmap.getColor(it, 158) ushr 24) > 200 }
            assertTrue(painted.isNotEmpty(), "the ghost drew nothing ($direction)")
            return painted.first()..painted.last()
        } finally {
            scene.close()
        }
    }

    @Test
    fun theGhostSitsAtTheFingerInEitherDirection() {
        val ltr = ghostSpan(LayoutDirection.Ltr, 300f)
        val rtl = ghostSpan(LayoutDirection.Rtl, 300f)
        assertEquals(ltr.first, rtl.first, "left edge, ltr=$ltr rtl=$rtl")
        assertTrue(260 in ltr, "the ghost starts 40 left of the finger: $ltr")
    }

    @Test
    fun movingTheFingerRightMovesTheGhostRightUnderRtl() {
        val before = ghostSpan(LayoutDirection.Rtl, 200f)
        val after = ghostSpan(LayoutDirection.Rtl, 320f)
        assertEquals(120, after.first - before.first, "before=$before after=$after")
    }
}

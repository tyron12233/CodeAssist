package dev.ide.ui.editor.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiVectorPath
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [UiDrawablePainter] renders a parsed `res/drawable` XML model — the path that lets `painterResource(R.drawable
 * .x)` in the on-device Compose preview resolve a VECTOR drawable (previously only bitmaps decoded). Verifies the
 * intrinsic-size logic (pure) and that a red vector actually draws red pixels (headless via [ImageComposeScene]).
 */
class UiDrawablePainterTest {

    @Test
    fun vectorIntrinsicSizeScalesDpByDensity() {
        val vec = UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f, paths = emptyList(),
        )
        assertEquals(Size(48f, 48f), UiDrawablePainter(vec, density = 2f).intrinsicSize, "24dp @ density 2 = 48px")
    }

    @Test
    fun aDrawableWithNoIntrinsicSizeReportsUnspecified() {
        assertEquals(Size.Unspecified, UiDrawablePainter(UiDrawable.SolidColor(0xFF00FF00L), density = 2f).intrinsicSize)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aRedVectorDrawsRedPixels() {
        // A 24x24 vector whose single path fills the whole viewport with opaque red.
        val red = UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f,
            paths = listOf(
                UiVectorPath(
                    pathData = "M0,0 L24,0 L24,24 L0,24 Z",
                    fillColor = 0xFFFF0000L, strokeColor = null, strokeWidthVp = 0f, fillAlpha = 1f, strokeAlpha = 1f,
                ),
            ),
        )
        val painter = UiDrawablePainter(red, density = 2f)

        val scene = ImageComposeScene(width = 48, height = 48, density = Density(2f)) {
            Image(painter = painter, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        val argb = try {
            scene.render()
            val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            ImageIO.read(ByteArrayInputStream(png)).getRGB(24, 24)
        } finally {
            scene.close()
        }
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        assertTrue(r > 200 && g < 60 && b < 60, "vector center should render red, got #%06X".format(argb and 0xFFFFFF))
    }
}

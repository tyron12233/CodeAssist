package dev.ide.ui.editor.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiVectorGroup
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
 * intrinsic-size logic (pure), that a red vector actually draws red pixels, and that a `<group>`'s transform
 * and `<clip-path>` move and cut the geometry they wrap (all headless via [ImageComposeScene]).
 */
class UiDrawablePainterTest {

    @Test
    fun vectorIntrinsicSizeScalesDpByDensity() {
        val vec = UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f, nodes = emptyList(),
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
            nodes = listOf(
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

    /**
     * A `<group>`'s translate has to move its children: the quadrant a path draws in is the whole point of the
     * node tree. Flattening the tree (the previous model) drew this square in the top-left instead.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aGroupTranslateMovesItsChildIntoTheOppositeQuadrant() {
        // A 12x12 red square at the viewport origin, pushed to the bottom-right corner by its group.
        val moved = UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f,
            nodes = listOf(
                UiVectorGroup(
                    translateX = 12f, translateY = 12f,
                    children = listOf(redSquare("M0,0 L12,0 L12,12 L0,12 Z")),
                ),
            ),
        )
        assertTrue(isRed(pixelAt(moved, 36, 36)), "the translated square should land in the bottom-right quadrant")
        assertTrue(!isRed(pixelAt(moved, 12, 12)), "the top-left quadrant it started in should now be empty")
    }

    /** A group's `<clip-path>` must cut its children: only the clipped half of a full-bleed path survives. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aGroupClipPathCutsItsChildToTheClipOutline() {
        val clipped = UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f,
            nodes = listOf(
                UiVectorGroup(
                    clipPathData = "M0,0 L12,0 L12,24 L0,24 Z", // the left half only
                    children = listOf(redSquare("M0,0 L24,0 L24,24 L0,24 Z")),
                ),
            ),
        )
        assertTrue(isRed(pixelAt(clipped, 12, 24)), "the left half is inside the clip and should draw")
        assertTrue(!isRed(pixelAt(clipped, 36, 24)), "the right half is outside the clip and should be cut")
    }

    private fun redSquare(pathData: String) = UiVectorPath(
        pathData = pathData,
        fillColor = 0xFFFF0000L, strokeColor = null, strokeWidthVp = 0f, fillAlpha = 1f, strokeAlpha = 1f,
    )

    private fun isRed(argb: Int): Boolean =
        ((argb shr 16) and 0xFF) > 200 && ((argb shr 8) and 0xFF) < 60 && (argb and 0xFF) < 60

    /** The ARGB of the pixel at ([x], [y]) in a 48x48 render of [drawable] (24dp @ density 2). */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun pixelAt(drawable: UiDrawable, x: Int, y: Int): Int {
        val painter = UiDrawablePainter(drawable, density = 2f)
        val scene = ImageComposeScene(width = 48, height = 48, density = Density(2f)) {
            Image(painter = painter, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        return try {
            scene.render()
            val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            ImageIO.read(ByteArrayInputStream(png)).getRGB(x, y)
        } finally {
            scene.close()
        }
    }
}

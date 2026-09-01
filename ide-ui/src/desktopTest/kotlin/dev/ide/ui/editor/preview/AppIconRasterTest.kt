package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import dev.ide.ui.backend.UiAppIconPreview
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiVectorPath
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The launcher-icon rasteriser: the adaptive-icon geometry (only the central 72 of the 108 authored units is
 * visible, so a safe-zone-sized layer must fill the whole icon), the launcher masks, the opaque store render,
 * and that the result actually encodes to a PNG.
 */
class AppIconRasterTest {

    private val blue = 0xFF0000FFL
    private val red = 0xFFFF0000L

    /** A layer in the 108-unit box whose single path covers [pathData]. */
    private fun layer(pathData: String, argb: Long) = UiDrawable.Vector(
        widthDp = 108f, heightDp = 108f, viewportWidth = 108f, viewportHeight = 108f, rootAlpha = 1f,
        nodes = listOf(UiVectorPath(pathData, argb, null, 0f, 1f, 1f)),
    )

    private fun preview(foreground: String) = UiAppIconPreview(
        background = UiDrawable.SolidColor(blue),
        foreground = layer(foreground, red),
        monochrome = null,
    )

    private fun ImageBitmap.at(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun Color.isRed() = red > 0.75f && green < 0.25f && blue < 0.25f && alpha > 0.9f
    private fun Color.isBlue() = blue > 0.75f && red < 0.25f && green < 0.25f && alpha > 0.9f

    @Test
    fun aLayerFillingTheSafeZoneFillsTheWholeIcon() {
        // The safe zone is the central 72 of 108 units, which the launcher scales up to the full icon.
        val image = assertNotNull(
            AppIconRaster.render(preview("M18,18h72v72h-72z"), pixels = 64, mask = IconMask.SQUARE),
        )
        assertTrue(image.at(32, 32).isRed(), "the centre is the foreground")
        assertTrue(image.at(1, 1).isRed(), "and so is the corner: the safe zone covers the visible icon")
    }

    @Test
    fun aLayerSmallerThanTheSafeZoneLeavesTheBackgroundVisible() {
        val image = assertNotNull(
            AppIconRaster.render(preview("M36,36h36v36h-36z"), pixels = 64, mask = IconMask.SQUARE),
        )
        assertTrue(image.at(32, 32).isRed(), "the centre is the foreground")
        assertTrue(image.at(2, 2).isBlue(), "the corner falls outside the artwork and shows the background")
    }

    @Test
    fun theBoxToSafeZoneRatioIsTheAdaptiveIconOne() {
        assertEquals(108f / 72f, AppIconRaster.BOX_TO_SAFE_ZONE)
    }

    @Test
    fun theCircleMaskClipsTheCorners() {
        val image = assertNotNull(
            AppIconRaster.render(preview("M0,0h108v108h-108z"), pixels = 64, mask = IconMask.CIRCLE),
        )
        assertTrue(image.at(32, 32).isRed(), "the centre draws")
        assertEquals(0f, image.at(0, 0).alpha, "the corner is outside the circle")
        assertEquals(0f, image.at(63, 63).alpha, "and so is the opposite one")
        assertTrue(image.at(32, 1).alpha > 0.9f, "the top edge midpoint is inside the circle")
    }

    @Test
    fun theRoundedMasksClipLessThanTheCircle() {
        fun cornerAlpha(mask: IconMask): Float =
            assertNotNull(AppIconRaster.render(preview("M0,0h108v108h-108z"), 64, mask)).at(1, 1).alpha

        assertEquals(0f, cornerAlpha(IconMask.CIRCLE))
        assertEquals(0f, cornerAlpha(IconMask.SQUIRCLE), "a squircle still cuts the very corner")
        assertTrue(cornerAlpha(IconMask.SQUARE) > 0.9f, "a square mask clips nothing")
    }

    @Test
    fun theSquareMaskKeepsEveryPixel() {
        val image = assertNotNull(
            AppIconRaster.render(preview("M0,0h108v108h-108z"), pixels = 32, mask = IconMask.SQUARE),
        )
        for (x in 0 until 32) {
            assertTrue(image.at(x, 0).alpha > 0.9f, "column $x of the top row should be painted")
        }
    }

    @Test
    fun anOpaqueRenderPaintsTheGroundAndIgnoresTheMask() {
        val image = assertNotNull(
            AppIconRaster.render(
                preview("M36,36h36v36h-36z"),
                pixels = 64,
                mask = IconMask.CIRCLE,
                opaqueBackground = Color(0xFF00FF00),
            ),
        )
        // A store listing image may not be transparent, so even the masked-away corner is filled.
        assertEquals(1f, image.at(0, 0).alpha)
        assertTrue(image.at(32, 32).isRed(), "the artwork still draws on top")
    }

    @Test
    fun theThemedRenderUsesTheMonochromeLayerWhenThereIsOne() {
        val themed = UiAppIconPreview(
            background = UiDrawable.SolidColor(blue),
            foreground = layer("M36,36h36v36h-36z", red),
            monochrome = layer("M18,18h72v72h-72z", 0xFF00FF00L),
        )
        val normal = assertNotNull(AppIconRaster.render(themed, 64, IconMask.SQUARE))
        val mono = assertNotNull(AppIconRaster.render(themed, 64, IconMask.SQUARE, monochrome = true))

        assertTrue(normal.at(2, 2).isBlue(), "the full-colour icon shows the background in the corner")
        assertTrue(mono.at(2, 2).green > 0.75f, "the themed layer is larger and covers the corner")
    }

    @Test
    fun theThemedRenderFallsBackToTheForegroundWhenNoMonochromeLayerExists() {
        val image = assertNotNull(
            AppIconRaster.render(preview("M18,18h72v72h-72z"), 64, IconMask.SQUARE, monochrome = true),
        )
        assertTrue(image.at(32, 32).isRed(), "with no themed layer the foreground is drawn")
    }

    @Test
    fun aBackgroundOnlyIconStillRenders() {
        val image = assertNotNull(
            AppIconRaster.render(
                UiAppIconPreview(UiDrawable.SolidColor(blue), foreground = null, monochrome = null),
                64, IconMask.SQUARE,
            ),
        )
        assertTrue(image.at(32, 32).isBlue())
    }

    @Test
    fun anEmptyPreviewProducesATransparentImageRatherThanFailing() {
        val image = assertNotNull(
            AppIconRaster.render(UiAppIconPreview(null, null, null), 32, IconMask.SQUARE),
        )
        assertEquals(0f, image.at(16, 16).alpha)
    }

    @Test
    fun aZeroSizedRenderIsRefused() {
        assertEquals(null, AppIconRaster.render(preview("M0,0h108v108h-108z"), 0, IconMask.SQUARE))
    }

    @Test
    fun everyDensityBucketRendersAtItsRequestedSize() {
        for (pixels in listOf(48, 72, 96, 144, 192, 512)) {
            val image = assertNotNull(
                AppIconRaster.render(preview("M18,18h72v72h-72z"), pixels, IconMask.SQUARE),
                "failed at $pixels",
            )
            assertEquals(pixels, image.width)
            assertEquals(pixels, image.height)
        }
    }

    @Test
    fun theRenderEncodesToAReadablePng() {
        val bytes = assertNotNull(
            AppIconRaster.renderPng(preview("M18,18h72v72h-72z"), pixels = 48, mask = IconMask.CIRCLE),
        )
        assertTrue(bytes.size > 100, "got ${bytes.size} bytes")
        // A PNG signature, then a decode through an independent reader.
        assertEquals(listOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()), bytes.take(4))

        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(48, decoded.width)
        assertEquals(48, decoded.height)
        val centre = decoded.getRGB(24, 24)
        assertTrue((centre shr 16 and 0xFF) > 200, "the encoded centre is still red")
    }

    @Test
    fun theOpaqueGroundComesFromAFlatBackgroundLayer() {
        assertEquals(Color(0xFF0000FF), AppIconRaster.opaqueGround(preview("M0,0h1v1h-1z")))
        assertEquals(
            Color.White,
            AppIconRaster.opaqueGround(UiAppIconPreview(null, null, null)),
            "with no flat background a store image falls back to white, never transparent",
        )
    }
}

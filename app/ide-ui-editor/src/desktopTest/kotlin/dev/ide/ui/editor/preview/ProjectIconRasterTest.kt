package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiLayer
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.backend.UiVectorPath
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rendering a project's own launcher icon for a store listing: the adaptive-icon geometry, the image layers
 * that a preview pane is allowed to skip and this is not, and the raster passthrough.
 */
class ProjectIconRasterTest {

    private val blue = 0xFF0000FFL
    private val red = 0xFFFF0000L

    /** A layer in the 108-unit box covering [pathData]. */
    private fun vector(pathData: String, argb: Long) = UiDrawable.Vector(
        widthDp = 108f, heightDp = 108f, viewportWidth = 108f, viewportHeight = 108f, rootAlpha = 1f,
        nodes = listOf(UiVectorPath(pathData, argb, null, 0f, 1f, 1f)),
    )

    private fun layers(foreground: UiDrawable, adaptive: Boolean) = UiDrawable.Layers(
        layers = listOf(
            UiLayer(UiDrawable.SolidColor(blue), 0f, 0f, 0f, 0f),
            UiLayer(foreground, 0f, 0f, 0f, 0f),
        ),
        adaptive = adaptive,
    )

    private fun ImageBitmap.at(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun Color.isRed() = red > 0.75f && green < 0.25f && blue < 0.25f && alpha > 0.9f
    private fun Color.isBlue() = blue > 0.75f && red < 0.25f && green < 0.25f && alpha > 0.9f

    /** A solid [argb] PNG, standing in for the image file an adaptive foreground references. */
    private fun pngBytes(argb: Int, edge: Int = 32): ByteArray {
        val image = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until edge) for (x in 0 until edge) image.setRGB(x, y, argb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun anAdaptiveIconIsDrawnTheSizeALauncherShowsIt() {
        // Its layers are authored in a 108-unit box of which only the central 72 is guaranteed visible, so
        // a foreground filling that safe zone has to reach the edge of the rendered icon.
        val image = assertNotNull(
            ProjectIconRaster.render(layers(vector("M18,18h72v72h-72z", red), adaptive = true), pixels = 64),
        )
        assertTrue(image.at(32, 32).isRed(), "the centre is the foreground")
        assertTrue(image.at(1, 1).isRed(), "the safe zone has to cover the whole icon")
    }

    @Test
    fun aPlainLayerListIsNotScaled() {
        // Not an adaptive icon: there is no safe zone to honour, and scaling it up would only crop it.
        val image = assertNotNull(
            ProjectIconRaster.render(layers(vector("M18,18h72v72h-72z", red), adaptive = false), pixels = 64),
        )
        assertTrue(image.at(32, 32).isRed(), "the centre is the foreground")
        assertTrue(image.at(1, 1).isBlue(), "the corner is outside the artwork and shows the background")
    }

    /**
     * The case the default templates and the asset wizard both produce: a vector background under a
     * `<bitmap>` foreground. The preview pane draws a placeholder for a nested bitmap, which as an icon
     * would publish a dashed grey box.
     */
    @Test
    fun aBitmapForegroundDrawsTheImageRatherThanAPlaceholder() {
        val logo = UiDrawable.Bitmap("drawable", "logo", "/project/res/drawable/logo.png")
        val bytes = runBlocking {
            ProjectIconRaster.renderPng(layers(logo, adaptive = true), pixels = 64) { path ->
                if (path == "/project/res/drawable/logo.png") pngBytes(0xFFFF0000.toInt()) else null
            }
        }

        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(assertNotNull(bytes))))
        assertEquals(64, image.width)
        val centre = Color(image.getRGB(32, 32))
        assertTrue(centre.isRed(), "the foreground image should be drawn, got $centre")
    }

    @Test
    fun anImageThatCannotBeReadIsLeftOutRatherThanFailingTheIcon() {
        val logo = UiDrawable.Bitmap("drawable", "logo", "/project/res/drawable/gone.png")
        val bytes = runBlocking {
            ProjectIconRaster.renderPng(layers(logo, adaptive = true), pixels = 64) { null }
        }

        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(assertNotNull(bytes))))
        assertTrue(Color(image.getRGB(32, 32)).isBlue(), "the background still renders")
    }

    /** Already the image the launcher draws: re-encoding it would only lose to its own compression. */
    @Test
    fun aRasterIconTravelsAsItsOwnBytes() {
        val bytes = pngBytes(0xFF00FF00.toInt())
        val out = runBlocking { ProjectIconRaster.toBytes(UiProjectIcon.Raster(bytes)) { null } }
        assertContentEquals(bytes, out)
    }

    @Test
    fun aDrawableIconEncodesToAPngOfTheRequestedSize() {
        val out = runBlocking {
            ProjectIconRaster.toBytes(
                UiProjectIcon.Drawable(layers(vector("M18,18h72v72h-72z", red), adaptive = true)),
                pixels = 128,
            ) { null }
        }

        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(assertNotNull(out))))
        assertEquals(128, image.width)
        assertEquals(128, image.height)
    }

    @Test
    fun anEmptyRasterIsNotAnIcon() {
        assertNull(runBlocking { ProjectIconRaster.toBytes(UiProjectIcon.Raster(ByteArray(0))) { null } })
    }
}

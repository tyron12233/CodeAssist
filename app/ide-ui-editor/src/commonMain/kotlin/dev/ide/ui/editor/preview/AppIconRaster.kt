package dev.ide.ui.editor.preview

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.backend.UiAppIconPreview
import dev.ide.ui.backend.UiDrawable
import kotlin.math.roundToInt

/** The shapes a launcher may mask an adaptive icon to. */
enum class IconMask { CIRCLE, SQUIRCLE, ROUNDED_SQUARE, SQUARE }

/**
 * Draws and rasterises an adaptive launcher icon.
 *
 * The geometry is the adaptive-icon contract: both layers are authored in a 108-unit box of which only the
 * central 72 units are guaranteed visible, and the launcher scales that box up so the safe zone fills the
 * icon. So drawing at N pixels means drawing the layers across `N * 108 / 72` pixels, centred, then masking
 * back to N. Getting this wrong is what makes an icon preview look zoomed out compared to the real launcher.
 *
 * The live preview and the generated PNGs both go through [drawAppIcon], so what the studio shows and what it
 * writes cannot drift apart.
 */
object AppIconRaster {

    /** The ratio between the authored box and the visible safe zone (108 / 72). */
    const val BOX_TO_SAFE_ZONE = 1.5f

    /** The corner radius of the legacy rounded-square mask, as a fraction of the icon's edge. */
    private const val ROUNDED_SQUARE_FRACTION = 0.16f

    /** The squircle approximation: a much larger radius, which reads as the modern Pixel launcher shape. */
    private const val SQUIRCLE_FRACTION = 0.36f

    /**
     * Rasterise [preview] to a [pixels]-square [ImageBitmap] through [mask]. [opaqueBackground], when set,
     * is painted first and the mask is skipped, which is what a Play Store listing image needs.
     */
    fun render(
        preview: UiAppIconPreview,
        pixels: Int,
        mask: IconMask,
        opaqueBackground: Color? = null,
        monochrome: Boolean = false,
        images: Map<String, ImageBitmap> = emptyMap(),
    ): ImageBitmap? {
        if (pixels <= 0) return null
        return runCatching {
            val bitmap = ImageBitmap(pixels, pixels)
            val size = Size(pixels.toFloat(), pixels.toFloat())
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
                drawAppIcon(preview, mask, opaqueBackground, monochrome, images)
            }
            bitmap
        }.getOrNull()
    }

    /** [render] followed by a PNG encode, which is what the engine writes. */
    fun renderPng(
        preview: UiAppIconPreview,
        pixels: Int,
        mask: IconMask,
        opaqueBackground: Color? = null,
        images: Map<String, ImageBitmap> = emptyMap(),
    ): ByteArray? = render(preview, pixels, mask, opaqueBackground, images = images)?.let(::encodeImagePng)

    /**
     * Draws [preview] filling the current bounds. [opaqueBackground] paints an opaque ground and drops the
     * mask; [monochrome] draws the themed-icon layer instead of the foreground. [images] holds the decoded
     * [UiAppIconPreview.images], keyed the same way: an image layer is written as-is across the whole box, so
     * it is drawn across the whole box.
     */
    fun DrawScope.drawAppIcon(
        preview: UiAppIconPreview,
        mask: IconMask,
        opaqueBackground: Color? = null,
        monochrome: Boolean = false,
        images: Map<String, ImageBitmap> = emptyMap(),
    ) {
        val edge = minOf(size.width, size.height)
        if (edge <= 0f) return

        // The authored 108-unit box, scaled so its 72-unit safe zone covers the visible icon, and centred.
        val boxEdge = edge * BOX_TO_SAFE_ZONE
        val inset = (edge - boxEdge) / 2f
        val boxTopLeft = Offset((size.width - edge) / 2f + inset, (size.height - edge) / 2f + inset)
        val boxSize = Size(boxEdge, boxEdge)

        val iconRect = Rect(
            (size.width - edge) / 2f,
            (size.height - edge) / 2f,
            (size.width + edge) / 2f,
            (size.height + edge) / 2f,
        )

        val dstOffset = IntOffset(boxTopLeft.x.roundToInt(), boxTopLeft.y.roundToInt())
        val dstSize = IntSize(boxEdge.roundToInt(), boxEdge.roundToInt())
        fun DrawScope.image(key: String) = images[key]?.let { drawImage(it, dstOffset = dstOffset, dstSize = dstSize) }

        val body: DrawScope.() -> Unit = {
            opaqueBackground?.let { drawRect(it, Offset(iconRect.left, iconRect.top), Size(edge, edge)) }
            preview.background?.let { drawUiDrawable(it, boxTopLeft, boxSize) } ?: image("background")
            val top = if (monochrome) preview.monochrome ?: preview.foreground else preview.foreground
            when {
                top != null -> drawUiDrawable(top, boxTopLeft, boxSize)
                // A themed icon is a stencil of the foreground; a bitmap foreground has none, so draw nothing.
                !monochrome -> image("foreground")
            }
        }

        // A store image must be a full opaque square, so it is never masked.
        if (opaqueBackground != null || mask == IconMask.SQUARE) {
            body()
            return
        }
        clipPath(maskPath(iconRect, mask)) { body() }
    }

    /** The mask outline for [rect]. */
    fun maskPath(rect: Rect, mask: IconMask): Path = Path().apply {
        when (mask) {
            IconMask.CIRCLE -> addOval(rect)
            IconMask.SQUARE -> addRect(rect)
            IconMask.SQUIRCLE -> addRoundRect(rounded(rect, SQUIRCLE_FRACTION))
            IconMask.ROUNDED_SQUARE -> addRoundRect(rounded(rect, ROUNDED_SQUARE_FRACTION))
        }
    }

    private fun rounded(rect: Rect, fraction: Float): RoundRect {
        val radius = CornerRadius(rect.width * fraction, rect.height * fraction)
        return RoundRect(rect, radius, radius, radius, radius)
    }

    /** The flat colour of a solid-colour background layer, for the opaque store image. */
    fun opaqueGround(preview: UiAppIconPreview): Color =
        (preview.background as? UiDrawable.SolidColor)?.let { Color(it.color.toInt()) } ?: Color.White
}

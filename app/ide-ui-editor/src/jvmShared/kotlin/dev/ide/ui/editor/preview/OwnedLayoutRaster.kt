package dev.ide.ui.editor.preview

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.preview.LayoutPreviewResult
import dev.ide.preview.PreviewEngine
import dev.ide.preview.SimpleRenderContext

/**
 * Draws an owned-renderer layout result into pixels, off any composition.
 *
 * The owned renderer measures text and paints through Compose ([ComposeGraphics], [ComposeRCanvas]), which used
 * to tie it to a pane composing it on screen. A layout render is now a service answer (an image plus the view
 * tree that produced it, see `dev.ide.android.layout.LayoutRenderer`), so the host needs the same drawing with
 * no composition at all: a [CanvasDrawScope] over an [ImageBitmap] is exactly that, on both desktop (Skia) and
 * Android.
 *
 * Lays out [result]'s tree as a side effect, so the caller can read every node's bounds afterwards.
 *
 * Returns packed ARGB pixels, row-major, [widthPx] × [heightPx]; null when the render threw.
 */
fun rasterizeOwnedLayout(
    result: LayoutPreviewResult,
    widthPx: Int,
    heightPx: Int,
    fonts: FontFamily.Resolver,
): IntArray? = runCatching {
    // Density 1: the engine works in device pixels throughout, so a `textSizePx.sp` must measure to exactly that
    // many pixels (the same convention the on-screen pane used).
    val measurer = TextMeasurer(fonts, Density(1f), LayoutDirection.Ltr)
    val engine = PreviewEngine(
        SimpleRenderContext(
            ComposeGraphics(measurer),
            UiPreviewResources(result.resources, result.imageFile),
            result.density,
            result.density,
        ),
    )
    val bitmap = ImageBitmap(widthPx, heightPx)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(widthPx.toFloat(), heightPx.toFloat())) {
        drawRect(Color.White)
        engine.render(result.root, widthPx, heightPx, ComposeRCanvas(this, measurer))
    }
    IntArray(widthPx * heightPx).also { bitmap.readPixels(it) }
}.getOrNull()

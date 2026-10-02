package dev.ide.ui.editor.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.ide.ui.ext.PreviewSnapshot
import dev.ide.ui.ext.PreviewSnapshots
import kotlinx.coroutines.async
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Makes a preview pane's drawing available as a screenshot ([PreviewSnapshots]) for as long as the pane is
 * composed, so the AI agent can look at the UI it just changed.
 *
 * The content is recorded into a graphics layer as it draws and read back only when a snapshot is asked for,
 * which costs nothing per frame beyond the layer itself. Recording happens at the pane's own size, inside any
 * pan or zoom the surface applies around it, so the shot is the preview as rendered rather than as the user
 * happens to have zoomed it. The same path covers a frame streamed from the preview process (an image) and an
 * in-process composition (live content), on every platform.
 */
@Composable
fun rememberPreviewCapture(path: String, label: String): Modifier {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val currentLabel by rememberUpdatedState(label)
    DisposableEffect(path, layer) {
        val registration = PreviewSnapshots.register { wanted ->
            if (wanted != null && !samePath(wanted, path)) return@register null
            // The layer belongs to the composition; read it on the composition's own dispatcher.
            val image = scope.async { layer.toImageBitmap() }.await()
            if (image.width <= 1 || image.height <= 1) return@register null
            val scaled = image.scaledToFit(MAX_EDGE)
            val png = encodeImagePng(scaled) ?: return@register null
            PreviewSnapshot(path, currentLabel, png, scaled.width, scaled.height)
        }
        onDispose { registration.dispose() }
    }
    return Modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/** Models resize anything larger to about this long edge, so a bigger screenshot only costs upload. */
private const val MAX_EDGE = 1568

private fun samePath(a: String, b: String): Boolean {
    val x = a.replace('\\', '/')
    val y = b.replace('\\', '/')
    return x == y || x.endsWith("/$y") || y.endsWith("/$x")
}

private fun ImageBitmap.scaledToFit(maxEdge: Int): ImageBitmap {
    val edge = max(width, height)
    if (edge <= maxEdge) return this
    val scale = maxEdge.toFloat() / edge
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    val out = ImageBitmap(w, h)
    Canvas(out).drawImageRect(
        this,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(width, height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(w, h),
        paint = Paint().apply { filterQuality = FilterQuality.High },
    )
    return out
}

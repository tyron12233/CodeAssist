package dev.ide.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.backend.UiResourceSwatch
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.preview.argbColor
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.drawCheckerboard
import dev.ide.ui.editor.preview.drawUiDrawable
import kotlin.math.roundToInt

/**
 * Color and drawable swatches in the gutter, beside each line whose resource reference resolves to one
 * (`@color/x`, `@drawable/y`, `R.color.z`, a `#hex` literal). Tapping a swatch opens the resource's declaration.
 *
 * The gutter has one 20dp slot per line, so a line carrying a `@Preview` icon or a plugin's mark keeps that
 * instead: those are actions, a swatch is only a preview. Positioned like [PluginGutterMarksLayer].
 */
@Composable
internal fun ResourceSwatchGutterLayer(
    session: EditorSession,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    docLength: Int,
    onOpen: (path: String, offset: Int) -> Unit,
) {
    val swatches = session.resourceSwatches
    if (swatches.isEmpty()) return
    val doc = session.doc
    val taken = remember(session.previewMarkers, session.gutterMarks, doc) {
        HashSet<Int>().apply {
            session.previewMarkers.forEach { add(doc.lineForOffset(it.offset.coerceIn(0, docLength))) }
            session.gutterMarks.forEach { add(it.line) }
        }
    }
    val shown = HashSet<Int>()
    for (s in swatches) {
        val line = doc.lineForOffset(s.offset.coerceIn(0, docLength))
        if (line in taken || !shown.add(line) || session.foldModel.isHidden(line)) continue
        key(line, s.color, s.image) {
            ResourceSwatch(
                swatch = s,
                onClick = s.targetPath?.let { path -> { onOpen(path, s.targetOffset) } },
                modifier = Modifier.offset {
                    IntOffset(
                        1.dp.roundToPx(),
                        (metrics.padTop + vlayout.topRow(line) * metrics.lineHeight - vOffset.floatValue +
                            (metrics.lineHeight - 20.dp.toPx()) / 2f).roundToInt(),
                    )
                },
            )
        }
    }
}

@Composable
private fun ResourceSwatch(swatch: UiResourceSwatch, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.size(20.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val chip = Modifier.size(13.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        val color = swatch.color
        when (val image = swatch.image) {
            null -> if (color != null) {
                // A checkerboard under a translucent color, so `#80FF0000` reads as see-through.
                Canvas(chip) {
                    if ((color ushr 24) < 0xFF) drawCheckerboard(cell = size.width / 3f)
                    drawRect(argbColor(color))
                }
            }
            is UiProjectIcon.Drawable -> Canvas(chip) { drawUiDrawable(image.drawable, Offset.Zero, size) }
            is UiProjectIcon.Raster -> {
                val bitmap = remember(image.bytes) { decodeImageBytes(image.bytes) }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = chip)
                } else {
                    Box(chip.background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

package dev.ide.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The selection loupe: while a selection handle is dragged ([EditorInteraction.loupeSource]), a magnified slice
 * of the editor floats above the finger, so the edge being placed is visible although the finger covers it.
 *
 * The editor's own drawing is recorded into a graphics layer and the loupe draws that layer again, zoomed, so it
 * shows exactly what the editor shows (text, selection, caret, handles) on every platform; Compose's platform
 * magnifier exists only on Android. Nothing is recorded while no loupe shows.
 */
@Composable
internal fun Modifier.editorLoupe(interaction: EditorInteraction, lineHeight: Float, background: Color, outline: Color): Modifier {
    val layer = rememberGraphicsLayer()
    val appear = remember { Animatable(0f) }
    // Where the loupe last looked, kept through its fade-out after the drag ends. A plain holder: written and read
    // in draw only.
    val lastSource = remember { arrayOf(Offset.Zero) }
    LaunchedEffect(interaction) {
        snapshotFlow { interaction.loupeSource != null }.collect { on ->
            appear.animateTo(if (on) 1f else 0f, tween(if (on) 140 else 110))
        }
    }
    return drawWithContent {
        interaction.loupeSource?.let { lastSource[0] = it }
        val shown = appear.value
        if (shown <= 0f) {
            drawContent()
            return@drawWithContent
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)

        val source = lastSource[0]
        val w = LoupeWidth.toPx()
        val h = LoupeHeight.toPx()
        val edge = 4.dp.toPx()
        // Above the line being magnified, clear of the finger, kept within the editor's width. For a line near the
        // top it may rise over what is above the editor (as Android's own loupe rises over the app bar), up to a
        // limit, rather than come down onto the line it is magnifying.
        val cx = source.x.coerceIn(w / 2f + edge, (size.width - w / 2f - edge).coerceAtLeast(w / 2f + edge))
        val cy = (source.y - lineHeight / 2f - LoupeGap.toPx() - h / 2f).coerceAtLeast(h / 2f - LoupeOverhang.toPx())
        val rect = Rect(Offset(cx - w / 2f, cy - h / 2f), Size(w, h))
        val radius = CornerRadius(LoupeCorner.toPx())
        val handleClearance = 2.dp.toPx()
        // It grows out of the point it magnifies and fades in, and shrinks back on release.
        val grow = 0.75f + 0.25f * shown
        withTransform({ scale(grow, grow, pivot = Offset(cx, rect.bottom)) }) {
            drawIntoCanvas { it.saveLayer(rect.inflate(8.dp.toPx()), Paint().apply { alpha = shown }) }
            // A soft drop shadow, two passes of a translucent outline below it.
            for (i in 1..2) {
                drawRoundRect(
                    Color.Black.copy(alpha = 0.16f), rect.topLeft + Offset(0f, i * 1.5.dp.toPx()), rect.size, radius,
                )
            }
            clipPath(Path().apply { addRoundRect(RoundRect(rect, radius)) }) {
                drawRect(background, rect.topLeft, rect.size)
                withTransform({
                    translate(rect.center.x, rect.center.y)
                    scale(LoupeZoom, LoupeZoom, pivot = Offset.Zero)
                    translate(-source.x, -source.y)
                    // Down to just above the line's bottom only: the selection handles hang from there (their top
                    // reaches ~1dp into the line), and magnified they would cover the very text being placed.
                    clipRect(top = source.y - lineHeight * 2f, bottom = source.y + lineHeight / 2f - handleClearance)
                }) {
                    drawLayer(layer)
                }
            }
            drawRoundRect(outline, rect.topLeft, rect.size, radius, style = Stroke(1.dp.toPx()))
            drawIntoCanvas { it.restore() }
        }
    }
}

private val LoupeWidth = 120.dp
private val LoupeHeight = 46.dp
private val LoupeCorner = 15.dp
private val LoupeGap = 12.dp
private const val LoupeZoom = 1.5f

/** How far above the editor's top edge the loupe may rise for a line near the top. */
private val LoupeOverhang = 44.dp

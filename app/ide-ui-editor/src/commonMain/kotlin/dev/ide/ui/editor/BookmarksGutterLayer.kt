package dev.ide.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.EditorSession

/**
 * Bookmarked lines, marked with a small ribbon at the gutter's left edge (clear of the line numbers, which
 * right-align). Drawn in one canvas that reads the scroll in the draw phase, so it follows a fling for free.
 */
@Composable
internal fun BookmarksGutterLayer(
    session: EditorSession,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    gutterWidthPx: Float,
    color: Color,
) {
    val lines = session.bookmarks
    if (lines.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val doc = session.doc
        val w = 7.dp.toPx()
        val h = metrics.lineHeight * 0.62f
        val notch = 2.5.dp.toPx()
        val left = 3.dp.toPx()
        clipRect(right = gutterWidthPx) {
            for (line in lines) {
                if (line !in 0 until doc.lineCount || session.foldModel.isHidden(line)) continue
                val top = metrics.padTop + vlayout.topRow(line) * metrics.lineHeight - vOffset.floatValue + (metrics.lineHeight - h) / 2f
                if (top + h < 0f || top > size.height) continue
                // A ribbon: a rectangle with a V notch cut into its foot.
                val ribbon = Path().apply {
                    moveTo(left, top)
                    lineTo(left + w, top)
                    lineTo(left + w, top + h)
                    lineTo(left + w / 2f, top + h - notch)
                    lineTo(left, top + h)
                    close()
                }
                drawPath(ribbon, color)
            }
        }
    }
}

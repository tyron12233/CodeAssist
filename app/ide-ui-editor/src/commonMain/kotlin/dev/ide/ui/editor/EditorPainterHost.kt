package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.folding.FoldModel
import dev.ide.ui.ext.EditorPaintContext
import dev.ide.ui.ext.EditorPaintLayer
import dev.ide.ui.ext.EditorPainterContribution
import dev.ide.ui.ext.EditorPainterRegistry

/**
 * The host side of `EditorPainterContribution`: the plugin painters for one file, and the guarded call that
 * runs them inside the editor's draw.
 *
 * A painter is the only contribution in the IDE that runs in a draw phase, which drives everything here. The
 * two lists are resolved once per composition rather than per frame ([rememberEditorPainters]); the context a
 * painter reads is built once per frame, and only when a painter exists; and a painter that throws is retired
 * for the session rather than retried, because a draw that throws throws again every frame.
 */

/** The painters for the open file, split by depth so the draw does no filtering per frame. */
internal class EditorPainters(
    val belowText: List<EditorPainterContribution>,
    val aboveText: List<EditorPainterContribution>,
) {
    val isEmpty: Boolean get() = belowText.isEmpty() && aboveText.isEmpty()

    companion object {
        val NONE = EditorPainters(emptyList(), emptyList())
    }
}

/**
 * The registered painters claiming [path], resolved in composition.
 *
 * The registry is Compose-observable, so registering or retiring a painter re-resolves this; reading it here
 * rather than in the draw keeps the per-frame cost at one field read.
 */
@Composable
internal fun rememberEditorPainters(path: String): EditorPainters {
    val below = EditorPainterRegistry.forFile(path, EditorPaintLayer.BelowText)
    val above = EditorPainterRegistry.forFile(path, EditorPaintLayer.AboveText)
    return remember(below, above) {
        if (below.isEmpty() && above.isEmpty()) EditorPainters.NONE else EditorPainters(below, above)
    }
}

/**
 * Run [painters] against [ctx], each guarded.
 *
 * A painter that throws is retired from the registry, which keeps the throwable. Skipping it for the frame
 * instead would mean a plugin whose painter always throws costs a stack unwind sixty times a second, forever,
 * while the user gets an editor that neither draws that plugin's marks nor recovers. Retiring costs that one
 * plugin its drawing and leaves the editor working, which is the trade a decoration should always take.
 */
internal fun DrawScope.runPainters(painters: List<EditorPainterContribution>, ctx: EditorPaintContext) {
    for (painter in painters) {
        try {
            painter.paint(this, ctx)
        } catch (e: Throwable) {
            // Retire rather than rethrow or skip: see the note on EditorPainterRegistry. The registry keeps
            // the throwable, so the reason survives for a surface that reports it.
            EditorPainterRegistry.retire(painter.id, e)
        }
    }
}

/**
 * [EditorPaintContext] over the editor's live layout for one frame.
 *
 * The positioning members are closures the caller passes in, taken from the draw's own local functions, so a
 * painter gets exactly the geometry the editor itself draws against: scroll offset, soft wrap and collapsed
 * folds are already accounted for. Reimplementing them here from line numbers and a line height would be
 * wrong the moment a line wraps or a fold closes, which is the whole reason this is an interface of functions
 * rather than a data class of numbers.
 */
internal class CanvasPaintContext(
    override val path: String,
    private val visible: IntRange,
    private val doc: EditorDocument,
    private val metrics: EditorMetrics,
    private val gutterWidthPx: Float,
    private val textLeftPx: Float,
    private val foldModel: FoldModel,
    private val lineTopOf: (Int) -> Float,
    private val xAt: (Int) -> Float,
) : EditorPaintContext {
    override val visibleLines: IntRange get() = visible
    override val lineCount: Int get() = doc.lineCount
    override val lineHeight: Float get() = metrics.lineHeight
    override val charWidth: Float get() = metrics.charWidth
    override val gutterWidth: Float get() = gutterWidthPx
    override val textLeft: Float get() = textLeftPx

    override fun lineTop(line: Int): Float = lineTopOf(line.coerceIn(0, doc.lineCount - 1))

    override fun xOf(offset: Int): Float =
        // The editor's own xOf throws on an offset outside the laid-out line, and a painter holding an offset
        // from a decoration pass can be one edit stale. Answering the text's left edge is wrong by a few
        // pixels for one frame; throwing would take the editor's draw down.
        runCatching { xAt(offset.coerceIn(0, doc.length)) }.getOrDefault(textLeftPx)

    override fun lineOf(offset: Int): Int = doc.lineForOffset(offset.coerceIn(0, doc.length))

    override fun lineRange(line: Int): IntRange {
        val l = line.coerceIn(0, doc.lineCount - 1)
        return doc.lineStart(l)..doc.lineEnd(l)
    }

    override fun isHidden(line: Int): Boolean = foldModel.isHidden(line)
}

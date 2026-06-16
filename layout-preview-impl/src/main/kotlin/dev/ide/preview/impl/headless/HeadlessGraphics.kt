package dev.ide.preview.impl.headless

import dev.ide.preview.FontMetrics
import dev.ide.preview.PaintStyle
import dev.ide.preview.RGraphics
import dev.ide.preview.RPaint
import dev.ide.preview.RPath

/** A deterministic [RPaint] holder with no platform backing. */
class HeadlessPaint : RPaint {
    override var color: Int = 0xFF000000.toInt()
    override var style: PaintStyle = PaintStyle.FILL
    override var strokeWidth: Float = 0f
    override var antiAlias: Boolean = false
    override var textSizePx: Float = 14f
    override var bold: Boolean = false
    override var gradient: dev.ide.preview.Gradient? = null
}

/** A trivial parsed path token (the headless backend doesn't rasterize paths). */
object HeadlessPath : RPath

/**
 * A platform-free [RGraphics] for headless rendering and tests: text metrics are a fixed-ratio model of the
 * paint size (each glyph ≈ 0.55 em, line height ≈ 1.2 em), which is deterministic and good enough to exercise
 * the measure/layout/wrap logic without a real text engine. Real metrics come from the Compose/android.graphics
 * backends.
 */
class HeadlessGraphics(private val glyphRatio: Float = 0.55f, private val lineRatio: Float = 1.2f) : RGraphics {
    override fun newPaint(): RPaint = HeadlessPaint()
    override fun parsePath(pathData: String): RPath = HeadlessPath
    override fun measureText(text: CharSequence, paint: RPaint): Float = text.length * paint.textSizePx * glyphRatio
    override fun fontMetrics(paint: RPaint): FontMetrics =
        FontMetrics(lineHeight = paint.textSizePx * lineRatio, ascent = paint.textSizePx)
}

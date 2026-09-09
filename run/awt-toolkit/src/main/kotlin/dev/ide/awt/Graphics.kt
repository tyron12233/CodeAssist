package dev.ide.awt

/**
 * `java.awt.Graphics`: what a component's `paintComponent` draws through. Abstract here for the same reason
 * it is in AWT, so that `(Graphics2D) g` is a legal cast on whatever the toolkit actually hands over.
 *
 * Coordinates are pixels in the component's own space, with the origin at its top-left; the surface applies
 * the translation to the component's position before calling it.
 */
abstract class Graphics {
    abstract fun getColor(): Color
    abstract fun setColor(c: Color?)
    abstract fun getFont(): Font
    abstract fun setFont(f: Font?)

    /** Metrics for the current font, or for [f] when given. */
    abstract fun getFontMetrics(): FontMetrics
    abstract fun getFontMetrics(f: Font?): FontMetrics

    abstract fun translate(dx: Int, dy: Int)
    abstract fun clipRect(x: Int, y: Int, width: Int, height: Int)
    abstract fun setClip(x: Int, y: Int, width: Int, height: Int)

    abstract fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int)
    abstract fun drawRect(x: Int, y: Int, width: Int, height: Int)
    abstract fun fillRect(x: Int, y: Int, width: Int, height: Int)
    abstract fun drawRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int)
    abstract fun fillRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int)
    abstract fun drawOval(x: Int, y: Int, width: Int, height: Int)
    abstract fun fillOval(x: Int, y: Int, width: Int, height: Int)
    abstract fun drawString(text: String?, x: Int, y: Int)

    /** `clearRect` paints the component's background colour over the rect, as AWT does. */
    abstract fun clearRect(x: Int, y: Int, width: Int, height: Int)

    /** A no-op: this toolkit's graphics are owned by the surface for the length of one paint pass. */
    open fun dispose() {}
}

/**
 * `java.awt.Graphics2D`. Programs reach it by casting the `Graphics` they were handed, so the extra surface is
 * declared here even where the implementation can only honour part of it (see [CanvasGraphics]).
 */
abstract class Graphics2D : Graphics() {
    abstract fun setRenderingHint(key: RenderingHints.Key?, value: Any?)
    abstract fun getRenderingHint(key: RenderingHints.Key?): Any?
    abstract fun setStroke(stroke: BasicStroke?)
    abstract fun getStroke(): BasicStroke
    abstract fun setBackground(c: Color?)
    abstract fun getBackground(): Color
}

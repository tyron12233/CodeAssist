package dev.ide.swing

import dev.ide.awt.Color
import dev.ide.awt.Component
import dev.ide.awt.Dimension
import dev.ide.awt.Graphics
import dev.ide.awt.Point
import dev.ide.awt.event.MouseWheelEvent

/** `javax.swing.ScrollPaneConstants`: when each scrollbar is shown. */
interface ScrollPaneConstants {
    companion object {
        @JvmField val VERTICAL_SCROLLBAR_AS_NEEDED = 20
        @JvmField val VERTICAL_SCROLLBAR_NEVER = 21
        @JvmField val VERTICAL_SCROLLBAR_ALWAYS = 22
        @JvmField val HORIZONTAL_SCROLLBAR_AS_NEEDED = 30
        @JvmField val HORIZONTAL_SCROLLBAR_NEVER = 31
        @JvmField val HORIZONTAL_SCROLLBAR_ALWAYS = 32
    }
}

/**
 * `javax.swing.JViewport`: a window onto a component larger than itself.
 *
 * The view is laid out at its own preferred size and drawn shifted by the view position, so scrolling costs a
 * translation rather than a re-layout. Children are clipped to the viewport because every container clips its
 * children to their bounds when painting.
 */
open class JViewport : JComponent() {

    private var view: Component? = null
    private var viewX = 0
    private var viewY = 0

    init {
        setOpaque(false)
    }

    open fun getView(): Component? = view

    open fun setView(component: Component?) {
        view?.let { remove(it) }
        view = component
        component?.let { add(it) }
        invalidate()
    }

    open fun getViewPosition(): Point = Point(viewX, viewY)

    open fun setViewPosition(p: Point?) {
        if (p == null) return
        setViewPosition(p.x, p.y)
    }

    /** Move the view, clamped so it can never be scrolled past its own edges. */
    open fun setViewPosition(x: Int, y: Int) {
        val size = viewSize()
        val nextX = x.coerceIn(0, maxOf(0, size.width - getWidth()))
        val nextY = y.coerceIn(0, maxOf(0, size.height - getHeight()))
        if (nextX == viewX && nextY == viewY) return
        viewX = nextX
        viewY = nextY
        doLayout()
        repaint()
    }

    open fun getViewSize(): Dimension = viewSize()

    private fun viewSize(): Dimension = view?.getPreferredSize() ?: Dimension(0, 0)

    override fun doLayout() {
        val v = view ?: return
        val size = viewSize()
        // At least the viewport's own size, so a small view still fills it rather than floating in a corner.
        v.setBounds(-viewX, -viewY, maxOf(size.width, getWidth()), maxOf(size.height, getHeight()))
    }

    override fun computePreferredSize(): Dimension = viewSize()
}

/**
 * `javax.swing.JScrollBar`: the track and the thumb. Dragging is not wired: on a touch surface the content is
 * scrolled with the wheel or a drag on the content itself, and a 12-pixel thumb is not a touch target.
 */
open class JScrollBar @JvmOverloads constructor(private val vertical: Boolean = true) : JComponent() {

    private var value = 0
    private var extent = 0
    private var maximum = 0

    init {
        setOpaque(true)
        setBackground(TRACK)
    }

    open fun getValue(): Int = value

    open fun getVisibleAmount(): Int = extent

    open fun getMaximum(): Int = maximum

    /** Set the whole model at once, which is all a scroll pane ever needs. */
    open fun setValues(value: Int, extent: Int, min: Int, max: Int) {
        this.value = value
        this.extent = extent
        this.maximum = max
        repaint()
    }

    override fun computePreferredSize(): Dimension =
        if (vertical) Dimension(THICKNESS, 0) else Dimension(0, THICKNESS)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (maximum <= extent || extent <= 0) return
        val span = if (vertical) getHeight() else getWidth()
        val thumb = maxOf(MIN_THUMB, span * extent / maximum)
        val offset = ((span - thumb).toLong() * value / maxOf(1, maximum - extent)).toInt()
        g.setColor(THUMB)
        if (vertical) g.fillRoundRect(2, offset, THICKNESS - 4, thumb, 4, 4)
        else g.fillRoundRect(offset, 2, thumb, THICKNESS - 4, 4, 4)
    }

    private companion object {
        val TRACK = Color(0xF0, 0xF0, 0xF0)
        val THUMB = Color(0xA0, 0xA0, 0xA0)
        const val THICKNESS = 10
        const val MIN_THUMB = 24
    }
}

/**
 * `javax.swing.JScrollPane`: a viewport plus the scrollbars that show where you are in it.
 *
 * Scrolling is driven by the wheel, which the run surface forwards, and the pane consumes the notch so a
 * scroll pane inside another one takes it rather than the outer one.
 */
open class JScrollPane @JvmOverloads constructor(view: Component? = null) : JComponent() {

    private val viewport = JViewport()
    private val vertical = JScrollBar(vertical = true)
    private val horizontal = JScrollBar(vertical = false)
    private var verticalPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    private var horizontalPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED

    init {
        setOpaque(false)
        addChild(viewport, null)
        addChild(vertical, null)
        addChild(horizontal, null)
        view?.let { viewport.setView(it) }
    }

    open fun getViewport(): JViewport = viewport

    open fun setViewportView(component: Component?) {
        viewport.setView(component)
        invalidate()
    }

    open fun getVerticalScrollBar(): JScrollBar = vertical

    open fun getHorizontalScrollBar(): JScrollBar = horizontal

    open fun setVerticalScrollBarPolicy(policy: Int) {
        verticalPolicy = policy
        invalidate()
    }

    open fun setHorizontalScrollBarPolicy(policy: Int) {
        horizontalPolicy = policy
        invalidate()
    }

    override fun doLayout() {
        val insets = getInsets()
        val size = viewport.getViewSize()
        val width = getWidth() - insets.left - insets.right
        val height = getHeight() - insets.top - insets.bottom
        val showV = shows(verticalPolicy, size.height > height, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS)
        val showH = shows(horizontalPolicy, size.width > width, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        val barV = if (showV) vertical.getPreferredSize().width else 0
        val barH = if (showH) horizontal.getPreferredSize().height else 0

        viewport.setBounds(insets.left, insets.top, width - barV, height - barH)
        vertical.setVisible(showV)
        horizontal.setVisible(showH)
        if (showV) vertical.setBounds(insets.left + width - barV, insets.top, barV, height - barH)
        if (showH) horizontal.setBounds(insets.left, insets.top + height - barH, width - barV, barH)

        val position = viewport.getViewPosition()
        vertical.setValues(position.y, viewport.getHeight(), 0, size.height)
        horizontal.setValues(position.x, viewport.getWidth(), 0, size.width)
    }

    private fun shows(policy: Int, needed: Boolean, never: Int, always: Int): Boolean = when (policy) {
        never -> false
        always -> true
        else -> needed
    }

    override fun computePreferredSize(): Dimension {
        val size = viewport.getViewSize()
        val insets = getInsets()
        return Dimension(size.width + insets.left + insets.right, size.height + insets.top + insets.bottom)
    }

    override fun processMouseWheelEvent(e: MouseWheelEvent): Boolean {
        if (super.processMouseWheelEvent(e)) return true
        val position = viewport.getViewPosition()
        val before = position.y
        viewport.setViewPosition(position.x, position.y + e.getUnitsToScroll() * UNIT)
        doLayout()
        // Consumed only if it actually moved, so a pane already at its end lets an outer one take over.
        return viewport.getViewPosition().y != before
    }

    private companion object {
        /** Pixels per scroll unit. AWT leaves this to the look and feel; a line of text is the useful choice. */
        const val UNIT = 16
    }
}

/** `javax.swing.JSeparator`: a hairline between sections. */
open class JSeparator @JvmOverloads constructor(private val vertical: Boolean = false) : JComponent() {

    init {
        setOpaque(false)
    }

    override fun computePreferredSize(): Dimension = if (vertical) Dimension(2, 0) else Dimension(0, 2)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g.setColor(LINE)
        if (vertical) g.drawLine(getWidth() / 2, 0, getWidth() / 2, getHeight())
        else g.drawLine(0, getHeight() / 2, getWidth(), getHeight() / 2)
    }

    private companion object {
        val LINE = Color(0xD0, 0xD0, 0xD0)
    }
}

/** `javax.swing.JProgressBar`: a filled track, optionally with its percentage written across it. */
open class JProgressBar @JvmOverloads constructor(
    private var minimum: Int = 0,
    private var maximum: Int = 100,
) : JComponent() {

    private var value = 0
    private var stringPainted = false
    private var indeterminate = false

    init {
        setOpaque(false)
    }

    open fun getValue(): Int = value

    open fun setValue(v: Int) {
        val next = v.coerceIn(minimum, maximum)
        if (next == value) return
        value = next
        repaint()
    }

    open fun getMinimum(): Int = minimum
    open fun getMaximum(): Int = maximum

    open fun setMinimum(v: Int) {
        minimum = v
        repaint()
    }

    open fun setMaximum(v: Int) {
        maximum = v
        repaint()
    }

    open fun isStringPainted(): Boolean = stringPainted

    open fun setStringPainted(value: Boolean) {
        stringPainted = value
        repaint()
    }

    open fun isIndeterminate(): Boolean = indeterminate

    open fun setIndeterminate(value: Boolean) {
        indeterminate = value
        repaint()
    }

    open fun getPercentComplete(): Double =
        if (maximum <= minimum) 0.0 else (value - minimum).toDouble() / (maximum - minimum)

    override fun computePreferredSize(): Dimension = Dimension(146, (metrics()?.getHeight() ?: 12) + 6)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g.setColor(TRACK)
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6)
        // An indeterminate bar shows a fixed sliver rather than animating: the toolkit has no clock, and a bar
        // that pretended to move would need one.
        val filled = if (indeterminate) getWidth() / 4 else (getWidth() * getPercentComplete()).toInt()
        g.setColor(FILL)
        g.fillRoundRect(0, 0, filled, getHeight(), 6, 6)
        if (!stringPainted || indeterminate) return
        val metrics = g.getFontMetrics(getFont())
        val label = "${(getPercentComplete() * 100).toInt()}%"
        g.setColor(Color.DARK_GRAY)
        g.drawString(
            label,
            (getWidth() - metrics.stringWidth(label)) / 2,
            (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent(),
        )
    }

    private companion object {
        val TRACK = Color(0xE0, 0xE0, 0xE0)
        val FILL = Color(0x2D, 0x6C, 0xDF)
    }
}

/** `javax.swing.JSlider`: a track with a knob, dragged to choose a value. */
open class JSlider @JvmOverloads constructor(
    private var minimum: Int = 0,
    private var maximum: Int = 100,
    value: Int = 50,
) : JComponent() {

    private var current = value.coerceIn(minimum, maximum)
    private val changeListeners = ArrayList<dev.ide.swing.event.ChangeListener>()

    init {
        setOpaque(false)
    }

    override fun isFocusable(): Boolean = true

    open fun getValue(): Int = current

    open fun setValue(v: Int) {
        val next = v.coerceIn(minimum, maximum)
        if (next == current) return
        current = next
        val e = dev.ide.swing.event.ChangeEvent(this)
        for (l in changeListeners.toList()) l.stateChanged(e)
        repaint()
    }

    open fun getMinimum(): Int = minimum
    open fun getMaximum(): Int = maximum

    fun addChangeListener(l: dev.ide.swing.event.ChangeListener?) {
        if (l != null) changeListeners.add(l)
    }

    override fun processMouseEvent(e: dev.ide.awt.event.MouseEvent) {
        super.processMouseEvent(e)
        if (e.id == dev.ide.awt.event.MouseEvent.MOUSE_PRESSED) setValueFrom(e.x)
    }

    override fun processMouseMotionEvent(e: dev.ide.awt.event.MouseEvent) {
        super.processMouseMotionEvent(e)
        if (e.id == dev.ide.awt.event.MouseEvent.MOUSE_DRAGGED) setValueFrom(e.x)
    }

    /** Map a press or drag across the track onto the value range. */
    private fun setValueFrom(x: Int) {
        val span = getWidth() - KNOB
        if (span <= 0) return
        val fraction = ((x - KNOB / 2).toFloat() / span).coerceIn(0f, 1f)
        setValue(minimum + ((maximum - minimum) * fraction).toInt())
    }

    override fun computePreferredSize(): Dimension = Dimension(200, KNOB + 4)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val midY = getHeight() / 2
        g.setColor(TRACK)
        g.fillRoundRect(KNOB / 2, midY - 2, getWidth() - KNOB, 4, 4, 4)
        val fraction = if (maximum <= minimum) 0f else (current - minimum).toFloat() / (maximum - minimum)
        val knobX = (KNOB / 2 + (getWidth() - KNOB) * fraction).toInt()
        g.setColor(FILL)
        g.fillRoundRect(KNOB / 2, midY - 2, knobX - KNOB / 2, 4, 4, 4)
        g.fillOval(knobX - KNOB / 2, midY - KNOB / 2, KNOB, KNOB)
    }

    private companion object {
        val TRACK = Color(0xD0, 0xD0, 0xD0)
        val FILL = Color(0x2D, 0x6C, 0xDF)
        const val KNOB = 16
    }
}

/**
 * `javax.swing.BoxLayout`: children in one line, each at its preferred size along the axis and stretched
 * across it. The layout a form is usually built from, `Box.createVerticalBox()` being the other half.
 */
class BoxLayout(private val target: Component?, private val axis: Int) : dev.ide.awt.LayoutManager {

    override fun layoutContainer(parent: dev.ide.awt.Container) {
        val insets = parent.getInsets()
        var x = insets.left
        var y = insets.top
        val width = parent.getWidth() - insets.left - insets.right
        val height = parent.getHeight() - insets.top - insets.bottom
        for (c in parent.components()) {
            if (!c.isVisible()) continue
            val size = c.getPreferredSize()
            if (axis == Y_AXIS) {
                c.setBounds(x, y, width, size.height)
                y += size.height
            } else {
                c.setBounds(x, y, size.width, height)
                x += size.width
            }
        }
    }

    override fun preferredLayoutSize(parent: dev.ide.awt.Container): Dimension {
        val insets = parent.getInsets()
        var main = 0
        var cross = 0
        for (c in parent.components()) {
            if (!c.isVisible()) continue
            val size = c.getPreferredSize()
            if (axis == Y_AXIS) {
                main += size.height
                cross = maxOf(cross, size.width)
            } else {
                main += size.width
                cross = maxOf(cross, size.height)
            }
        }
        return if (axis == Y_AXIS) {
            Dimension(cross + insets.left + insets.right, main + insets.top + insets.bottom)
        } else {
            Dimension(main + insets.left + insets.right, cross + insets.top + insets.bottom)
        }
    }

    companion object {
        @JvmField val X_AXIS = 0
        @JvmField val Y_AXIS = 1
        @JvmField val LINE_AXIS = 2
        @JvmField val PAGE_AXIS = 3
    }
}

package dev.ide.awt

import dev.ide.awt.event.MouseEvent
import dev.ide.awt.event.MouseListener

/**
 * `java.awt.Component`: a rectangle that knows how to size itself, paint itself, and receive mouse events.
 * Position and size are in the PARENT's coordinate space; painting happens in the component's own, with the
 * origin at its top-left, because the surface translates before calling [paint].
 *
 * The layout contract is AWT's: a container asks each child for its preferred size, assigns bounds, and the
 * child paints inside them. A component that computes its own size overrides [computePreferredSize];
 * `setPreferredSize` overrides that, exactly as it does in AWT.
 */
open class Component {

    @JvmField internal var xPos: Int = 0
    @JvmField internal var yPos: Int = 0
    @JvmField internal var widthPx: Int = 0
    @JvmField internal var heightPx: Int = 0

    private var explicitPreferredSize: Dimension? = null
    private val mouseListeners = ArrayList<MouseListener>()

    var parent: Container? = null
        internal set

    private var visible = true
    private var enabled = true
    private var background: Color? = null
    private var foreground: Color? = null
    private var font: Font? = null

    // ---- geometry ----------------------------------------------------------------------------------

    fun getX(): Int = xPos
    fun getY(): Int = yPos
    fun getWidth(): Int = widthPx
    fun getHeight(): Int = heightPx
    fun getSize(): Dimension = Dimension(widthPx, heightPx)
    fun getLocation(): Point = Point(xPos, yPos)
    fun getBounds(): Rectangle = Rectangle(xPos, yPos, widthPx, heightPx)

    open fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val resized = width != widthPx || height != heightPx
        xPos = x
        yPos = y
        widthPx = width
        heightPx = height
        if (resized) invalidate()
    }

    open fun setSize(width: Int, height: Int) = setBounds(xPos, yPos, width, height)
    fun setSize(d: Dimension) = setSize(d.width, d.height)
    open fun setLocation(x: Int, y: Int) = setBounds(x, y, widthPx, heightPx)

    open fun getPreferredSize(): Dimension = explicitPreferredSize?.let { Dimension(it) } ?: computePreferredSize()

    open fun setPreferredSize(size: Dimension?) {
        explicitPreferredSize = size
        invalidate()
    }

    /** Whether the program pinned a size, so a container knows a computed size is only a suggestion. */
    fun isPreferredSizeSet(): Boolean = explicitPreferredSize != null

    open fun getMinimumSize(): Dimension = getPreferredSize()
    open fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

    /** What this component wants to be when nothing pinned it. Overridden by every widget that has content. */
    protected open fun computePreferredSize(): Dimension = Dimension(0, 0)

    // ---- appearance --------------------------------------------------------------------------------

    open fun isVisible(): Boolean = visible

    open fun setVisible(value: Boolean) {
        visible = value
    }

    open fun isEnabled(): Boolean = enabled

    open fun setEnabled(value: Boolean) {
        enabled = value
    }

    open fun getBackground(): Color? = background ?: parent?.getBackground()

    open fun setBackground(c: Color?) {
        background = c
    }

    open fun getForeground(): Color = foreground ?: parent?.getForeground() ?: Color.BLACK

    open fun setForeground(c: Color?) {
        foreground = c
    }

    open fun getFont(): Font? = font ?: parent?.getFont()

    open fun setFont(f: Font?) {
        font = f
        invalidate()
    }

    // ---- painting ----------------------------------------------------------------------------------

    /** Paint this component. The graphics origin is already this component's top-left. */
    open fun paint(g: Graphics) {}

    /**
     * Ask for a repaint. The owning surface decides when that happens, so this only raises a flag: a
     * component cannot force a frame in a toolkit whose output is a canvas someone else drives.
     */
    open fun repaint() {
        surface()?.invalidateFrame()
    }

    /** Discard cached layout up the tree, so the next pass re-measures. */
    open fun invalidate() {
        parent?.invalidate()
    }

    /** The surface this component is attached to, found by walking up to the root. */
    internal open fun surface(): Surface? = parent?.surface()

    /**
     * Text metrics for this component's font, or null while it is not attached to a surface. Measuring needs
     * the backend's real typeface, which only the window has, so a detached component reports a zero preferred
     * size and takes its real one at the first layout pass after it is added.
     */
    protected fun metrics(): FontMetrics? = surface()?.measuringGraphics()?.getFontMetrics(getFont())

    // ---- input -------------------------------------------------------------------------------------

    private val keyListeners = ArrayList<dev.ide.awt.event.KeyListener>()

    fun addKeyListener(l: dev.ide.awt.event.KeyListener?) {
        if (l != null) keyListeners.add(l)
    }

    fun removeKeyListener(l: dev.ide.awt.event.KeyListener?) {
        if (l != null) keyListeners.remove(l)
    }

    fun getKeyListeners(): Array<dev.ide.awt.event.KeyListener> = keyListeners.toTypedArray()

    private var focusable = true

    /**
     * Whether this component can take keyboard focus. True by default, as in AWT, where it is focus TRAVERSAL
     * that is restricted rather than focusability: pressing a panel that has a `KeyListener` therefore starts
     * sending it keys, which is what a program that draws and reads the keyboard expects.
     */
    open fun isFocusable(): Boolean = focusable

    open fun setFocusable(value: Boolean) {
        focusable = value
    }

    /** Ask the window to send keys here. No-op when this component is not in a window yet. */
    fun requestFocusInWindow(): Boolean = surface()?.focusOn(this) ?: false

    fun addMouseListener(l: MouseListener?) {
        if (l != null) mouseListeners.add(l)
    }

    fun removeMouseListener(l: MouseListener?) {
        if (l != null) mouseListeners.remove(l)
    }

    fun getMouseListeners(): Array<MouseListener> = mouseListeners.toTypedArray()

    /** Whether ([px], [py]) in this component's own space is inside it. */
    open fun contains(px: Int, py: Int): Boolean = px >= 0 && py >= 0 && px < widthPx && py < heightPx

    /** Deliver a key event to this component's listeners. */
    protected open fun processKeyEvent(e: dev.ide.awt.event.KeyEvent) {
        for (l in keyListeners.toList()) {
            when (e.id) {
                dev.ide.awt.event.KeyEvent.KEY_PRESSED -> l.keyPressed(e)
                dev.ide.awt.event.KeyEvent.KEY_RELEASED -> l.keyReleased(e)
                dev.ide.awt.event.KeyEvent.KEY_TYPED -> l.keyTyped(e)
            }
        }
    }

    /** Entry point for the surface, which is outside this class's package in AWT terms. */
    internal fun dispatchKeyEvent(e: dev.ide.awt.event.KeyEvent) = processKeyEvent(e)

    /**
     * Deliver [e] to this component. Widgets that react to the mouse (a button) override this, call `super`
     * to keep the listeners working, and add their own behaviour, exactly as `AbstractButton` does.
     */
    protected open fun processMouseEvent(e: MouseEvent) {
        for (l in mouseListeners.toList()) {
            when (e.id) {
                MouseEvent.MOUSE_PRESSED -> l.mousePressed(e)
                MouseEvent.MOUSE_RELEASED -> l.mouseReleased(e)
                MouseEvent.MOUSE_CLICKED -> l.mouseClicked(e)
                MouseEvent.MOUSE_ENTERED -> l.mouseEntered(e)
                MouseEvent.MOUSE_EXITED -> l.mouseExited(e)
                MouseEvent.MOUSE_DRAGGED -> Unit // a drag reaches MouseMotionListener in AWT, which is not modelled

            }
        }
    }

    /** Entry point for the surface, which is outside this class's package in AWT terms. */
    internal fun dispatchMouseEvent(e: MouseEvent) = processMouseEvent(e)
}

/**
 * `java.awt.Container`: a component with children and a [LayoutManager] that positions them.
 *
 * Layout is lazy and cached: [validate] re-runs it only when something invalidated the tree, so a paint pass
 * over an unchanged UI does no layout work at all.
 */
open class Container : Component() {

    private val children = ArrayList<Component>()
    private val constraints = HashMap<Component, Any?>()
    private var layoutManager: LayoutManager? = null
    private var valid = false

    open fun getLayout(): LayoutManager? = layoutManager

    open fun setLayout(manager: LayoutManager?) {
        layoutManager = manager
        invalidate()
    }

    open fun add(comp: Component): Component = add(comp, null)

    open fun add(comp: Component, constraint: Any?): Component = addChild(comp, constraint)

    open fun remove(comp: Component) {
        removeChild(comp)
    }

    open fun removeAll() {
        children.toList().forEach { removeChild(it) }
    }

    /**
     * Put [comp] in this container, without going through [add].
     *
     * A subclass that forwards `add` somewhere else needs this: `JFrame.add` delegates to its content pane, so
     * a `super.add(contentPane)` in its constructor would come straight back through the override and make the
     * content pane its own child. Every path that must insert HERE calls this instead.
     */
    protected fun addChild(comp: Component, constraint: Any?): Component {
        if (comp === this) throw IllegalArgumentException("a container cannot contain itself")
        comp.parent?.removeChild(comp)
        children.add(comp)
        constraints[comp] = constraint
        comp.parent = this
        invalidate()
        return comp
    }

    /** Take [comp] out of this container, without going through [remove]; the counterpart of [addChild]. */
    protected fun removeChild(comp: Component) {
        if (children.remove(comp)) {
            constraints.remove(comp)
            comp.parent = null
            invalidate()
        }
    }

    fun getComponentCount(): Int = children.size
    fun getComponent(index: Int): Component = children[index]
    fun getComponents(): Array<Component> = children.toTypedArray()

    /** The children in add order, for a [LayoutManager]. */
    fun components(): List<Component> = children

    /** What [comp] was added with, e.g. `BorderLayout.CENTER`. */
    fun constraintFor(comp: Component): Any? = constraints[comp]

    /** The space a container reserves inside its bounds. Zero here; a bordered widget overrides it. */
    open fun getInsets(): Insets = NO_INSETS

    override fun computePreferredSize(): Dimension =
        layoutManager?.preferredLayoutSize(this) ?: boundsOfChildren()

    /** The smallest box holding every child where no layout manager has an opinion. */
    private fun boundsOfChildren(): Dimension {
        var w = 0
        var h = 0
        for (c in children) {
            w = maxOf(w, c.getX() + c.getWidth())
            h = maxOf(h, c.getY() + c.getHeight())
        }
        val insets = getInsets()
        return Dimension(w + insets.left + insets.right, h + insets.top + insets.bottom)
    }

    open fun doLayout() {
        layoutManager?.layoutContainer(this)
    }

    override fun invalidate() {
        valid = false
        super.invalidate()
    }

    /** Lay this container out, then everything beneath it, if anything invalidated the tree. */
    open fun validate() {
        if (valid) return
        valid = true
        doLayout()
        for (c in children) if (c is Container) c.validate()
    }

    override fun paint(g: Graphics) {
        paintChildren(g)
    }

    /** Paint each visible child in its own translated, clipped coordinate space. */
    protected open fun paintChildren(g: Graphics) {
        if (g !is CanvasGraphics) return
        for (c in children) {
            if (!c.isVisible() || c.getWidth() <= 0 || c.getHeight() <= 0) continue
            g.push(c.getX(), c.getY(), c.getWidth(), c.getHeight())
            try {
                c.paint(g)
            } finally {
                g.pop()
            }
        }
    }

    /**
     * The deepest visible descendant containing ([px], [py]), given in this container's own space, or this
     * container when the point hits no child. Last-added wins, matching AWT's front-to-back z-order.
     */
    open fun componentAt(px: Int, py: Int): Component {
        for (c in children.asReversed()) {
            if (!c.isVisible()) continue
            val localX = px - c.getX()
            val localY = py - c.getY()
            if (!c.contains(localX, localY)) continue
            return if (c is Container) c.componentAt(localX, localY) else c
        }
        return this
    }

    private companion object {
        val NO_INSETS = Insets(0, 0, 0, 0)
    }
}

/** What a component needs from whatever is displaying it. Implemented by [Window]. */
internal interface Surface {
    /** Mark the next frame as needing a repaint. */
    fun invalidateFrame()

    /** Send keyboard events to [component] from now on. Returns false when it cannot take focus. */
    fun focusOn(component: Component): Boolean

    /** A graphics bound to the backend but to no canvas, for measuring text outside a paint pass. */
    fun measuringGraphics(): CanvasGraphics?
}

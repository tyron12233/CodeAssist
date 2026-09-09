package dev.ide.swing

import dev.ide.awt.Color
import dev.ide.awt.Container
import dev.ide.awt.Dimension
import dev.ide.awt.Font
import dev.ide.awt.Graphics
import dev.ide.awt.Insets
import dev.ide.awt.event.ActionEvent
import dev.ide.awt.event.ActionListener
import dev.ide.awt.event.MouseEvent

/**
 * `javax.swing.JComponent`. The paint order is Swing's, and it is the part a program depends on: `paint` calls
 * [paintComponent] and then paints the children, so a subclass that overrides `paintComponent` and starts with
 * `super.paintComponent(g)` gets its background filled and then draws on top of it, with children above that.
 */
open class JComponent : Container() {

    private var opaque = true
    private var border: dev.ide.swing.border.Border? = null

    open fun isOpaque(): Boolean = opaque

    open fun setOpaque(value: Boolean) {
        opaque = value
    }

    /** The border, which also decides this component's insets, as it does in Swing. */
    open fun getBorder(): dev.ide.swing.border.Border? = border

    open fun setBorder(value: dev.ide.swing.border.Border?) {
        border = value
        invalidate()
        repaint()
    }

    /** A bordered component reserves the border's space; a widget with padding of its own adds to it. */
    override fun getInsets(): Insets = border?.getBorderInsets(this) ?: super.getInsets()

    override fun paint(g: Graphics) {
        paintComponent(g)
        paintBorder(g)
        paintChildren(g)
    }

    /** Draw the border over the component's own content, as Swing does, so content cannot spill past it. */
    protected open fun paintBorder(g: Graphics) {
        border?.paintBorder(this, g, 0, 0, getWidth(), getHeight())
    }

    /**
     * Draw this component. The default fills the background when opaque, which is what `super.paintComponent(g)`
     * is for in user code; everything a widget shows on top of that goes in an override.
     */
    protected open fun paintComponent(g: Graphics) {
        if (!opaque) return
        val bg = getBackground() ?: return
        g.setColor(bg)
        g.fillRect(0, 0, getWidth(), getHeight())
    }
}

/** `javax.swing.JPanel`: the blank component programs subclass to draw on, and the default container. */
open class JPanel @JvmOverloads constructor(layout: dev.ide.awt.LayoutManager? = dev.ide.awt.FlowLayout()) : JComponent() {
    init {
        setLayout(layout)
        setBackground(Color.WHITE)
    }
}

/** `javax.swing.JLabel`: text, vertically centred, with a left inset so it does not touch its own edge. */
open class JLabel @JvmOverloads constructor(private var text: String? = null) : JComponent() {

    init {
        setOpaque(false)
    }

    open fun getText(): String? = text

    open fun setText(value: String?) {
        text = value
        invalidate()
        repaint()
    }

    override fun getInsets(): Insets = INSETS

    override fun computePreferredSize(): Dimension {
        val metrics = metrics() ?: return Dimension(0, 0)
        return Dimension(
            metrics.stringWidth(text ?: "") + INSETS.left + INSETS.right,
            metrics.getHeight() + INSETS.top + INSETS.bottom,
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val label = text ?: return
        val metrics = g.getFontMetrics(getFont())
        g.setColor(getForeground())
        val baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent()
        g.drawString(label, INSETS.left, baseline)
    }

    private companion object {
        val INSETS = Insets(2, 4, 2, 4)
    }
}

/**
 * `javax.swing.AbstractButton`: the listener plumbing shared by every button. A press followed by a release
 * inside the same button fires the action, which is what makes a click a click.
 */
abstract class AbstractButton : JComponent() {

    private val actionListeners = ArrayList<ActionListener>()
    private var text: String? = null
    private var actionCommand: String? = null
    private var armed = false

    open fun getText(): String? = text

    open fun setText(value: String?) {
        text = value
        invalidate()
        repaint()
    }

    open fun getActionCommand(): String? = actionCommand ?: text

    open fun setActionCommand(command: String?) {
        actionCommand = command
    }

    fun addActionListener(l: ActionListener?) {
        if (l != null) actionListeners.add(l)
    }

    fun removeActionListener(l: ActionListener?) {
        if (l != null) actionListeners.remove(l)
    }

    fun getActionListeners(): Array<ActionListener> = actionListeners.toTypedArray()

    /** Activate the button programmatically, as `doClick` does in Swing. */
    open fun doClick() {
        if (isEnabled()) fireActionPerformed()
    }

    /** True while the button is held down, so a subclass can paint a pressed state. */
    protected fun isArmed(): Boolean = armed

    protected open fun fireActionPerformed() {
        val e = ActionEvent(this, ActionEvent.ACTION_PERFORMED, getActionCommand())
        for (l in actionListeners.toList()) l.actionPerformed(e)
    }

    override fun processMouseEvent(e: MouseEvent) {
        super.processMouseEvent(e)
        if (!isEnabled()) return
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> {
                armed = true
                repaint()
            }
            MouseEvent.MOUSE_RELEASED -> {
                val wasArmed = armed
                armed = false
                repaint()
                // Only a release that lands back inside the button counts, so dragging off it cancels.
                if (wasArmed && contains(e.x, e.y)) fireActionPerformed()
            }
            MouseEvent.MOUSE_EXITED -> {
                armed = false
                repaint()
            }
        }
    }
}

/** `javax.swing.JButton`: a labelled, bordered button. */
open class JButton @JvmOverloads constructor(text: String? = null) : AbstractButton() {

    init {
        setText(text)
        setBackground(BACKGROUND)
    }

    override fun getInsets(): Insets = INSETS

    override fun computePreferredSize(): Dimension {
        val metrics = metrics() ?: return Dimension(0, 0)
        return Dimension(
            metrics.stringWidth(getText() ?: "") + INSETS.left + INSETS.right,
            metrics.getHeight() + INSETS.top + INSETS.bottom,
        )
    }

    override fun paintComponent(g: Graphics) {
        val fill = if (isArmed()) (getBackground() ?: BACKGROUND).darker() else getBackground() ?: BACKGROUND
        g.setColor(fill)
        g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC)
        g.setColor(BORDER)
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC)

        val label = getText() ?: return
        val metrics = g.getFontMetrics(getFont())
        g.setColor(getForeground())
        g.drawString(
            label,
            (getWidth() - metrics.stringWidth(label)) / 2,
            (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent(),
        )
    }

    private companion object {
        val BACKGROUND = Color(0xEE, 0xEE, 0xEE)
        val BORDER = Color(0x9E, 0x9E, 0x9E)
        val INSETS = Insets(6, 14, 6, 14)
        const val ARC = 10
    }
}


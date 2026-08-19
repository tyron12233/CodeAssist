package dev.ide.swing

import dev.ide.awt.Color
import dev.ide.awt.Dimension
import dev.ide.awt.Graphics
import dev.ide.awt.Insets
import dev.ide.awt.event.ItemEvent
import dev.ide.awt.event.ItemListener

/**
 * `javax.swing.JToggleButton`: a button that stays down. The parent of the checkbox and the radio button in
 * Swing, and the place their shared selected-state and [ItemListener] plumbing lives.
 */
open class JToggleButton @JvmOverloads constructor(
    text: String? = null,
    selected: Boolean = false,
) : AbstractButton() {

    private val itemListeners = ArrayList<ItemListener>()
    private var selectedState = selected

    /** The group this belongs to, which is what makes radio buttons mutually exclusive. */
    internal var group: ButtonGroup? = null

    init {
        setText(text)
        setOpaque(false)
        setBackground(BACKGROUND)
    }

    open fun isSelected(): Boolean = selectedState

    open fun setSelected(value: Boolean) {
        if (selectedState == value) return
        selectedState = value
        if (value) group?.selected(this)
        fireItemStateChanged()
        repaint()
    }

    fun addItemListener(l: ItemListener?) {
        if (l != null) itemListeners.add(l)
    }

    fun removeItemListener(l: ItemListener?) {
        if (l != null) itemListeners.remove(l)
    }

    /**
     * Activating a toggle changes its state BEFORE the action listeners run, so a listener that reads
     * `isSelected()` sees what the user just chose rather than what it was.
     *
     * A button in a group only ever selects: clicking the radio button that is already on must not turn it
     * off, or the group would end up with nothing selected.
     */
    override fun fireActionPerformed() {
        if (group != null) setSelected(true) else setSelected(!selectedState)
        super.fireActionPerformed()
    }

    private fun fireItemStateChanged() {
        val e = ItemEvent(
            this, ItemEvent.ITEM_STATE_CHANGED, this,
            if (selectedState) ItemEvent.SELECTED else ItemEvent.DESELECTED,
        )
        for (l in itemListeners.toList()) l.itemStateChanged(e)
    }

    override fun getInsets(): Insets = getBorder()?.getBorderInsets(this) ?: INSETS

    override fun computePreferredSize(): Dimension {
        val metrics = metrics() ?: return Dimension(0, 0)
        val insets = getInsets()
        return Dimension(
            BOX + GAP + metrics.stringWidth(getText().orEmpty()) + insets.left + insets.right,
            maxOf(BOX, metrics.getHeight()) + insets.top + insets.bottom,
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val metrics = g.getFontMetrics(getFont())
        val insets = getInsets()
        val top = insets.top + (getHeight() - insets.top - insets.bottom - BOX) / 2
        paintIndicator(g, insets.left, top)
        getText()?.let {
            g.setColor(getForeground())
            g.drawString(it, insets.left + BOX + GAP, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent())
        }
    }

    /** The mark that shows the state. A plain toggle fills its whole face; the subclasses draw a box or a dot. */
    protected open fun paintIndicator(g: Graphics, x: Int, y: Int) {
        g.setColor(if (isSelected()) SELECTED else BACKGROUND)
        g.fillRoundRect(x, y, BOX, BOX, 4, 4)
        g.setColor(BORDER)
        g.drawRoundRect(x, y, BOX - 1, BOX - 1, 4, 4)
    }

    protected companion object {
        val BACKGROUND = Color(0xEE, 0xEE, 0xEE)
        val BORDER = Color(0x9E, 0x9E, 0x9E)
        val SELECTED = Color(0x2D, 0x6C, 0xDF)
        val INSETS = Insets(4, 4, 4, 6)
        const val BOX = 16
        const val GAP = 6
    }
}

/** `javax.swing.JCheckBox`: a square that shows a tick when selected. */
open class JCheckBox @JvmOverloads constructor(
    text: String? = null,
    selected: Boolean = false,
) : JToggleButton(text, selected) {

    override fun paintIndicator(g: Graphics, x: Int, y: Int) {
        g.setColor(if (isSelected()) SELECTED else Color.WHITE)
        g.fillRoundRect(x, y, BOX, BOX, 3, 3)
        g.setColor(BORDER)
        g.drawRoundRect(x, y, BOX - 1, BOX - 1, 3, 3)
        if (!isSelected()) return
        // A tick, as two strokes rather than a font glyph so it does not depend on the typeface.
        g.setColor(Color.WHITE)
        g.drawLine(x + 4, y + BOX / 2, x + BOX / 2 - 1, y + BOX - 5)
        g.drawLine(x + BOX / 2 - 1, y + BOX - 5, x + BOX - 4, y + 4)
    }
}

/** `javax.swing.JRadioButton`: a circle, normally one of a [ButtonGroup]. */
open class JRadioButton @JvmOverloads constructor(
    text: String? = null,
    selected: Boolean = false,
) : JToggleButton(text, selected) {

    override fun paintIndicator(g: Graphics, x: Int, y: Int) {
        g.setColor(Color.WHITE)
        g.fillOval(x, y, BOX, BOX)
        g.setColor(if (isSelected()) SELECTED else BORDER)
        g.drawOval(x, y, BOX - 1, BOX - 1)
        if (!isSelected()) return
        g.setColor(SELECTED)
        g.fillOval(x + 4, y + 4, BOX - 8, BOX - 8)
    }
}

/**
 * `javax.swing.ButtonGroup`: makes a set of toggles mutually exclusive.
 *
 * The group holds no layout role at all, which surprises people: adding buttons to it does not place them
 * anywhere, it only means selecting one clears the others.
 */
class ButtonGroup {

    private val buttons = ArrayList<JToggleButton>()

    fun add(button: JToggleButton?) {
        if (button == null || buttons.any { it === button }) return
        buttons.add(button)
        button.group = this
        if (button.isSelected()) selected(button)
    }

    fun remove(button: JToggleButton?) {
        if (button == null) return
        if (buttons.removeAll { it === button }) button.group = null
    }

    fun getButtonCount(): Int = buttons.size

    /** The selected button, or null when none is. */
    fun getSelection(): JToggleButton? = buttons.firstOrNull { it.isSelected() }

    fun clearSelection() {
        buttons.forEach { it.setSelected(false) }
    }

    /** Called when [button] became selected: everything else in the group clears. */
    internal fun selected(button: JToggleButton) {
        for (other in buttons) if (other !== button && other.isSelected()) other.setSelected(false)
    }
}

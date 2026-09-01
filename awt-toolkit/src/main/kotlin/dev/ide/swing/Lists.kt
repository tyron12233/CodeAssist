package dev.ide.swing

import dev.ide.awt.Color
import dev.ide.awt.Dimension
import dev.ide.awt.Graphics
import dev.ide.awt.Insets
import dev.ide.awt.event.ActionEvent
import dev.ide.awt.event.ActionListener
import dev.ide.awt.event.ItemEvent
import dev.ide.awt.event.ItemListener
import dev.ide.awt.event.MouseEvent
import dev.ide.swing.border.LineBorder
import dev.ide.swing.event.ListSelectionEvent
import dev.ide.swing.event.ListSelectionListener

/** `javax.swing.ListModel`: the items a list shows, and a way to hear about changes to them. */
interface ListModel<E> {
    fun getSize(): Int
    fun getElementAt(index: Int): E
}

/** `javax.swing.AbstractListModel`. */
abstract class AbstractListModel<E> : ListModel<E>

/** `javax.swing.DefaultListModel`: the mutable model programs actually use. */
open class DefaultListModel<E> : AbstractListModel<E>() {

    private val items = ArrayList<E>()

    override fun getSize(): Int = items.size
    override fun getElementAt(index: Int): E = items[index]

    open fun addElement(element: E) {
        items.add(element)
    }

    open fun add(index: Int, element: E) {
        items.add(index.coerceIn(0, items.size), element)
    }

    open fun removeElement(element: E): Boolean = items.remove(element)

    open fun remove(index: Int): E = items.removeAt(index)

    open fun removeAllElements() = items.clear()

    open fun clear() = items.clear()

    open fun isEmpty(): Boolean = items.isEmpty()

    open fun size(): Int = items.size

    open fun elementAt(index: Int): E = items[index]

    open fun contains(element: E): Boolean = items.contains(element)

    open fun indexOf(element: E): Int = items.indexOf(element)
}

/** A model over a fixed array, which is what `new JList<>(items)` builds. */
private class ArrayListModel<E>(private val items: List<E>) : AbstractListModel<E>() {
    override fun getSize(): Int = items.size
    override fun getElementAt(index: Int): E = items[index]
}

/**
 * `javax.swing.JList`: rows of items, one of which can be selected.
 *
 * Single selection only. The multi-interval selection model is a large surface that a program reaches for far
 * less often than it reaches for `getSelectedValue`, and pretending to support it would be worse than not.
 */
open class JList<E> : JComponent {

    private var model: ListModel<E>
    private var selectedIndex = -1
    private val selectionListeners = ArrayList<ListSelectionListener>()
    private var visibleRowCount = 8

    constructor(model: ListModel<E>) : super() {
        this.model = model
        init()
    }

    constructor(items: Array<E>) : this(ArrayListModel(items.toList()))

    constructor() : this(DefaultListModel<E>())

    private fun init() {
        setBackground(Color.WHITE)
        setOpaque(true)
        setBorder(LineBorder(BORDER))
    }

    override fun isFocusable(): Boolean = true

    open fun getModel(): ListModel<E> = model

    open fun setModel(value: ListModel<E>) {
        model = value
        selectedIndex = -1
        invalidate()
        repaint()
    }

    open fun setListData(items: Array<E>) = setModel(ArrayListModel(items.toList()))

    open fun getSelectedIndex(): Int = selectedIndex

    open fun setSelectedIndex(index: Int) {
        val next = if (index in 0 until model.getSize()) index else -1
        if (next == selectedIndex) return
        selectedIndex = next
        fireSelectionChanged()
        repaint()
    }

    open fun getSelectedValue(): E? = selectedIndex.takeIf { it >= 0 }?.let { model.getElementAt(it) }

    open fun clearSelection() = setSelectedIndex(-1)

    open fun getVisibleRowCount(): Int = visibleRowCount

    open fun setVisibleRowCount(count: Int) {
        visibleRowCount = count
        invalidate()
    }

    fun addListSelectionListener(l: ListSelectionListener?) {
        if (l != null) selectionListeners.add(l)
    }

    fun removeListSelectionListener(l: ListSelectionListener?) {
        if (l != null) selectionListeners.remove(l)
    }

    private fun fireSelectionChanged() {
        val e = ListSelectionEvent(this, selectedIndex, selectedIndex, false)
        for (l in selectionListeners.toList()) l.valueChanged(e)
    }

    /** The row at [y] in this list's own space, or -1 past the last one. */
    open fun locationToIndex(y: Int): Int {
        val height = rowHeight()
        if (height <= 0) return -1
        val index = (y - getInsets().top) / height
        return if (index in 0 until model.getSize()) index else -1
    }

    override fun processMouseEvent(e: MouseEvent) {
        super.processMouseEvent(e)
        if (e.id == MouseEvent.MOUSE_PRESSED) locationToIndex(e.y).takeIf { it >= 0 }?.let { setSelectedIndex(it) }
    }

    private fun rowHeight(): Int = (metrics()?.getHeight() ?: 0) + ROW_PADDING * 2

    override fun computePreferredSize(): Dimension {
        val metrics = metrics() ?: return Dimension(0, 0)
        val insets = getInsets()
        var width = 0
        for (i in 0 until model.getSize()) {
            width = maxOf(width, metrics.stringWidth(model.getElementAt(i).toString()))
        }
        val rows = if (model.getSize() > 0) minOf(model.getSize(), visibleRowCount) else 1
        return Dimension(
            width + insets.left + insets.right + ROW_PADDING * 2,
            rowHeight() * rows + insets.top + insets.bottom,
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val metrics = g.getFontMetrics(getFont())
        val insets = getInsets()
        val height = metrics.getHeight() + ROW_PADDING * 2
        var y = insets.top
        for (i in 0 until model.getSize()) {
            if (y > getHeight()) break
            if (i == selectedIndex) {
                g.setColor(SELECTION)
                g.fillRect(insets.left, y, getWidth() - insets.left - insets.right, height)
            }
            g.setColor(if (i == selectedIndex) Color.WHITE else getForeground())
            g.drawString(model.getElementAt(i).toString(), insets.left + ROW_PADDING, y + ROW_PADDING + metrics.getAscent())
            y += height
        }
    }

    private companion object {
        val BORDER = Color(0x9E, 0x9E, 0x9E)
        val SELECTION = Color(0x2D, 0x6C, 0xDF)
        const val ROW_PADDING = 3
    }
}

/**
 * `javax.swing.JComboBox`: a button showing the selection, which opens a list of the choices.
 *
 * The popup is drawn by the window as an overlay rather than by a real layered pane, because a toolkit that
 * paints one frame at a time only needs the popup drawn LAST, and that is what an overlay is. It closes on the
 * next press anywhere, which is what a dropdown does.
 */
open class JComboBox<E> : JComponent {

    private val items = ArrayList<E>()
    private val actionListeners = ArrayList<ActionListener>()
    private val itemListeners = ArrayList<ItemListener>()
    private var selectedIndex = -1
    private var popupVisible = false

    constructor(values: Array<E>) : super() {
        items.addAll(values)
        if (items.isNotEmpty()) selectedIndex = 0
        init()
    }

    constructor() : super() {
        init()
    }

    private fun init() {
        setBackground(Color.WHITE)
        setOpaque(true)
        setBorder(LineBorder(BORDER))
    }

    override fun isFocusable(): Boolean = true

    open fun addItem(item: E) {
        items.add(item)
        if (selectedIndex < 0) setSelectedIndex(0)
        invalidate()
        repaint()
    }

    open fun removeItem(item: E) {
        val index = items.indexOf(item)
        if (index < 0) return
        items.removeAt(index)
        if (selectedIndex >= items.size) setSelectedIndex(items.size - 1)
        invalidate()
        repaint()
    }

    open fun removeAllItems() {
        items.clear()
        selectedIndex = -1
        invalidate()
        repaint()
    }

    open fun getItemCount(): Int = items.size

    open fun getItemAt(index: Int): E? = items.getOrNull(index)

    open fun getSelectedIndex(): Int = selectedIndex

    open fun getSelectedItem(): Any? = items.getOrNull(selectedIndex)

    open fun setSelectedIndex(index: Int) {
        val next = if (index in items.indices) index else -1
        if (next == selectedIndex) return
        selectedIndex = next
        fireSelectionChanged()
        repaint()
    }

    open fun setSelectedItem(item: Any?) = setSelectedIndex(items.indexOfFirst { it == item })

    fun addActionListener(l: ActionListener?) {
        if (l != null) actionListeners.add(l)
    }

    fun addItemListener(l: ItemListener?) {
        if (l != null) itemListeners.add(l)
    }

    open fun isPopupVisible(): Boolean = popupVisible

    open fun setPopupVisible(value: Boolean) {
        if (popupVisible == value) return
        popupVisible = value
        repaint()
    }

    private fun fireSelectionChanged() {
        val item = getSelectedItem()
        val itemEvent = ItemEvent(this, ItemEvent.ITEM_STATE_CHANGED, item, ItemEvent.SELECTED)
        for (l in itemListeners.toList()) l.itemStateChanged(itemEvent)
        val actionEvent = ActionEvent(this, ActionEvent.ACTION_PERFORMED, "comboBoxChanged")
        for (l in actionListeners.toList()) l.actionPerformed(actionEvent)
    }

    override fun processMouseEvent(e: MouseEvent) {
        super.processMouseEvent(e)
        if (e.id != MouseEvent.MOUSE_PRESSED) return
        if (!popupVisible) {
            setPopupVisible(true)
            return
        }
        // A press while open picks the row it landed on, if it landed on one.
        val row = (e.y - getHeight()) / rowHeight()
        if (e.y > getHeight() && row in items.indices) setSelectedIndex(row)
        setPopupVisible(false)
    }

    private fun rowHeight(): Int = (metrics()?.getHeight() ?: 0) + PADDING * 2

    override fun computePreferredSize(): Dimension {
        val metrics = metrics() ?: return Dimension(0, 0)
        val insets = getInsets()
        val widest = items.maxOfOrNull { metrics.stringWidth(it.toString()) } ?: 0
        return Dimension(
            widest + ARROW + PADDING * 3 + insets.left + insets.right,
            metrics.getHeight() + PADDING * 2 + insets.top + insets.bottom,
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val metrics = g.getFontMetrics(getFont())
        val insets = getInsets()
        g.setColor(getForeground())
        getSelectedItem()?.let {
            g.drawString(it.toString(), insets.left + PADDING, insets.top + PADDING + metrics.getAscent())
        }
        // The arrow, as a triangle of lines: the toolkit has no filled polygon and this needs none.
        val cx = getWidth() - insets.right - ARROW
        val cy = getHeight() / 2
        g.setColor(BORDER)
        g.drawLine(cx, cy - 2, cx + ARROW / 2, cy + 3)
        g.drawLine(cx + ARROW / 2, cy + 3, cx + ARROW, cy - 2)
    }

    /** How tall the open list is, so the window can hit-test the area the popup covers. */
    internal fun popupHeight(): Int = if (!popupVisible) 0 else rowHeight() * items.size

    /**
     * Draw the open list. Called by the window AFTER the whole tree, so the popup covers what is under it;
     * coordinates are this component's, translated by the window.
     */
    internal fun paintPopup(g: Graphics) {
        if (!popupVisible || items.isEmpty()) return
        val metrics = g.getFontMetrics(getFont())
        val height = metrics.getHeight() + PADDING * 2
        val top = getHeight()
        g.setColor(Color.WHITE)
        g.fillRect(0, top, getWidth(), height * items.size)
        g.setColor(BORDER)
        g.drawRect(0, top, getWidth() - 1, height * items.size - 1)
        for ((i, item) in items.withIndex()) {
            val y = top + i * height
            if (i == selectedIndex) {
                g.setColor(SELECTION)
                g.fillRect(1, y + 1, getWidth() - 2, height - 2)
            }
            g.setColor(if (i == selectedIndex) Color.WHITE else getForeground())
            g.drawString(item.toString(), PADDING, y + PADDING + metrics.getAscent())
        }
    }

    private companion object {
        val BORDER = Color(0x9E, 0x9E, 0x9E)
        val SELECTION = Color(0x2D, 0x6C, 0xDF)
        const val PADDING = 4
        const val ARROW = 10
    }
}

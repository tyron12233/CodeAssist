package dev.ide.swing.text

import dev.ide.awt.Color
import dev.ide.awt.Component
import dev.ide.awt.Dimension
import dev.ide.awt.FontMetrics
import dev.ide.awt.Graphics
import dev.ide.awt.Insets
import dev.ide.awt.Window
import dev.ide.awt.event.ActionEvent
import dev.ide.awt.event.ActionListener
import dev.ide.awt.event.KeyEvent
import dev.ide.awt.event.MouseEvent
import dev.ide.swing.JComponent
import dev.ide.swing.border.LineBorder
import kotlin.math.abs

/**
 * `javax.swing.text.JTextComponent`: editable text with a caret, and the base of every text widget.
 *
 * The model is deliberately a [StringBuilder] and an integer caret rather than a `Document`: everything a
 * program does through the `JTextComponent` API (get, set, append, insert, caret position) is expressible that
 * way, and a real Document's element tree buys nothing until there is styled text to hold.
 *
 * Editing is driven by the keys the surface forwards. The caret does not blink: a toolkit whose output is a
 * frame per repaint draws it solid, which reads correctly and costs no timer.
 */
open class JTextComponent : JComponent() {

    private val content = StringBuilder()
    private var caret = 0
    private var editable = true

    init {
        setBackground(Color.WHITE)
        setOpaque(true)
    }

    /** Text is where keys are supposed to go, so it takes focus when pressed. */
    override fun isFocusable(): Boolean = true

    open fun getText(): String = content.toString()

    open fun setText(text: String?) {
        content.setLength(0)
        content.append(text.orEmpty())
        caret = content.length
        invalidate()
        repaint()
    }

    open fun isEditable(): Boolean = editable

    open fun setEditable(value: Boolean) {
        editable = value
    }

    open fun getCaretPosition(): Int = caret

    open fun setCaretPosition(position: Int) {
        caret = position.coerceIn(0, content.length)
        repaint()
    }

    open fun append(text: String?) {
        if (text.isNullOrEmpty()) return
        content.append(text)
        caret = content.length
        invalidate()
        repaint()
    }

    open fun insert(text: String?, position: Int) {
        if (text.isNullOrEmpty()) return
        val at = position.coerceIn(0, content.length)
        content.insert(at, text)
        if (caret >= at) caret += text.length
        invalidate()
        repaint()
    }

    open fun replaceRange(text: String?, start: Int, end: Int) {
        val from = start.coerceIn(0, content.length)
        val to = end.coerceIn(from, content.length)
        content.replace(from, to, text.orEmpty())
        caret = from + text.orEmpty().length
        invalidate()
        repaint()
    }

    /** The characters actually drawn. A password field shows its echo character instead of the text. */
    protected open fun displayText(): String = content.toString()

    /** Called when Enter is pressed. True inserts the newline (a text area); false does not (a field, which
     *  fires its action instead). */
    protected open fun onEnter(): Boolean = false

    override fun processMouseEvent(e: MouseEvent) {
        super.processMouseEvent(e)
        if (e.id != MouseEvent.MOUSE_PRESSED) return
        // Put the caret where the press landed, measured with the metrics the paint uses, so the caret ends up
        // under the finger rather than near it.
        val metrics = metrics() ?: return
        val text = displayText()
        val left = getInsets().left + PADDING
        var best = 0
        var bestDx = Int.MAX_VALUE
        for (i in 0..text.length) {
            val dx = abs(left + metrics.stringWidth(text.substring(0, i)) - e.x)
            if (dx < bestDx) {
                bestDx = dx
                best = i
            }
        }
        caret = best
        repaint()
    }

    override fun processKeyEvent(e: KeyEvent) {
        super.processKeyEvent(e)
        if (!editable || e.id != KeyEvent.KEY_PRESSED) return
        var edited = true
        when (e.getKeyCode()) {
            KeyEvent.VK_BACK_SPACE -> if (caret > 0) {
                content.deleteCharAt(caret - 1)
                caret--
            } else edited = false
            KeyEvent.VK_DELETE -> if (caret < content.length) content.deleteCharAt(caret) else edited = false
            KeyEvent.VK_LEFT -> {
                caret = (caret - 1).coerceAtLeast(0)
                edited = false
            }
            KeyEvent.VK_RIGHT -> {
                caret = (caret + 1).coerceAtMost(content.length)
                edited = false
            }
            KeyEvent.VK_ENTER -> if (onEnter()) {
                content.insert(caret, '\n')
                caret++
            } else edited = false
            else -> {
                val c = e.getKeyChar()
                if (c.code >= FIRST_PRINTABLE && c != CHAR_UNDEFINED) {
                    content.insert(caret, c)
                    caret++
                } else edited = false
            }
        }
        if (edited) invalidate()
        repaint()
    }

    override fun computePreferredSize(): Dimension {
        val metrics = metrics() ?: return Dimension(0, 0)
        val insets = getInsets()
        val lines = displayText().split('\n')
        val width = lines.maxOfOrNull { metrics.stringWidth(it) } ?: 0
        return Dimension(
            maxOf(width, preferredContentWidth(metrics)) + insets.left + insets.right + PADDING * 2,
            metrics.getHeight() * maxOf(lines.size, preferredRows()) + insets.top + insets.bottom + PADDING * 2,
        )
    }

    /** Width a subclass wants regardless of content, e.g. a field's declared column count. */
    protected open fun preferredContentWidth(metrics: FontMetrics): Int = 0

    /** Rows a subclass wants regardless of content. */
    protected open fun preferredRows(): Int = 1

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val metrics = g.getFontMetrics(getFont())
        val insets = getInsets()
        val x = insets.left + PADDING
        var baseline = insets.top + PADDING + metrics.getAscent()
        g.setColor(getForeground())
        for (line in displayText().split('\n')) {
            g.drawString(line, x, baseline)
            baseline += metrics.getHeight()
        }
        if (hasFocus()) paintCaret(g, metrics, x, insets)
    }

    /** A solid caret at the insertion point, on the row the caret is in. */
    private fun paintCaret(g: Graphics, metrics: FontMetrics, x: Int, insets: Insets) {
        val text = displayText()
        val before = text.take(caret.coerceAtMost(text.length))
        val row = before.count { it == '\n' }
        val cx = x + metrics.stringWidth(before.substringAfterLast('\n'))
        val top = insets.top + PADDING + row * metrics.getHeight()
        g.setColor(getForeground())
        g.drawLine(cx, top, cx, top + metrics.getHeight() - 1)
    }

    /** Whether this component currently holds the window's keyboard focus. */
    protected fun hasFocus(): Boolean = window()?.getFocusOwner() === this

    private fun window(): Window? {
        var node: Component? = this
        while (node != null) {
            if (node is Window) return node
            node = node.parent
        }
        return null
    }

    companion object {
        internal const val PADDING = 3

        /** Space and above: anything below is a control character rather than something to insert. */
        internal const val FIRST_PRINTABLE = 0x20
        internal const val CHAR_UNDEFINED = '￿'
    }
}

package dev.ide.swing

import dev.ide.awt.Color
import dev.ide.awt.FontMetrics
import dev.ide.awt.event.ActionEvent
import dev.ide.awt.event.ActionListener
import dev.ide.swing.border.LineBorder
import dev.ide.swing.text.JTextComponent

/** `javax.swing.JTextField`: one line of text, firing its action listeners on Enter. */
open class JTextField @JvmOverloads constructor(
    text: String? = null,
    private val columns: Int = 0,
) : JTextComponent() {

    private val actionListeners = ArrayList<ActionListener>()

    init {
        setText(text)
        setBorder(LineBorder(Color(0x9E, 0x9E, 0x9E)))
    }

    /** `new JTextField(20)`: the column count, not the text. */
    constructor(columns: Int) : this(null, columns)

    fun addActionListener(l: ActionListener?) {
        if (l != null) actionListeners.add(l)
    }

    fun removeActionListener(l: ActionListener?) {
        if (l != null) actionListeners.remove(l)
    }

    open fun getColumns(): Int = columns

    /** A field never takes Enter as text; it fires instead, which is what makes it a form field. */
    override fun onEnter(): Boolean {
        val e = ActionEvent(this, ActionEvent.ACTION_PERFORMED, getText())
        for (l in actionListeners.toList()) l.actionPerformed(e)
        return false
    }

    override fun preferredContentWidth(metrics: FontMetrics): Int =
        if (columns > 0) metrics.charWidth('m') * columns else 0

    /** A field stays one line however much text it holds. */
    override fun displayText(): String = super.displayText().replace("\n", "")
}

/** `javax.swing.JPasswordField`: shows its echo character instead of what was typed. */
open class JPasswordField @JvmOverloads constructor(
    text: String? = null,
    columns: Int = 0,
) : JTextField(text, columns) {

    private var echoChar = BULLET

    fun getEchoChar(): Char = echoChar

    fun setEchoChar(c: Char) {
        echoChar = c
        repaint()
    }

    /** The real text. Swing deprecates `getText` here for exactly this reason. */
    fun getPassword(): CharArray = getText().toCharArray()

    override fun displayText(): String =
        if (echoChar == ' ') super.displayText() else echoChar.toString().repeat(getText().length)

    private companion object {
        const val BULLET = '•'
    }
}

/** `javax.swing.JTextArea`: many lines, where Enter inserts a newline instead of firing. */
open class JTextArea @JvmOverloads constructor(
    text: String? = null,
    private val rows: Int = 0,
    private val columns: Int = 0,
) : JTextComponent() {

    init {
        setText(text)
    }

    /** `new JTextArea(5, 40)`: rows and columns, not the text. */
    constructor(rows: Int, columns: Int) : this(null, rows, columns)

    open fun getRows(): Int = rows
    open fun getColumns(): Int = columns

    /** Enter belongs in the text here, which is the whole difference from a field. */
    override fun onEnter(): Boolean = true

    override fun preferredContentWidth(metrics: FontMetrics): Int =
        if (columns > 0) metrics.charWidth('m') * columns else 0

    override fun preferredRows(): Int = maxOf(rows, 1)
}

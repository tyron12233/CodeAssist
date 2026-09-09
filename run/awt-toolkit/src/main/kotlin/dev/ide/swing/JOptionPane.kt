package dev.ide.swing

import dev.ide.awt.BorderLayout
import dev.ide.awt.Color
import dev.ide.awt.Component
import dev.ide.awt.Dialog
import dev.ide.awt.Dimension
import dev.ide.awt.FlowLayout
import dev.ide.awt.Graphics
import dev.ide.awt.LayoutManager
import dev.ide.awt.ToolkitWindows
import dev.ide.awt.Window
import dev.ide.swing.border.EmptyBorder

/**
 * `javax.swing.JDialog`: a window over another one, holding a content pane exactly as [JFrame] does.
 *
 * `add` and `setLayout` forward to the content pane for the same reason they do on a frame, and for the same
 * reason it uses the non-virtual insertion path in its constructor: forwarding plus `super.add` is how a
 * container ends up containing itself.
 */
open class JDialog @JvmOverloads constructor(
    owner: Window? = null,
    title: String? = null,
    modal: Boolean = false,
) : Dialog(owner, title, modal) {

    private val contentPane: JPanel = JPanel(BorderLayout()).apply { setBackground(Color.WHITE) }
    private var closeOperation = WindowConstants.DISPOSE_ON_CLOSE

    init {
        setBackground(Color.WHITE)
        addChild(contentPane, null)
    }

    open fun getContentPane(): dev.ide.awt.Container = contentPane

    override fun add(comp: Component): Component = contentPane.add(comp)

    override fun add(comp: Component, constraint: Any?): Component = contentPane.add(comp, constraint)

    override fun remove(comp: Component) = contentPane.remove(comp)

    override fun setLayout(manager: LayoutManager?) = contentPane.setLayout(manager)

    override fun getLayout(): LayoutManager? = contentPane.getLayout()

    open fun setDefaultCloseOperation(operation: Int) {
        closeOperation = operation
    }

    open fun getDefaultCloseOperation(): Int = closeOperation

    open fun setResizable(value: Boolean) {}

    open fun setLocationRelativeTo(c: Component?) = centerOver(c as? Window)

    open fun pack() {
        validate()
        val preferred = contentPane.getPreferredSize()
        setSize(preferred.width, preferred.height)
        invalidate()
        validate()
    }

    override fun doLayout() {
        contentPane.setBounds(0, 0, getWidth(), getHeight())
    }

    override fun computePreferredSize(): Dimension = contentPane.getPreferredSize()

    override fun paint(g: Graphics) {
        getBackground()?.let {
            g.setColor(it)
            g.fillRect(0, 0, getWidth(), getHeight())
        }
        super.paint(g)
        // A dialog floats over the window behind it, so it needs an edge of its own to read as separate.
        g.setColor(EDGE)
        g.drawRect(0, 0, getWidth() - 1, getHeight() - 1)
    }

    private companion object {
        val EDGE = Color(0x9E, 0x9E, 0x9E)
    }
}

/**
 * `javax.swing.JOptionPane`: the standard message, confirm, and input dialogs.
 *
 * Each entry point builds a small modal [JDialog], shows it, and returns what the user chose. The blocking is
 * real: `showMessageDialog` does not return until the dialog closes, which it achieves through the nested pump
 * described on [Dialog]. A program written against Swing therefore reads the same here, with the answer on the
 * line after the call rather than in a callback.
 */
object JOptionPane {

    // Message kinds. Only the title differs; there is no icon set to vary, and inventing one would make the
    // dialogs look like something they are not.
    @JvmField val ERROR_MESSAGE = 0
    @JvmField val INFORMATION_MESSAGE = 1
    @JvmField val WARNING_MESSAGE = 2
    @JvmField val QUESTION_MESSAGE = 3
    @JvmField val PLAIN_MESSAGE = -1

    // Button sets.
    @JvmField val DEFAULT_OPTION = -1
    @JvmField val YES_NO_OPTION = 0
    @JvmField val YES_NO_CANCEL_OPTION = 1
    @JvmField val OK_CANCEL_OPTION = 2

    // Answers.
    @JvmField val YES_OPTION = 0
    @JvmField val OK_OPTION = 0
    @JvmField val NO_OPTION = 1
    @JvmField val CANCEL_OPTION = 2
    @JvmField val CLOSED_OPTION = -1

    @JvmStatic
    @JvmOverloads
    fun showMessageDialog(
        parent: Component?,
        message: Any?,
        title: String = "Message",
        messageType: Int = INFORMATION_MESSAGE,
    ) {
        val dialog = build(parent, message, title)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, GAP, GAP))
        buttons.add(closing(dialog, "OK") {})
        dialog.add(buttons, BorderLayout.SOUTH)
        show(dialog, parent)
    }

    @JvmStatic
    @JvmOverloads
    fun showConfirmDialog(
        parent: Component?,
        message: Any?,
        title: String = "Confirm",
        optionType: Int = YES_NO_OPTION,
    ): Int {
        var answer = CLOSED_OPTION
        val dialog = build(parent, message, title)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, GAP, GAP))
        when (optionType) {
            OK_CANCEL_OPTION -> {
                buttons.add(closing(dialog, "OK") { answer = OK_OPTION })
                buttons.add(closing(dialog, "Cancel") { answer = CANCEL_OPTION })
            }
            YES_NO_CANCEL_OPTION -> {
                buttons.add(closing(dialog, "Yes") { answer = YES_OPTION })
                buttons.add(closing(dialog, "No") { answer = NO_OPTION })
                buttons.add(closing(dialog, "Cancel") { answer = CANCEL_OPTION })
            }
            else -> {
                buttons.add(closing(dialog, "Yes") { answer = YES_OPTION })
                buttons.add(closing(dialog, "No") { answer = NO_OPTION })
            }
        }
        dialog.add(buttons, BorderLayout.SOUTH)
        show(dialog, parent)
        return answer
    }

    /** Returns what the user typed, or null when they cancelled, exactly as Swing's does. */
    @JvmStatic
    @JvmOverloads
    fun showInputDialog(
        parent: Component?,
        message: Any?,
        title: String = "Input",
        initialValue: String? = null,
    ): String? {
        var answer: String? = null
        val dialog = build(parent, message, title)
        val field = JTextField(initialValue, INPUT_COLUMNS)
        dialog.add(field, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, GAP, GAP))
        buttons.add(closing(dialog, "OK") { answer = field.getText() })
        buttons.add(closing(dialog, "Cancel") { answer = null })
        dialog.add(buttons, BorderLayout.SOUTH)
        // Enter in the field is the same as pressing OK, which is what makes a one-field dialog quick.
        field.addActionListener {
            answer = field.getText()
            dialog.dispose()
        }
        show(dialog, parent)
        return answer
    }

    /** The message area of every one of these dialogs: the text, padded, above whatever the caller adds. */
    private fun build(parent: Component?, message: Any?, title: String): JDialog {
        val dialog = JDialog(windowOf(parent), title, modal = true)
        val label = JLabel(message?.toString().orEmpty())
        label.setBorder(EmptyBorder(PAD, PAD, PAD, PAD))
        dialog.add(label, BorderLayout.NORTH)
        return dialog
    }

    /** A button that runs [onChosen] and then closes the dialog, which is every button on these dialogs. */
    private fun closing(dialog: JDialog, text: String, onChosen: () -> Unit): JButton =
        JButton(text).apply {
            addActionListener {
                onChosen()
                dialog.dispose()
            }
        }

    private fun show(dialog: JDialog, parent: Component?) {
        dialog.pack()
        dialog.centerOver(windowOf(parent) ?: ToolkitWindows.displayable().firstOrNull())
        // Blocks here when a pump is installed, and returns immediately when one is not.
        dialog.setVisible(true)
    }

    private fun windowOf(c: Component?): Window? {
        var node: Component? = c
        while (node != null) {
            if (node is Window) return node
            node = node.parent
        }
        return null
    }

    private const val PAD = 12
    private const val GAP = 6
    private const val INPUT_COLUMNS = 16
}

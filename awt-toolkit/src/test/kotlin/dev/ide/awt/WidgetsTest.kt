package dev.ide.awt

import dev.ide.awt.event.ItemEvent
import dev.ide.awt.event.KeyEvent
import dev.ide.swing.ButtonGroup
import dev.ide.swing.DefaultListModel
import dev.ide.swing.JCheckBox
import dev.ide.swing.JComboBox
import dev.ide.swing.JFrame
import dev.ide.swing.JList
import dev.ide.swing.JPanel
import dev.ide.swing.JProgressBar
import dev.ide.swing.JRadioButton
import dev.ide.swing.JScrollPane
import dev.ide.swing.JSlider
import dev.ide.swing.ToolkitEventQueue
import dev.ide.swing.border.EmptyBorder
import dev.ide.swing.border.LineBorder
import dev.ide.swing.JPasswordField
import dev.ide.swing.JTextArea
import dev.ide.swing.JTextField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The widget set beyond the core, driven the way a program drives it. Headless throughout: a recording backend
 * stands in for the screen, and input arrives as the surface would deliver it.
 */
class WidgetsTest {

    private val backend = RecordingBackend()

    @AfterTest fun tearDown() {
        ToolkitWindows.disposeAll()
        ToolkitEventQueue.clear()
    }

    private fun frame(width: Int = 300, height: Int = 240): JFrame =
        JFrame("widgets").apply {
            attachBackend(backend)
            setSize(width, height)
            setVisible(true)
        }

    /** Attach [c] so it can measure text, and lay it out. */
    private fun shown(c: dev.ide.awt.Component, width: Int = 300, height: Int = 240): JFrame {
        val f = frame(width, height)
        f.add(c, BorderLayout.CENTER)
        f.validate()
        return f
    }

    private fun type(f: JFrame, text: String) {
        for (ch in text) f.key(KEY_DOWN, ch.code, ch)
    }

    // ---- text --------------------------------------------------------------------------------------

    @Test fun typingIntoAFieldInsertsAtTheCaret() {
        val field = JTextField()
        val f = shown(field)
        f.pointer(POINTER_DOWN, 10, f.getHeight() / 2)
        f.pointer(POINTER_UP, 10, f.getHeight() / 2)

        type(f, "abc")
        assertEquals("abc", field.getText())

        // Left twice then a character: it lands between a and b.
        f.key(KEY_DOWN, KeyEvent.VK_LEFT, CHAR_UNDEFINED)
        f.key(KEY_DOWN, KeyEvent.VK_LEFT, CHAR_UNDEFINED)
        type(f, "X")
        assertEquals("aXbc", field.getText())
    }

    @Test fun backspaceDeletesBeforeTheCaretAndStopsAtTheStart() {
        val field = JTextField("hi")
        val f = shown(field)
        field.requestFocusInWindow()

        f.key(KEY_DOWN, KeyEvent.VK_BACK_SPACE, CHAR_UNDEFINED)
        assertEquals("h", field.getText())
        f.key(KEY_DOWN, KeyEvent.VK_BACK_SPACE, CHAR_UNDEFINED)
        f.key(KEY_DOWN, KeyEvent.VK_BACK_SPACE, CHAR_UNDEFINED)
        assertEquals("", field.getText(), "backspacing an empty field is not an error")
    }

    @Test fun enterFiresAFieldsActionInsteadOfBeingTyped() {
        val field = JTextField()
        val f = shown(field)
        field.requestFocusInWindow()
        var fired = 0
        var seen: String? = null
        field.addActionListener { e ->
            fired++
            seen = e.getActionCommand()
        }

        type(f, "go")
        f.key(KEY_DOWN, KeyEvent.VK_ENTER, '\n')

        assertEquals(1, fired)
        assertEquals("go", seen, "the action carries the field's text")
        assertEquals("go", field.getText(), "Enter is not inserted into a field")
    }

    @Test fun enterInATextAreaInsertsANewline() {
        val area = JTextArea()
        val f = shown(area)
        area.requestFocusInWindow()

        type(f, "a")
        f.key(KEY_DOWN, KeyEvent.VK_ENTER, '\n')
        type(f, "b")

        assertEquals("a\nb", area.getText())
    }

    @Test fun aPasswordFieldKeepsItsTextButDrawsTheEchoCharacter() {
        val field = JPasswordField()
        val f = shown(field)
        field.requestFocusInWindow()
        type(f, "secret")

        assertEquals("secret", field.getText())
        assertEquals("secret", field.getPassword().concatToString())

        backend.clear()
        f.paintTo(backend)
        val drawn = backend.texts().joinToString("")
        assertFalse("secret" in drawn, "the password must not be painted: $drawn")
        assertTrue(drawn.isNotEmpty() && drawn.all { it == field.getEchoChar() }, "drew: $drawn")
    }

    @Test fun anUneditableFieldIgnoresTyping() {
        val field = JTextField("fixed")
        val f = shown(field)
        field.requestFocusInWindow()
        field.setEditable(false)

        type(f, "more")

        assertEquals("fixed", field.getText())
    }

    // ---- toggles -----------------------------------------------------------------------------------

    @Test fun aCheckBoxFlipsAndTellsItsListenersTheNewState() {
        val box = JCheckBox("On?")
        val f = shown(box)
        val states = ArrayList<Boolean>()
        box.addItemListener { states.add(it.getStateChange() == ItemEvent.SELECTED) }
        box.addActionListener { states.add(box.isSelected()) }

        box.doClick()
        assertTrue(box.isSelected())
        box.doClick()
        assertFalse(box.isSelected())

        // Item then action per click, and the action already sees the new state.
        assertEquals(listOf(true, true, false, false), states)
        assertTrue(f.isDisplayable())
    }

    @Test fun aButtonGroupKeepsExactlyOneRadioSelected() {
        val a = JRadioButton("A")
        val b = JRadioButton("B")
        val group = ButtonGroup()
        group.add(a)
        group.add(b)
        shown(JPanel().apply { add(a); add(b) })

        a.doClick()
        assertTrue(a.isSelected())
        assertFalse(b.isSelected())

        b.doClick()
        assertFalse(a.isSelected(), "selecting one clears the other")
        assertTrue(b.isSelected())
        assertEquals(b, group.getSelection())

        // Clicking the selected one must not turn it off, or the group would end up empty.
        b.doClick()
        assertTrue(b.isSelected())
    }

    // ---- list and combo ----------------------------------------------------------------------------

    @Test fun clickingAListRowSelectsIt() {
        val model = DefaultListModel<String>()
        listOf("one", "two", "three").forEach { model.addElement(it) }
        val list = JList(model)
        val f = shown(list)
        val chosen = ArrayList<String?>()
        list.addListSelectionListener { chosen.add(list.getSelectedValue()) }

        val rowHeight = list.getHeight() / 8
        // Second row: past the border inset, one row down.
        f.pointer(POINTER_DOWN, 20, list.getY() + list.getInsets().top + rowHeight + 2)

        assertEquals(1, list.getSelectedIndex())
        assertEquals("two", list.getSelectedValue())
        assertEquals(listOf<String?>("two"), chosen.toList())
    }

    @Test fun aComboBoxOpensOnAPressAndPicksTheRowPressedNext() {
        val combo = JComboBox(arrayOf("red", "green", "blue"))
        val f = shown(combo, height = 300)
        val picked = ArrayList<Any?>()
        combo.addActionListener { picked.add(combo.getSelectedItem()) }

        assertEquals("red", combo.getSelectedItem(), "the first item is selected to begin with")

        val top = combo.getY()
        f.pointer(POINTER_DOWN, 20, top + 5)
        assertTrue(combo.isPopupVisible(), "a press opens the list")

        // The popup is drawn below the combo, one row per item; the third row is two rows down from its
        // bottom edge. The row height comes from the widget rather than being guessed at.
        val row = combo.popupHeight() / combo.getItemCount()
        f.pointer(POINTER_DOWN, 20, top + combo.getHeight() + row * 2 + 2)

        assertFalse(combo.isPopupVisible(), "picking closes it")
        assertEquals("blue", combo.getSelectedItem())
        assertEquals(listOf<Any?>("blue"), picked.toList())
    }

    @Test fun anOpenComboBoxIsDrawnOverWhateverIsBelowIt() {
        val combo = JComboBox(arrayOf("red", "green"))
        val below = JPanel()
        val f = frame(height = 300)
        f.add(combo, BorderLayout.NORTH)
        f.add(below, BorderLayout.CENTER)
        f.validate()
        combo.setPopupVisible(true)

        backend.clear()
        f.paintTo(backend)

        // The popup's rows are drawn after the tree, so its text is the last thing painted.
        assertEquals("green", backend.texts().last(), "drew: ${backend.texts()}")
    }

    // ---- scrolling ---------------------------------------------------------------------------------

    @Test fun aWheelNotchScrollsTheViewportAndStopsAtTheEnd() {
        val tall = JPanel().apply { setPreferredSize(Dimension(100, 2000)) }
        val pane = JScrollPane(tall)
        val f = shown(pane, height = 200)

        assertEquals(0, pane.getViewport().getViewPosition().y)

        f.wheel(20, 20, 3)
        val after = pane.getViewport().getViewPosition().y
        assertTrue(after > 0, "the wheel scrolled: $after")

        // Far past the end: it clamps rather than running off.
        repeat(200) { f.wheel(20, 20, 10) }
        val end = pane.getViewport().getViewPosition().y
        assertEquals(2000 - pane.getViewport().getHeight(), end, "scrolling stops at the content's end")
    }

    @Test fun aScrollPaneShowsItsBarOnlyWhenTheContentOverflows() {
        val short = JPanel().apply { setPreferredSize(Dimension(50, 20)) }
        val pane = JScrollPane(short)
        shown(pane, height = 200)
        assertFalse(pane.getVerticalScrollBar().isVisible(), "short content needs no bar")

        pane.setViewportView(JPanel().apply { setPreferredSize(Dimension(50, 5000)) })
        pane.doLayout()
        assertTrue(pane.getVerticalScrollBar().isVisible(), "tall content gets one")
    }

    // ---- borders and indicators --------------------------------------------------------------------

    @Test fun aBorderBecomesTheComponentsInsets() {
        val panel = JPanel()
        assertEquals(Insets(0, 0, 0, 0), panel.getInsets())

        panel.setBorder(EmptyBorder(4, 8, 4, 8))
        assertEquals(Insets(4, 8, 4, 8), panel.getInsets())

        panel.setBorder(LineBorder(Color.RED, 2))
        assertEquals(Insets(2, 2, 2, 2), panel.getInsets())
    }

    @Test fun aProgressBarReportsItsFractionAndClampsItsValue() {
        val bar = JProgressBar(0, 200)
        bar.setValue(50)
        assertEquals(0.25, bar.getPercentComplete())

        bar.setValue(9999)
        assertEquals(200, bar.getValue(), "a value past the maximum clamps")
        bar.setValue(-5)
        assertEquals(0, bar.getValue())
    }

    @Test fun draggingASliderMovesItsValueAndTellsItsListeners() {
        val slider = JSlider(0, 100, 0)
        val f = shown(slider)
        var changes = 0
        slider.addChangeListener { changes++ }

        val y = slider.getY() + slider.getHeight() / 2
        f.pointer(POINTER_DOWN, slider.getWidth() / 2, y)
        val mid = slider.getValue()
        assertTrue(mid in 40..60, "a press in the middle is about half: $mid")

        f.pointer(POINTER_MOVE, slider.getWidth() - 1, y)
        assertEquals(100, slider.getValue(), "dragging to the end is the maximum")
        assertTrue(changes >= 2)
    }

    @Test fun aDetachedListStillReportsItsModel() {
        // Nothing here is attached to a window, so nothing can measure text; the model must still work.
        val list = JList(arrayOf("a", "b"))
        assertEquals(2, list.getModel().getSize())
        assertEquals(-1, list.getSelectedIndex())
        assertNull(list.getSelectedValue())
    }

    private companion object {
        const val POINTER_DOWN = 0
        const val POINTER_MOVE = 1
        const val POINTER_UP = 2
        const val KEY_DOWN = 0
        const val CHAR_UNDEFINED = '￿'
    }
}

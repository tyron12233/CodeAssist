package dev.ide.awt

import dev.ide.awt.event.ActionEvent
import dev.ide.awt.event.ActionListener
import dev.ide.preview.PaintStyle
import dev.ide.swing.JButton
import dev.ide.swing.JFrame
import dev.ide.swing.JLabel
import dev.ide.swing.JPanel
import dev.ide.swing.SwingUtilities
import dev.ide.swing.ToolkitEventQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owned toolkit driven exactly as a program drives it, with a recording backend in place of a screen. No
 * display is involved anywhere, which is the point: unlike real Swing, this is testable in CI.
 */
class ToolkitTest {

    private val backend = RecordingBackend()

    @AfterTest fun tearDown() {
        ToolkitWindows.disposeAll()
        ToolkitEventQueue.clear()
    }

    private fun frame(title: String = "test", width: Int = 200, height: Int = 100): JFrame =
        JFrame(title).apply {
            attachBackend(backend)
            setSize(width, height)
        }

    // ---- layout ------------------------------------------------------------------------------------

    @Test fun borderLayoutGivesTheEdgesTheirPreferredSizeAndTheCentreTheRest() {
        val f = frame(width = 200, height = 100)
        val north = JPanel().apply { setPreferredSize(Dimension(0, 20)) }
        val south = JPanel().apply { setPreferredSize(Dimension(0, 30)) }
        val west = JPanel().apply { setPreferredSize(Dimension(40, 0)) }
        val centre = JPanel()
        f.add(north, BorderLayout.NORTH)
        f.add(south, BorderLayout.SOUTH)
        f.add(west, BorderLayout.WEST)
        f.add(centre, BorderLayout.CENTER)
        f.setVisible(true)
        f.validate()

        assertEquals(Rectangle(0, 0, 200, 20), north.getBounds(), "north spans the width at its own height")
        assertEquals(Rectangle(0, 70, 200, 30), south.getBounds(), "south sits against the bottom")
        assertEquals(Rectangle(0, 20, 40, 50), west.getBounds(), "west takes what north and south left")
        assertEquals(Rectangle(40, 20, 160, 50), centre.getBounds(), "centre takes everything remaining")
    }

    @Test fun addWithNoConstraintIsTheCentre() {
        val f = frame(width = 120, height = 60)
        val only = JPanel()
        f.add(only)
        f.setVisible(true)
        f.validate()

        assertEquals(Rectangle(0, 0, 120, 60), only.getBounds())
    }

    @Test fun flowLayoutWrapsWhenTheRowIsFull() {
        val f = frame(width = 100, height = 100)
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5))
        val a = JPanel().apply { setPreferredSize(Dimension(40, 10)) }
        val b = JPanel().apply { setPreferredSize(Dimension(40, 10)) }
        val c = JPanel().apply { setPreferredSize(Dimension(40, 10)) }
        listOf(a, b, c).forEach { panel.add(it) }
        f.add(panel, BorderLayout.CENTER)
        f.setVisible(true)
        f.validate()

        assertEquals(a.getY(), b.getY(), "the first two fit on one row")
        assertTrue(c.getY() > b.getY(), "the third wraps: ${c.getBounds()} after ${b.getBounds()}")
        assertEquals(a.getX(), c.getX(), "a wrapped row starts back at the left")
    }

    @Test fun gridLayoutSplitsTheContainerIntoEqualCells() {
        val f = frame(width = 100, height = 100)
        val panel = JPanel(GridLayout(2, 2))
        val cells = List(4) { JPanel() }
        cells.forEach { panel.add(it) }
        f.add(panel, BorderLayout.CENTER)
        f.setVisible(true)
        f.validate()

        assertEquals(Rectangle(0, 0, 50, 50), cells[0].getBounds())
        assertEquals(Rectangle(50, 0, 50, 50), cells[1].getBounds())
        assertEquals(Rectangle(0, 50, 50, 50), cells[2].getBounds())
        assertEquals(Rectangle(50, 50, 50, 50), cells[3].getBounds())
    }

    @Test fun packSizesTheFrameToItsContent() {
        val f = frame()
        val panel = JPanel().apply { setPreferredSize(Dimension(150, 75)) }
        f.add(panel, BorderLayout.CENTER)
        f.setVisible(true)
        f.pack()

        assertEquals(150, f.getWidth())
        assertEquals(75, f.getHeight())
    }

    // ---- painting ----------------------------------------------------------------------------------

    @Test fun aPanelPaintsItsBackgroundAndThenItsOwnDrawing() {
        val f = frame(width = 100, height = 50)
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                g.setColor(Color.RED)
                g.fillRect(10, 10, 20, 20)
            }
        }
        panel.setBackground(Color.BLUE)
        f.add(panel, BorderLayout.CENTER)
        f.setVisible(true)
        f.paintTo(backend)

        val fills = backend.opsOf("rect").filter { it.style == PaintStyle.FILL }
        assertTrue(fills.any { it.color == Color.BLUE.rgb }, "the background is painted: ${backend.ops}")
        val red = fills.single { it.color == Color.RED.rgb }
        assertEquals(10f, red.x)
        assertEquals(30f, red.right, "a 20-wide fill at x=10 ends at 30")
    }

    @Test fun childrenPaintInTheirParentsCoordinateSpace() {
        val f = frame(width = 100, height = 100)
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                g.setColor(Color.GREEN)
                g.fillRect(0, 0, 5, 5)
            }
        }
        panel.setOpaque(false)
        f.add(panel, BorderLayout.SOUTH)
        panel.setPreferredSize(Dimension(100, 20))
        f.setVisible(true)
        f.paintTo(backend)

        val green = backend.opsOf("rect").single { it.color == Color.GREEN.rgb }
        // The panel is the SOUTH child of a 100-tall frame at 20 tall, so its origin is y=80 in the window.
        assertEquals(80f, green.y, "a child's drawing is offset by its position")
    }

    @Test fun drawStringIsPositionedByItsBaseline() {
        val f = frame(width = 100, height = 40)
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                g.setFont(Font("SansSerif", Font.PLAIN, RecordingBackend.BASE_TEXT_SIZE.toInt()))
                g.drawString("hi", 0, 30)
            }
        }
        panel.setOpaque(false)
        f.add(panel, BorderLayout.CENTER)
        f.setVisible(true)
        f.paintTo(backend)

        val text = backend.opsOf("text").single()
        assertEquals("hi", text.text)
        // The canvas takes the top of the line, so the ascent comes off the AWT baseline.
        assertEquals(30f - RecordingBackend.ASCENT, text.y)
    }

    @Test fun anOvalGoesOutAsACircleWhenItIsRoundAndAPathWhenItIsNot() {
        val f = frame(width = 100, height = 100)
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                g.fillOval(0, 0, 20, 20)
                g.fillOval(0, 30, 40, 20)
            }
        }
        panel.setOpaque(false)
        f.add(panel, BorderLayout.CENTER)
        f.setVisible(true)
        f.paintTo(backend)

        assertEquals(1, backend.opsOf("circle").size, "a square oval is a circle")
        val path = backend.opsOf("path").single()
        assertTrue(path.text!!.startsWith("M "), "an elliptical oval becomes path data: ${path.text}")
        assertEquals(4, path.text.split("C").size - 1, "an ellipse is four cubic segments")
    }

    // ---- input -------------------------------------------------------------------------------------

    @Test fun aClickReachesTheButtonUnderIt() {
        val f = frame(width = 200, height = 100)
        var fired = 0
        val button = JButton("Click me")
        button.addActionListener { fired++ }
        f.add(button, BorderLayout.SOUTH)
        f.setVisible(true)
        f.validate()

        val bounds = button.getBounds()
        f.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)

        assertEquals(1, fired, "the listener fires once for one click on the button at $bounds")
    }

    @Test fun aClickThatMissesEveryButtonFiresNothing() {
        val f = frame(width = 200, height = 100)
        var fired = 0
        val button = JButton("Click me")
        button.addActionListener { fired++ }
        f.add(button, BorderLayout.SOUTH)
        f.setVisible(true)
        f.validate()

        f.click(5, 5)

        assertEquals(0, fired)
    }

    @Test fun theActionEventCarriesTheButtonAndItsCommand() {
        val button = JButton("Save")
        var event: ActionEvent? = null
        button.addActionListener(ActionListener { event = it })
        button.doClick()

        // `source` is the Kotlin view of the `getSource()` an interpreted program calls.
        assertEquals(button, event?.source)
        assertEquals("Save", event?.getActionCommand())
        assertEquals(ActionEvent.ACTION_PERFORMED, event?.getID())
    }

    @Test fun aDisabledButtonIgnoresClicks() {
        val button = JButton("Save")
        var fired = 0
        button.addActionListener { fired++ }
        button.setEnabled(false)
        button.doClick()

        assertEquals(0, fired)
    }

    // ---- window lifetime ---------------------------------------------------------------------------

    @Test fun aWindowIsDisplayableBetweenSetVisibleAndDispose() {
        val f = frame()
        assertFalse(f.isDisplayable(), "a frame that was never shown is not displayable")

        f.setVisible(true)
        assertTrue(f.isDisplayable())
        assertEquals(listOf<Window>(f), ToolkitWindows.displayable())

        f.dispose()
        assertFalse(f.isDisplayable())
        assertTrue(ToolkitWindows.displayable().isEmpty(), "a disposed window stops holding the run open")
    }

    @Test fun hidingAWindowIsNotDisposingIt() {
        val f = frame()
        f.setVisible(true)
        f.setVisible(false)

        assertTrue(f.isDisplayable(), "a hidden window can be shown again, so it stays displayable")
        assertEquals(1, ToolkitWindows.displayable().size)
    }

    @Test fun exitOnCloseDisposesRatherThanEndingTheProcess() {
        // The whole hazard under real Swing was that this reached System.exit inside java.desktop.
        val f = frame()
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
        f.setVisible(true)

        f.close()

        assertFalse(f.isDisplayable())
        assertTrue(ToolkitWindows.displayable().isEmpty())
    }

    @Test fun theDefaultCloseOperationDecidesWhatClosingDoes() {
        val hide = frame().apply { setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE); setVisible(true) }
        hide.close()
        assertTrue(hide.isDisplayable(), "HIDE_ON_CLOSE keeps the window alive")

        val nothing = frame().apply { setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); setVisible(true) }
        nothing.close()
        assertTrue(nothing.isVisible(), "DO_NOTHING_ON_CLOSE leaves it on screen")
    }

    // ---- the event queue ---------------------------------------------------------------------------

    @Test fun invokeLaterRunsOnTheNextDrain() {
        val order = ArrayList<String>()
        SwingUtilities.invokeLater { order.add("later") }
        order.add("now")

        assertEquals(listOf("now"), order, "posted work does not run inline")

        ToolkitEventQueue.drain()
        assertEquals(listOf("now", "later"), order)
    }

    @Test fun workPostedFromWorkWaitsForTheFollowingDrain() {
        val order = ArrayList<String>()
        SwingUtilities.invokeLater {
            order.add("first")
            SwingUtilities.invokeLater { order.add("second") }
        }

        ToolkitEventQueue.drain()
        assertEquals(listOf("first"), order, "a task that reposts cannot starve the drain it ran in")

        ToolkitEventQueue.drain()
        assertEquals(listOf("first", "second"), order)
    }

    // ---- measurement -------------------------------------------------------------------------------

    @Test fun aLabelSizesItselfToItsTextOnceItIsAttached() {
        val f = frame()
        val label = JLabel("abcd")
        label.setFont(Font("SansSerif", Font.PLAIN, RecordingBackend.BASE_TEXT_SIZE.toInt()))
        f.add(label, BorderLayout.CENTER)
        f.setVisible(true)

        val size = label.getPreferredSize()
        // Four characters at the backend's 6px grid, plus the label's 4px horizontal insets.
        assertEquals(4 * RecordingBackend.CHAR_WIDTH.toInt() + 8, size.width)
        assertEquals(RecordingBackend.LINE_HEIGHT.toInt() + 4, size.height)
    }

    @Test fun aDetachedComponentHasNoSizeYet() {
        // Nothing can measure text before a backend is attached, and reporting zero is what lets a program
        // build its UI before showing it.
        assertEquals(Dimension(0, 0), JLabel("abcd").getPreferredSize())
    }
}

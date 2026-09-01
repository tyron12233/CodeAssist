package dev.ide.awt

import dev.ide.swing.JButton
import dev.ide.swing.JDialog
import dev.ide.swing.JFrame
import dev.ide.swing.JOptionPane
import dev.ide.swing.JTextField
import dev.ide.swing.ToolkitEventQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The modal dialogs, including the part that makes them modal: `showConfirmDialog` does not return until the
 * dialog closes, yet the UI keeps running while it waits.
 *
 * The test plays the part the host normally plays. It installs a [ModalPump] that presses a button on the open
 * dialog, which is exactly what a user tapping the screen amounts to, so the nested loop is driven by the same
 * mechanism it is driven by in production rather than by a special path.
 */
class OptionPaneTest {

    private val backend = RecordingBackend()

    @AfterTest fun tearDown() {
        ToolkitWindows.installedPump = null
        ToolkitWindows.installedBackend = null
        ToolkitWindows.disposeAll()
        ToolkitEventQueue.clear()
    }

    /** Install the backend the way the host does, so every window a dialog opens later can measure and paint. */
    private fun frame(): JFrame {
        ToolkitWindows.installedBackend = backend
        return JFrame("host").apply {
            setSize(400, 300)
            setVisible(true)
        }
    }

    /** Drive the nested loop, pressing the dialog button labelled [label] on the [afterTurns]-th turn. */
    private fun pumpPressing(label: String, afterTurns: Int = 1): () -> Int {
        var turns = 0
        ToolkitWindows.installedPump = ModalPump {
            turns++
            if (turns >= afterTurns) openDialog()?.let { press(it, label) }
        }
        return { turns }
    }

    private fun openDialog(): JDialog? = ToolkitWindows.displayable().filterIsInstance<JDialog>().lastOrNull()

    /** Press the button with this text, wherever it sits in the dialog's tree. */
    private fun press(dialog: JDialog, label: String) {
        buttons(dialog.getContentPane()).firstOrNull { it.getText() == label }?.doClick()
    }

    private fun buttons(c: Container): List<JButton> = buildList {
        for (child in c.components()) {
            if (child is JButton) add(child)
            if (child is Container) addAll(buttons(child))
        }
    }

    private fun fields(c: Container): List<JTextField> = buildList {
        for (child in c.components()) {
            if (child is JTextField) add(child)
            if (child is Container) addAll(fields(child))
        }
    }

    @Test fun aMessageDialogBlocksUntilItIsDismissed() {
        val f = frame()
        val turns = pumpPressing("OK", afterTurns = 3)
        var returned = false

        JOptionPane.showMessageDialog(f, "Saved.")
        returned = true

        assertTrue(returned, "the call returned")
        assertTrue(turns() >= 3, "the UI kept running while it waited: ${turns()} turns")
        assertTrue(openDialog() == null, "the dialog closed")
        assertEquals(listOf<Window>(f), ToolkitWindows.displayable(), "only the frame is left")
    }

    @Test fun aConfirmDialogReturnsWhichButtonWasPressed() {
        val f = frame()
        pumpPressing("No")

        val answer = JOptionPane.showConfirmDialog(f, "Delete it?")

        assertEquals(JOptionPane.NO_OPTION, answer)
    }

    @Test fun anUnansweredConfirmDialogReportsThatItWasClosed() {
        val f = frame()
        // Dismiss it without touching a button, as the window's close would.
        ToolkitWindows.installedPump = ModalPump { openDialog()?.dispose() }

        val answer = JOptionPane.showConfirmDialog(f, "Delete it?")

        assertEquals(JOptionPane.CLOSED_OPTION, answer)
    }

    @Test fun anInputDialogReturnsWhatWasTypedAndNullWhenCancelled() {
        val f = frame()
        ToolkitWindows.installedPump = ModalPump {
            openDialog()?.let { dialog ->
                fields(dialog.getContentPane()).firstOrNull()?.setText("Ada")
                press(dialog, "OK")
            }
        }
        assertEquals("Ada", JOptionPane.showInputDialog(f, "Name?"))

        pumpPressing("Cancel")
        assertNull(JOptionPane.showInputDialog(f, "Name?"), "cancelling returns null, not an empty string")
    }

    @Test fun aModalDialogTakesTheInputFromTheWindowBehindIt() {
        val f = frame()
        var behind = 0
        val button = JButton("behind")
        button.addActionListener { behind++ }
        f.add(button, BorderLayout.SOUTH)
        f.validate()

        ToolkitWindows.installedPump = ModalPump {
            // While the dialog is up it is what `modal()` reports, which is what the host routes input to.
            assertTrue(ToolkitWindows.modal() is JDialog, "the dialog is the modal window")
            openDialog()?.let { press(it, "OK") }
        }
        JOptionPane.showMessageDialog(f, "hi")

        assertNull(ToolkitWindows.modal(), "nothing is modal once it closed")
        assertEquals(0, behind, "the window behind never saw a press")
    }

    @Test fun withNoPumpInstalledAModalDialogReturnsInsteadOfHanging() {
        // An embedder with no loop to lend cannot block; hanging the program forever would be far worse than
        // answering with the default.
        val f = frame()
        ToolkitWindows.installedPump = null

        val answer = JOptionPane.showConfirmDialog(f, "Delete it?")

        assertEquals(JOptionPane.CLOSED_OPTION, answer)
        assertFalse(openDialog() == null, "the dialog is still up; only the blocking was skipped")
    }

    @Test fun aDialogIsPaintedOverTheWindowThatOpenedIt() {
        val f = frame()
        ToolkitWindows.installedPump = ModalPump { openDialog()?.let { press(it, "OK") } }
        val dialog = JDialog(f, "over", modal = false)
        dialog.add(JButton("OK"), BorderLayout.CENTER)
        dialog.pack()
        dialog.centerOver(f)
        dialog.setVisible(true)

        assertTrue(dialog.getX() > 0 && dialog.getY() > 0, "it centred over the frame: ${dialog.getBounds()}")
        backend.clear()
        f.paintTo(backend)
        val frameOps = backend.ops.size
        dialog.paintTo(backend, dialog.getX(), dialog.getY())
        assertTrue(backend.ops.size > frameOps, "the dialog drew on top of what the frame drew")
    }
}

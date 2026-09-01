package dev.ide.build.jvm.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pieces that keep a GUI run alive for as long as its UI, and keep the program's window from taking the
 * IDE down with it. Everything here is deliberately checked without a display: the test JVMs run headless (a
 * real `JFrame` cannot be constructed), so the window TYPE checks use loaded classes and the tracker gets a
 * stand-in window through its test seam. The end-to-end behaviour on a real Swing program is covered by the
 * out-of-Gradle spike, which needs a display.
 */
class RunWindowLifecycleTest {

    // ---- which types count as a window -------------------------------------------------------------

    @Test fun swingAndAwtWindowsAreRecognisedByTheirSupertypes() {
        assertTrue(isAwtWindow(Class.forName("java.awt.Window")), "Window itself")
        assertTrue(isAwtWindow(Class.forName("java.awt.Frame")), "Frame extends Window")
        assertTrue(isAwtWindow(Class.forName("javax.swing.JFrame")), "JFrame extends Frame")
        assertTrue(isAwtWindow(Class.forName("javax.swing.JDialog")), "JDialog extends Dialog")
    }

    @Test fun nonWindowsAreNotRecognised() {
        assertFalse(isAwtWindow(String::class.java))
        // A component is not a top-level window: only windows keep AWT (and so the run) alive.
        assertFalse(isAwtWindow(Class.forName("javax.swing.JPanel")))
    }

    // ---- the tracker -------------------------------------------------------------------------------

    @Test fun anUndisposedWindowKeepsTheRunAlive() {
        val windows = ProgramWindows { it == StubWindow::class.java }
        val frame = StubWindow()
        windows.record(frame)

        assertEquals(1, windows.liveCount(), "a realized window counts")

        frame.dispose()
        assertEquals(0, windows.liveCount(), "a disposed window no longer counts")
    }

    @Test fun hidingAWindowIsNotClosingIt() {
        // AWT keeps its event thread alive for a displayable-but-hidden window, and so must the run: the
        // program can call setVisible(true) again.
        val windows = ProgramWindows { it == StubWindow::class.java }
        val frame = StubWindow().also { it.visible = false }
        windows.record(frame)

        assertEquals(1, windows.liveCount())
    }

    @Test fun nonWindowsAreNotTracked() {
        val windows = ProgramWindows { it == StubWindow::class.java }
        windows.record("a string")
        windows.record(null)

        assertEquals(0, windows.liveCount())
    }

    @Test fun disposeAllEndsEveryTrackedWindow() {
        val windows = ProgramWindows { it == StubWindow::class.java }
        val a = StubWindow()
        val b = StubWindow()
        windows.record(a)
        windows.record(b)

        windows.disposeAll()

        assertTrue(a.disposed && b.disposed, "Stop must dispose every window the program opened")
        assertEquals(0, windows.liveCount())
    }

    // ---- the EXIT_ON_CLOSE clamp -------------------------------------------------------------------

    @Test fun exitOnCloseIsClampedToDisposeOnAWindow() {
        // Swing calls System.exit for EXIT_ON_CLOSE from inside java.desktop, where the bridge's exit
        // interception cannot see it, so closing the program's window would kill the IDE process.
        val clamped = clampedCloseOperation(
            Class.forName("javax.swing.JFrame"), "setDefaultCloseOperation", "(I)V", listOf(EXIT_ON_CLOSE),
        )
        assertEquals(listOf(DISPOSE_ON_CLOSE), clamped)
    }

    @Test fun otherCloseOperationsPassThroughUntouched() {
        for (op in listOf(DO_NOTHING_ON_CLOSE, HIDE_ON_CLOSE, DISPOSE_ON_CLOSE)) {
            assertNull(
                clampedCloseOperation(Class.forName("javax.swing.JFrame"), "setDefaultCloseOperation", "(I)V", listOf(op)),
                "close operation $op must not be rewritten",
            )
        }
    }

    @Test fun theClampOnlyAppliesToWindows() {
        assertNull(
            clampedCloseOperation(StubWindow::class.java, "setDefaultCloseOperation", "(I)V", listOf(EXIT_ON_CLOSE)),
            "a same-named method on an unrelated class must not be rewritten",
        )
    }

    @Test fun theClampOnlyAppliesToThatExactCall() {
        val frame = Class.forName("javax.swing.JFrame")
        assertNull(clampedCloseOperation(frame, "setVisible", "(Z)V", listOf(1)))
        assertNull(clampedCloseOperation(frame, "setDefaultCloseOperation", "()V", emptyList()))
    }

    /** Stands in for a `java.awt.Window`, which a headless JVM cannot instantiate. Matched by name through
     *  [ProgramWindows]'s test seam, and reached through the same reflective calls a real window is. */
    private class StubWindow {
        @Volatile var disposed = false
        @Volatile var visible = true

        fun isDisplayable(): Boolean = !disposed
        fun isVisible(): Boolean = visible
        fun dispose() {
            disposed = true
        }
    }

    private companion object {
        const val DO_NOTHING_ON_CLOSE = 0
        const val HIDE_ON_CLOSE = 1
        const val DISPOSE_ON_CLOSE = 2
        const val EXIT_ON_CLOSE = 3
    }
}

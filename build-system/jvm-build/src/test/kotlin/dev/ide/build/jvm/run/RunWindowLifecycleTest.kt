package dev.ide.build.jvm.run

import dev.ide.build.ControlledExit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    // ---- Compose for Desktop's exit -----------------------------------------------------------------

    private val content: Any = Any() // stands in for the composable content lambda

    @Test fun composeApplicationNeverExitsTheProcess() {
        assertEquals(
            listOf(false, content),
            clampedComposeExit(APP_FACADE, "application", "(ZLkotlin/jvm/functions/Function3;)V", listOf(true, content)),
        )
    }

    @Test fun theDefaultBridgeHasTheParameterTakenOutOfItsDefaults() {
        // `application { }` compiles to `application$default(false, content, 0b1, null)`: bit 0 says "use the
        // default", which is `true`, so passing false alone would be ignored.
        assertEquals(
            listOf(false, content, 0, null),
            clampedComposeExit(APP_FACADE, "application\$default", "(ZLkotlin/jvm/functions/Function3;ILjava/lang/Object;)V", listOf(false, content, 1, null)),
        )
    }

    @Test fun theInterpretersIntBooleansAreClampedToo() {
        // What a run actually passes: the interpreter keeps a `boolean` as Int 0/1, as on the JVM stack.
        assertEquals(
            listOf(0, content, 0, null),
            clampedComposeExit(APP_FACADE, "application\$default", "(ZLkotlin/jvm/functions/Function3;ILjava/lang/Object;)V", listOf(0, content, 1, null)),
        )
        assertEquals(
            listOf(0, content),
            clampedComposeExit(APP_FACADE, "application", "(ZLkotlin/jvm/functions/Function3;)V", listOf(1, content)),
        )
    }

    @Test fun singleWindowApplicationIsClampedAtItsOwnPosition() {
        // 14 parameters; exitProcessOnExit is the 13th (index 12), just before the content lambda.
        val real = MutableList<Any?>(14) { null }.apply { this[1] = true; this[12] = true; this[13] = content }
        val mask = (1 shl 12) or (1 shl 2) // exitProcessOnExit and `title` both defaulted
        val out = assertNotNull(
            clampedComposeExit(WINDOW_FACADE, "singleWindowApplication\$default", SINGLE_WINDOW_DEFAULT, real + listOf(mask, null)),
        )
        assertEquals(false, out[12])
        assertEquals(1 shl 2, out[14], "only that parameter leaves the defaults")
        assertEquals(true, out[1], "nothing else is touched")
    }

    @Test fun otherComposeCallsPassThrough() {
        assertNull(clampedComposeExit(APP_FACADE, "awaitApplication", "(Lkotlin/jvm/functions/Function3;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", listOf(content, null)))
        assertNull(clampedComposeExit("androidx/compose/ui/window/Other_desktopKt", "application", "(ZLkotlin/jvm/functions/Function3;)V", listOf(true, content)))
    }

    @Test fun kotlinExitProcessEndsTheRunNotTheProcess() {
        val bridge = RunBridge(javaClass.classLoader)
        val exit = assertFailsWith<ControlledExit> {
            bridge.invokeStatic("kotlin/system/ProcessKt", "exitProcess", "(I)Ljava/lang/Void;", listOf(3))
        }
        assertEquals(3, exit.code)
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
        const val APP_FACADE = "androidx/compose/ui/window/Application_desktopKt"
        const val WINDOW_FACADE = "androidx/compose/ui/window/Window_desktopKt"
        const val SINGLE_WINDOW_DEFAULT =
            "(Landroidx/compose/ui/window/WindowState;ZLjava/lang/String;Landroidx/compose/ui/graphics/painter/Painter;" +
                "Landroidx/compose/ui/window/WindowDecoration;ZZZZZLkotlin/jvm/functions/Function1;" +
                "Lkotlin/jvm/functions/Function1;ZLkotlin/jvm/functions/Function3;ILjava/lang/Object;)V"
        const val DO_NOTHING_ON_CLOSE = 0
        const val HIDE_ON_CLOSE = 1
        const val DISPOSE_ON_CLOSE = 2
        const val EXIT_ON_CLOSE = 3
    }
}

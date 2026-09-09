package dev.ide.awt

import dev.ide.awt.interp.AwtNameRemapper
import dev.ide.jvm.AsmPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.ReflectiveBridge
import dev.ide.jvm.Vm
import dev.ide.jvm.interpretedMethods
import dev.ide.preview.PaintStyle
import dev.ide.swing.JButton
import dev.ide.swing.JFrame
import dev.ide.swing.ToolkitEventQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The architecture end to end: a real Swing program, compiled against the real java.awt/javax.swing, is
 * INTERPRETED by the bytecode VM with its AWT references remapped onto the owned toolkit, and the result is
 * laid out, painted, and clicked with no display anywhere.
 *
 * This is the bet the toolkit rests on. If remapping did not work the program would not resolve; if the peer
 * layer did not work its `paintComponent` override would never be called; if the lambda bridge did not work
 * the button would do nothing. Each of those is asserted below.
 */
class InterpretedSwingTest {

    private val backend = RecordingBackend()

    @AfterTest fun tearDown() {
        ToolkitWindows.disposeAll()
        ToolkitEventQueue.clear()
    }

    /**
     * A VM that interprets only the fixture package and bridges everything else, reading class bytes off the
     * test classpath through the remapper. Bridged therefore includes the toolkit itself, which is the
     * intended split: user code is interpreted, the toolkit runs as ordinary compiled code.
     */
    private fun vm(): Vm = Vm(
        source = ClassBytesSource { internalName ->
            javaClass.classLoader.getResourceAsStream("$internalName.class")?.use { it.readBytes() }
                ?.let { AwtNameRemapper.remap(it) }
        },
        policy = InterpretPolicy { it.startsWith(FIXTURES) },
        bridge = ReflectiveBridge(),
        peerFactory = AsmPeerFactory(),
    )

    /** The single window the fixture opened, as the frame it is. */
    private fun frame(): JFrame = ToolkitWindows.displayable().single() as JFrame

    private fun runMain(vm: Vm, fqn: String) {
        val main = vm.interpretedMethods(fqn).single { it.name == "main" && it.isStatic }
        main.invoke(null, listOf(emptyArray<String>()))
    }

    @Test fun anInterpretedSwingProgramBuildsARealToolkitWindow() {
        runMain(vm(), "$FIXTURE_PACKAGE.SwingFixture")

        val windows = ToolkitWindows.displayable()
        assertEquals(1, windows.size, "the program's setVisible(true) opened exactly one window")
        val frame = windows.single()
        assertEquals(240, frame.getWidth())
        assertEquals(160, frame.getHeight())
    }

    @Test fun theInterpretedPaintComponentOverrideDrawsThroughTheCanvas() {
        runMain(vm(), "$FIXTURE_PACKAGE.SwingFixture")
        val frame = frame()
        frame.attachBackend(backend)

        frame.paintTo(backend)

        // The override filled a 50x20 rect in its own colour at (10,10) inside the centre panel, which sits at
        // the top of the frame, and drew the click count under it.
        val blue = backend.opsOf("rect").single { it.color == Color(0x2D, 0x6C, 0xDF).rgb }
        assertEquals(PaintStyle.FILL, blue.style)
        assertEquals(10f, blue.x)
        assertEquals(60f, blue.right, "a 50-wide fill starting at x=10")
        assertTrue(backend.texts().any { it == "clicks: 0" }, "drew: ${backend.texts()}")
    }

    @Test fun aClickReachesTheInterpretedLambdaAndTheNextPaintShowsIt() {
        runMain(vm(), "$FIXTURE_PACKAGE.SwingFixture")
        val frame = frame()
        frame.attachBackend(backend)
        frame.paintTo(backend)
        assertTrue(backend.texts().any { it == "clicks: 0" }, "before: ${backend.texts()}")

        // The button is a plain toolkit JButton: the program never subclassed it, so it needed no peer.
        val button = frame.getContentPane().components().filterIsInstance<JButton>().single()
        val bounds = button.getBounds()
        frame.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)

        backend.clear()
        frame.paintTo(backend)
        assertTrue(
            backend.texts().any { it == "clicks: 1" },
            "the interpreted listener incremented the interpreted field: ${backend.texts()}",
        )
    }

    @Test fun theProgramsOwnClassIsInterpretedAndTheToolkitIsNot() {
        val vm = vm()
        runMain(vm, "$FIXTURE_PACKAGE.SwingFixture")

        assertTrue(vm.steps > 0, "the fixture ran as interpreted bytecode")
        // The panel the program built is a real toolkit object: a generated peer whose superclass is ours.
        val panel = frame().getContentPane().components().first { it !is JButton }
        assertEquals(
            "dev.ide.swing.JPanel",
            (panel as Any).javaClass.superclass.name,
            "the interpreted subclass reaches the toolkit as a peer of the owned JPanel",
        )
    }

    @Test fun exitOnCloseFromAnInterpretedProgramOnlyDisposesTheWindow() {
        runMain(vm(), "$FIXTURE_PACKAGE.SwingFixture")
        val frame = frame()
        assertEquals(JFrame.EXIT_ON_CLOSE, frame.getDefaultCloseOperation())

        frame.close()

        assertTrue(ToolkitWindows.displayable().isEmpty(), "closing ends the run instead of the process")
    }

    private companion object {
        const val FIXTURE_PACKAGE = "swingfixture"
        const val FIXTURES = "swingfixture/"
    }
}

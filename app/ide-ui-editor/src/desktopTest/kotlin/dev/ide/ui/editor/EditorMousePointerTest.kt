package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.platform.PointerSource
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A mouse on a touch platform, driven with synthetic events.
 *
 * On Android the editor pans with `scrollable2D`, which ignores wheel events, so the wheel did nothing until
 * the editor handled it itself. And the Android emulator (like some desktop-mode tablets) reports its mouse as
 * a stylus, so the editor took mouse clicks for finger taps unless it asks [PointerSource] where the event
 * came from. Both are exercised here with `twoAxisScroll = true`, the Android configuration.
 */
class EditorMousePointerTest {

    private val text = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    val message = \"Hello, \" + name + \"! This is a deliberately very long line of source code that runs well past the right edge of a narrow viewport by a very wide margin indeed.\"")
        repeat(60) { appendLine("    println(message) // line ${it + 2}") }
        appendLine("}")
    }

    @AfterTest
    fun reset() {
        PointerSource.mouseSourced = false
    }

    @Test
    fun `the wheel scrolls the two-axis editor vertically`() {
        // Three separate wheel clicks, three lines each.
        val line = topLineAfterWheel(notches = 3, gapMs = 150)
        assertTrue(line in 8..10, "three wheel clicks should scroll about nine lines; the top row is line $line")
    }

    @Test
    fun `a trackpad stream scrolls a line per notch`() {
        // What Android makes of a trackpad swipe: a notch every frame. Only the first counts as a wheel click.
        val line = topLineAfterWheel(notches = 10, gapMs = 16)
        assertTrue(line in 11..13, "ten streamed notches should scroll about twelve lines; the top row is line $line")
    }

    /** Scroll [notches] wheel notches [gapMs] apart, then click the top row; returns the line the caret lands on. */
    private fun topLineAfterWheel(notches: Int, gapMs: Long): Int = withEditor { scene, session ->
        scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 100f), type = PointerType.Mouse, timeMillis = 0)
        repeat(notches) {
            scene.sendPointerEvent(
                PointerEventType.Scroll, Offset(300f, 100f), type = PointerType.Mouse, scrollDelta = Offset(0f, 1f),
                timeMillis = 1_000 + it * gapMs,
            )
        }
        scene.render(4 * FRAME_NS)
        // A click on the top row lands on whatever line the wheel brought there.
        click(scene, Offset(300f, 4f), PointerType.Mouse, timeMillis = 10_000)
        scene.render(5 * FRAME_NS)
        session.doc.lineForOffset(session.selection.start)
    }

    @Test
    fun `shift and the wheel scroll horizontally`() {
        val h = withEditor { scene, session ->
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(
                PointerEventType.Scroll, Offset(300f, 100f), type = PointerType.Mouse, scrollDelta = Offset(0f, 2f),
                keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true),
            )
            scene.render(4 * FRAME_NS)
            session.hScrollOffsetPx
        }
        assertTrue(h > 0f, "Shift+wheel should scroll right; offset $h")
    }

    @Test
    fun `a stylus that comes from a mouse source drag-selects`() {
        PointerSource.mouseSourced = true
        val sel = dragAcross(PointerType.Stylus)
        assertTrue(!sel.collapsed, "a mouse drag selects text; selection $sel")
    }

    @Test
    fun `a real stylus still pans instead of selecting`() {
        PointerSource.mouseSourced = false
        val sel = dragAcross(PointerType.Stylus)
        assertTrue(sel.collapsed, "a stylus drag is a pan, as with a finger; selection $sel")
    }

    @Test
    fun `a mouse-sourced click places the caret on press`() {
        PointerSource.mouseSourced = true
        val caret = withEditor { scene, session ->
            // Press only: a mouse places the caret on the way down, a finger only once it lifts.
            scene.sendPointerEvent(
                PointerEventType.Press, Offset(300f, 4f), type = PointerType.Stylus,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
            )
            scene.render(4 * FRAME_NS)
            session.selection.start
        }
        assertTrue(caret > 0, "the press should already have moved the caret; caret $caret")
    }

    /** Press on line 1 and drag down three rows; returns the selection afterwards. */
    private fun dragAcross(type: PointerType): TextRange = withEditor { scene, session ->
        var at = Offset(300f, 4f)
        var stamp = 0L
        scene.sendPointerEvent(
            PointerEventType.Press, at, type = type, timeMillis = stamp,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
        )
        repeat(6) {
            stamp += 16
            at += Offset(10f, 12f)
            scene.sendPointerEvent(
                PointerEventType.Move, at, type = type, timeMillis = stamp,
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            scene.render((3 + it) * FRAME_NS)
        }
        scene.sendPointerEvent(
            PointerEventType.Release, at, type = type, timeMillis = stamp + 16,
            buttons = PointerButtons(), button = PointerButton.Primary,
        )
        scene.render(20 * FRAME_NS)
        session.selection
    }

    private fun click(scene: ImageComposeScene, at: Offset, type: PointerType, timeMillis: Long) {
        scene.sendPointerEvent(
            PointerEventType.Press, at, type = type, timeMillis = timeMillis,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
        )
        scene.sendPointerEvent(
            PointerEventType.Release, at, type = type, timeMillis = timeMillis + 50,
            buttons = PointerButtons(), button = PointerButton.Primary,
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> withEditor(block: (ImageComposeScene, EditorSession) -> T): T {
        val session = EditorSession(text, languageFor("Sample.kt"), TextRange(0))
        val scene = ImageComposeScene(width = 760, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Sample.kt", session, PreviewBackend, Modifier.fillMaxSize(), twoAxisScroll = true)
                }
            }
        }
        return try {
            scene.render()
            scene.render(FRAME_NS)
            scene.render(2 * FRAME_NS)
            block(scene, session)
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val FRAME_NS = 16_000_000L
    }
}

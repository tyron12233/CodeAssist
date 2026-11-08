package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Touch gestures over the real [CodeEditor], driven through an off-screen scene: a swipe pans the document, a
 * still finger long-presses, and — the regression these guard — a swipe that follows a long press still pans.
 *
 * The editor detects the long press itself rather than through `detectTapGestures`, whose own long press
 * consumes every event until the finger lifts: that swallowed the swipe of anyone who rested a moment before
 * dragging, so the gesture scrolled nothing until they lifted and started over.
 */
class EditorTouchScrollTest {

    private val longLine = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    val message = \"Hello, \" + name + \"! This is a deliberately very long line of source code that runs well past the right edge of a narrow viewport by a wide margin indeed.\"")
        appendLine("    println(message)")
        appendLine("}")
    }

    @Test
    fun `a swipe pans the document`() {
        val session = session()
        drive(session, swipeSteps = 3)
        assertTrue(session.hScrollOffsetPx > 0f, "the swipe scrolled nothing (offset ${session.hScrollOffsetPx})")
    }

    @Test
    fun `a still finger long-presses`() {
        val session = session()
        drive(session, restMs = LONG_REST_MS)
        assertTrue(!session.selection.collapsed, "a long press should have selected the word under the finger")
    }

    @Test
    fun `a swipe that follows a lingering press pans and leaves the document alone`() {
        val session = session()
        drive(session, restMs = LONG_REST_MS, swipeSteps = 3)
        assertTrue(
            session.hScrollOffsetPx > 0f,
            "the swipe after the long press scrolled nothing (offset ${session.hScrollOffsetPx})",
        )
        assertTrue(
            session.selection.collapsed,
            "panning abandons the long press, so its word selection must be rolled back (was ${session.selection})",
        )
    }

    @Test
    fun `a hand that drifts a little after a long press keeps the word selected`() {
        val session = session()
        // 36px at density 2 = 18dp: past touch slop, so the scroll container takes the move, but well inside
        // the drift a held finger is allowed before the long press counts as a pan.
        drive(session, restMs = LONG_REST_MS, swipeSteps = 2, stepPx = 18f)
        assertTrue(
            !session.selection.collapsed,
            "a few dp of shake after the long press must not throw its word selection away (was ${session.selection})",
        )
    }

    private fun session() = EditorSession(longLine, languageFor("Sample.kt"), TextRange(0))

    /**
     * Press inside the long line, optionally rest for [restMs], then drag left in [swipeSteps] steps of [stepPx]
     * and lift.
     * Frames are pumped throughout, since the long-press timer resolves on the scene's dispatcher.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun drive(session: EditorSession, restMs: Long = 0, swipeSteps: Int = 0, stepPx: Float = 40f) {
        val scene = ImageComposeScene(width = 760, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content(session) }
        }
        try {
            scene.render()
            scene.render(FRAME_NS)
            var frame = 2 * FRAME_NS
            fun pump(millis: Long) {
                var slept = 0L
                while (slept < millis) {
                    Thread.sleep(FRAME_MS)
                    slept += FRAME_MS
                    scene.render(frame)
                    frame += FRAME_NS
                }
            }

            var x = 400f
            val y = 60f // inside the long second line, clear of the horizontal scrollbar strip
            var stamp = 0L
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
            if (restMs > 0) {
                pump(restMs)
                stamp += restMs
            }
            repeat(swipeSteps) {
                stamp += FRAME_MS
                x -= stepPx
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
                pump(FRAME_MS)
            }
            stamp += FRAME_MS
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
            pump(3 * FRAME_MS)
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun content(session: EditorSession) {
        Box(Modifier.width(360.dp).fillMaxSize().background(Ide.colors.editorBg)) {
            CodeEditor("Sample.kt", remember { session }, PreviewBackend, Modifier.fillMaxSize())
        }
    }

    private companion object {
        /** Comfortably past any platform's long-press timeout, so the rest really does trigger one. */
        const val LONG_REST_MS = 900L
        const val FRAME_MS = 50L
        const val FRAME_NS = 16_000_000L
    }
}

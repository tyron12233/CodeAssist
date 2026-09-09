package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.ide.ui.components.PushDrawer
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two-axis (free) touch panning inside the phone layout's [PushDrawer], driven with synthetic touch events.
 *
 * The drawer's drag-to-close is a horizontal `draggable` wrapping the editor. Compose asks a child whether it
 * is interested in a drag before letting it consume one, and answers that question for an ancestor `draggable`
 * from the delta's angle ALONE — `enabled` is not consulted for moves — while a child `scrollable2D` never
 * claims interest, because the angle test returns false for an unlocked orientation. A drag-to-close left
 * attached-but-disabled therefore stalled every horizontal pan in the editor: deferred event after event, then
 * delivered in one jump when the stroke stopped reading as horizontal. Hence the drawer attaches that modifier
 * only while it has something to close, and hence this test.
 */
class EditorTwoAxisScrollTest {

    private val longLine = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    val message = \"Hello, \" + name + \"! This is a deliberately very long line of source code that runs well past the right edge of a narrow viewport by a very wide margin indeed.\"")
        appendLine("    println(message)")
        repeat(40) { appendLine("    // filler so the document scrolls vertically too") }
        appendLine("}")
    }

    @Test
    fun `two-axis panning tracks the finger inside a closed push drawer`() {
        val offsets = pan(twoAxis = true)
        // Every step of the stroke must move the document: the failure this guards reported 0 throughout.
        assertTrue(offsets.first() > 0f, "the pan never started (offsets $offsets)")
        assertTrue(
            offsets.zipWithNext().all { (a, b) -> b > a },
            "the pan stalled instead of following the finger (offsets $offsets)",
        )
    }

    @Test
    fun `orientation-locked panning is unaffected`() {
        val offsets = pan(twoAxis = false)
        assertTrue(offsets.first() > 0f, "the pan never started (offsets $offsets)")
        assertTrue(offsets.zipWithNext().all { (a, b) -> b > a }, "the pan stalled (offsets $offsets)")
    }

    /** Swipe left across the editor in even steps; returns the horizontal scroll offset after each step. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun pan(twoAxis: Boolean): List<Float> {
        val session = EditorSession(longLine, languageFor("Sample.kt"), TextRange(0))
        val offsets = mutableListOf<Float>()
        val scene = ImageComposeScene(width = 760, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content(session, twoAxis) }
        }
        try {
            scene.render()
            scene.render(FRAME_NS)
            var frame = 2 * FRAME_NS
            var x = 500f
            var y = 200f
            var stamp = 0L
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
            repeat(8) {
                stamp += 16
                x -= 30f
                y += 2f // a real finger arcs a little; the pan must not need a perfectly straight line
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
                scene.render(frame); frame += FRAME_NS
                offsets += session.hScrollOffsetPx
            }
            scene.sendPointerEvent(
                PointerEventType.Release, Offset(x, y), type = PointerType.Touch, timeMillis = stamp + 16,
            )
            scene.render(frame)
        } finally {
            scene.close()
        }
        return offsets
    }

    @Composable
    private fun content(session: EditorSession, twoAxis: Boolean) {
        PushDrawer(open = false, onOpenChange = {}, drawerContent = {}) {
            Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                CodeEditor(
                    "Sample.kt",
                    remember { session },
                    PreviewBackend,
                    Modifier.fillMaxSize(),
                    twoAxisScroll = twoAxis,
                )
            }
        }
    }

    private companion object {
        const val FRAME_NS = 16_000_000L
    }
}

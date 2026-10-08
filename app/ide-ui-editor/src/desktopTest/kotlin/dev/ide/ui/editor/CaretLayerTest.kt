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
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The caret is drawn in a layer of its own over the editor (see [drawCaret]). These render the real [CodeEditor]
 * off screen, place the caret with a click, and compare a frame with the caret shown against one taken half a
 * blink later: the only pixels that differ must be the caret's own thin bar.
 */
class CaretLayerTest {

    private val text = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    println(name)")
        appendLine("}")
    }

    @Test
    fun `a blink changes only the caret's pixels`() {
        val session = EditorSession(text, languageFor("Sample.kt"), TextRange(0))
        val (shown, hidden) = renderAcrossBlink(session)
        val diff = diffBounds(shown, hidden)
        assertTrue(diff != null, "the caret never blinked: the frames with and without it are identical")
        val (width, height) = diff
        assertTrue(width in 1..8, "a blink changed a ${width}px-wide region; only the 2dp caret should change")
        assertTrue(height >= 10, "a blink changed a ${height}px-tall region; the caret spans a whole line")
    }

    /** Clicks into the first line, then renders one frame while the caret is solid and one half a blink later. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderAcrossBlink(session: EditorSession): Pair<Image, Image> {
        val scene = ImageComposeScene(width = 760, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content(session) }
        }
        try {
            var frame = 0L
            fun pump(millis: Long) {
                var slept = 0L
                while (slept < millis) {
                    Thread.sleep(FRAME_MS)
                    slept += FRAME_MS
                    scene.render(frame)
                    frame += FRAME_NS
                }
            }
            pump(3 * FRAME_MS)
            val at = Offset(600f, 20f) // past the end of the first line, so the caret lands at its end
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse)
            pump(150)
            val shown = scene.render(frame)
            // The caret hides 530 ms after it last moved and shows again 530 ms after that.
            pump(650)
            val hidden = scene.render(frame)
            return shown to hidden
        } finally {
            scene.close()
        }
    }

    /** The width and height of the box holding every pixel that differs between [a] and [b], or null. */
    private fun diffBounds(a: Image, b: Image): Pair<Int, Int>? {
        val pa = org.jetbrains.skia.Bitmap.makeFromImage(a)
        val pb = org.jetbrains.skia.Bitmap.makeFromImage(b)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (pa.getColor(x, y) != pb.getColor(x, y)) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else (maxX - minX + 1) to (maxY - minY + 1)
    }

    @Composable
    private fun content(session: EditorSession) {
        Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
            CodeEditor("Sample.kt", remember { session }, PreviewBackend, Modifier.fillMaxSize())
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        const val FRAME_NS = FRAME_MS * 1_000_000
    }
}

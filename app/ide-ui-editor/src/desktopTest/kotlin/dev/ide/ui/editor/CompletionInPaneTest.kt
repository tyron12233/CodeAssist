package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A backend whose completion always offers `println` / `print` for the token before the caret. */
private object CompletingBackend : IdeBackend by PreviewBackend {
    override val editor: EditorService = object : EditorService by PreviewBackend {
        override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult {
            var start = offset
            while (start > 0 && text[start - 1].isLetterOrDigit()) start--
            val items = listOf("println", "print").map {
                UiCompletionItem(it, it, "(message: Any?): Unit", "kotlin.io", null, UiCompletionKind.Method, 0)
            }
            return UiCompletionResult(items, start, offset)
        }
    }
}

/**
 * The completion list is drawn in the editor pane, not in a popup window (a popup re-reads its screen position
 * every frame while it shows). These pin that it still opens under the token and takes a tap on a row.
 */
class CompletionInPaneTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theListOpensUnderTheTokenAndATapAcceptsARow() {
        val text = "fun main() {\n    \n}\n"
        val caret = text.indexOf("    \n") + 4
        val session = EditorSession(text, languageFor("Main.kt"), TextRange(caret))
        val scene = ImageComposeScene(width = 1000, height = 700, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Main.kt", session, CompletingBackend, Modifier.fillMaxSize())
                }
            }
        }
        var t = 0L
        fun pump(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
        }
        try {
            pump(200)
            session.commitText("p") // an identifier character: the editor asks for completion after its delay
            pump(800)
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/completion-in-pane.png")
                .apply { parentFile?.mkdirs() }
                .writeBytes(scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)

            // Line 1 (0-based) holds the token; the first row sits just under it (6dp gap, ~20dp to its middle).
            val lineBottomPx = TOP_PX + 2 * LINE_PX
            val firstRow = Offset(GUTTER_PX + 6 * CHAR_PX, lineBottomPx + 12f + 40f)
            scene.sendPointerEvent(PointerEventType.Press, firstRow, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, firstRow, buttons = PointerButtons())
            pump(300)

            val line = session.doc.text.lines()[1]
            assertEquals("    println", line.trimEnd(), "tapping the first row accepts it, saw '$line'")
        } finally {
            scene.close()
        }
    }

    @Test
    fun placementGoesBelowTheLineAndFlipsAboveNearThePaneBottom() {
        // lineTop 100, line 30 high, gap 6: below starts at 136.
        assertEquals(IntOffset(200, 136), belowLinePlacement(200f, 100f, 30f, 6, 8, 40, 300, 200, 1000, 700))
        // Never left of the gutter, and kept a margin inside the pane's right edge.
        assertEquals(40, belowLinePlacement(10f, 100f, 30f, 6, 8, 40, 300, 200, 1000, 700).x)
        assertEquals(1000 - 300 - 8, belowLinePlacement(900f, 100f, 30f, 6, 8, 40, 300, 200, 1000, 700).x)
        // Near the bottom with more room above: the list goes above the line (ending a gap above its top).
        val flipped = belowLinePlacement(200f, 600f, 30f, 6, 8, 40, 300, 200, 1000, 700)
        assertEquals(600 - 6 - 200, flipped.y)
        // No room either way: below, clamped inside the pane.
        val tight = belowLinePlacement(200f, 50f, 30f, 6, 8, 40, 300, 690, 1000, 700)
        assertTrue(tight.y in 0..10, "kept inside the pane: ${tight.y}")
    }

    private companion object {
        // Same metrics as EditorMouseSnapshot (density 2).
        const val GUTTER_PX = 135f
        const val CHAR_PX = 16.2f
        const val TOP_PX = 12f
        const val LINE_PX = 32.5f
    }
}

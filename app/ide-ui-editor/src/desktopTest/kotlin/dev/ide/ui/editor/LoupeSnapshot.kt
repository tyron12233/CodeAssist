package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The selection loupe, driven by touch: a long press selects a word, then its end handle is dragged right and the
 * loupe shows the edge above the finger. Writes PNGs; asserts the drag moved the selection.
 */
class LoupeSnapshot {
    private val sample = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    val message = \"Hello, \" + name")
        appendLine("    println(message)")
        appendLine("}")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun loupeFollowsADraggedHandle() {
        val session = EditorSession(sample, languageFor("Sample.kt"), TextRange(0))
        val scene = ImageComposeScene(width = 822, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    // Stands in for the breadcrumb row the loupe may rise over.
                    Box(Modifier.fillMaxSize().weight(0.15f))
                    CodeEditor("Sample.kt", session, PreviewBackend, Modifier.fillMaxSize().weight(0.85f))
                }
            }
        }
        var t = 0L
        fun pump(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
        }
        fun shot(name: String) {
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        }
        fun at(line: Int, col: Float) = Offset(GUTTER_PX + col * CHAR_PX, STRIP_PX + TOP_PX + line * LINE_PX + LINE_PX / 2)
        fun touch(type: PointerEventType, p: Offset) = scene.sendPointerEvent(type, p, type = PointerType.Touch)
        try {
            pump(200)
            // Long press on `message` (line 1) selects it and raises the handles.
            val word = at(1, 10.5f)
            touch(PointerEventType.Press, word); pump(800); touch(PointerEventType.Release, word); pump(300)
            val start = sample.indexOf("message")
            assertEquals(TextRange(start, start + "message".length), session.selection, "the long press selected the word")

            // Grab the end handle, which hangs below the line at the selection's end, and drag it right.
            val handle = Offset(GUTTER_PX + 15f * CHAR_PX, STRIP_PX + TOP_PX + 2 * LINE_PX + 17f)
            touch(PointerEventType.Press, handle); pump(48)
            for (i in 1..8) { touch(PointerEventType.Move, handle + Offset(i * 12f, 0f)); pump(20) }
            pump(250)
            shot("loupe-1-dragging.png")
            touch(PointerEventType.Release, handle + Offset(96f, 0f)); pump(300)
            shot("loupe-2-released.png")
            assertTrue(session.selection.max > start + "message".length, "the drag extended the selection: ${session.selection}")
        } finally {
            scene.close()
        }
    }

    private companion object {
        // Measured off the editor at density 2 (same metrics as EditorMouseSnapshot).
        const val GUTTER_PX = 135f
        const val CHAR_PX = 16.2f
        const val TOP_PX = 12f
        const val LINE_PX = 32.5f
        const val STRIP_PX = 60f // 15% of the 400px scene
    }
}

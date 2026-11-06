package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen renders of the trackpad's floating cursor: past a short line's end, and between two lines. PNGs. */
class FloatingCaretSnapshot {
    private val sample = "fun main() {\n    val name = \"world\"\n    println(name)\n}\n"

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun floatingCaret() {
        val session = EditorSession(sample, languageFor("Main.kt"), TextRange(sample.indexOf("println")))
        val scene = ImageComposeScene(width = 822, height = 300, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Main.kt", session, PreviewBackend, Modifier.fillMaxSize())
                }
            }
        }
        var t = 0L
        fun shot(name: String) {
            repeat(30) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        }
        try {
            session.beginFloatingCaret()
            session.moveFloatingCaret(14f, 0f) // past the end of `println(name)`
            shot("floating-1-past-line-end.png")
            session.moveFloatingCaret(-6f, -0.45f) // up, nearly between two lines
            shot("floating-2-between-lines.png")
        } finally {
            scene.close()
        }
    }

    /** Floated far past a short line's end, beside a long one: the view scrolls to the cursor, not the caret. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theViewFollowsTheFloatingCursorNotTheCaret() {
        val text = "val a = 1\nval message = \"a line long enough to scroll sideways on a phone\" + suffix\nval b = 2\n"
        val session = EditorSession(text, languageFor("Main.kt"), TextRange(text.indexOf("1")))
        val scene = ImageComposeScene(width = 822, height = 240, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Main.kt", session, PreviewBackend, Modifier.fillMaxSize())
                }
            }
        }
        var t = 0L
        try {
            repeat(10) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
            session.beginFloatingCaret()
            session.moveFloatingCaret(70f, 0f)
            repeat(30) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/floating-3-scrolls.png").apply { parentFile?.mkdirs() }.writeBytes(png)
            kotlin.test.assertEquals(text.indexOf("1") + 1, session.selection.start, "the caret waits at the end of `val a = 1`")
        } finally {
            scene.close()
        }
    }
}

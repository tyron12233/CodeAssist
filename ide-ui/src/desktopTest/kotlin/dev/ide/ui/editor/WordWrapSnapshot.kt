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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNGs of the real [CodeEditor] at a narrow width with word wrap OFF vs ON, so the wrapped
 *  layout (gutter alignment, current-line band on the caret's sub-row, selection across rows) can be
 *  eyeballed without launching the app. Not assertions — visual snapshots dropped at [OUT_DIR]. */
class WordWrapSnapshot {
    private val sample = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    val message = \"Hello, \" + name + \"! This is a deliberately very long line of source code that should soft-wrap onto several visual rows when word wrap is enabled at a narrow viewport width.\"")
        appendLine("    println(message)")
        appendLine("    repeat(3) { index -> println(\"line \" + index + \" — another fairly long statement so we can see how the gutter line numbers stay aligned with the first wrapped row only\") }")
        appendLine("}")
    }

    // caret + selection inside the long second line so the current-line band and selection span wrapped rows.
    private fun session(): EditorSession {
        val s = EditorSession(sample, languageFor("Sample.kt"), TextRange(60, 120))
        return s
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderWrapOnVsOff() {
        snapshot("wrap-off.png", 760, 520) {
            Box(Modifier.width(360.dp).fillMaxSize().background(Ca.colors.editorBg)) {
                CodeEditor("Sample.kt", remember { session() }, PreviewBackend, Modifier.fillMaxSize(), wordWrap = false)
            }
        }
        snapshot("wrap-on.png", 760, 520) {
            Box(Modifier.width(360.dp).fillMaxSize().background(Ca.colors.editorBg)) {
                CodeEditor("Sample.kt", remember { session() }, PreviewBackend, Modifier.fillMaxSize(), wordWrap = true, wrapIndent = false)
            }
        }
        snapshot("wrap-indent.png", 760, 520) {
            Box(Modifier.width(360.dp).fillMaxSize().background(Ca.colors.editorBg)) {
                CodeEditor("Sample.kt", remember { session() }, PreviewBackend, Modifier.fillMaxSize(), wordWrap = true, wrapIndent = true)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            scene.render()
            scene.render(16_000_000L)
            val img = scene.render(32_000_000L) // settle layout (viewport size → wrap width) + recomposition
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = "$OUT_DIR/$name"
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $out (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/8b7a0e57-9bee-4671-94e9-9bfc2f97e5ba/scratchpad"
    }
}

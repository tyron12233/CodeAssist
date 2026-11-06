package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiBlameLine
import dev.ide.ui.backend.UiVcsStatus
import dev.ide.ui.backend.VcsService
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Inline blame on an unchanged line (its author) and on a changed one ("Not committed yet"). PNGs for review. */
@OptIn(ExperimentalComposeUiApi::class)
class InlineBlameSnapshot {
    private val committed = "fun main() {\n    val a = 1\n    val b = 2\n    println(a + b)\n}\n"
    private val edited = "fun main() {\n    val a = 10\n    val b = 2\n    println(a + b)\n}\n"
    private val ana = UiBlameLine("Ana Lima", "3 days ago", "Add the sum example", "a1b2c3d")
    private val ben = UiBlameLine("Ben Ito", "2 weeks ago", "Start the project", "e4f5a6b")

    private val backend = object : IdeBackend by PreviewBackend {
        override val vcs: VcsService = object : VcsService {
            override val status: StateFlow<UiVcsStatus> = MutableStateFlow(UiVcsStatus(present = true))
            override suspend fun baseText(path: String) = committed
            override suspend fun blame(path: String) = listOf(ben, ana, ana, ana, ben, null)
        }
    }

    private fun shot(name: String, caret: Int) {
        val session = EditorSession(edited, languageFor("Main.kt"), TextRange(caret))
        val scene = ImageComposeScene(width = 1100, height = 360, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Main.kt", session, backend, Modifier.fillMaxSize())
                }
            }
        }
        try {
            var t = 0L
            val end = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < end) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }

    @Test
    fun blameOnCommittedAndChangedLines() {
        shot("blame-1-committed.png", edited.indexOf("val b"))
        shot("blame-2-changed.png", edited.indexOf("val a"))
    }
}

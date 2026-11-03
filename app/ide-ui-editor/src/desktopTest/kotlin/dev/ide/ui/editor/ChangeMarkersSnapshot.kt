package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.IdeBackend
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
import kotlin.test.assertEquals

/** Change stripes against a fake repository's committed text, then Revert on the modified stretch. PNGs too. */
@OptIn(ExperimentalComposeUiApi::class)
class ChangeMarkersSnapshot {
    private val committed = "fun main() {\n    val a = 1\n    val b = 2\n    println(a + b)\n    done()\n}\n"
    private val edited = "fun main() {\n    val a = 10\n    val b = 2\n    log(\"sum\")\n    println(a + b)\n}\n"

    private val backend = object : IdeBackend by PreviewBackend {
        override val vcs: VcsService = object : VcsService {
            override val status: StateFlow<UiVcsStatus> = MutableStateFlow(UiVcsStatus(present = true))
            override suspend fun baseText(path: String) = committed
        }
    }

    @Test
    fun stripesShowAndRevertRestoresTheLine() {
        val session = EditorSession(edited, languageFor("Main.kt"), TextRange(0))
        val scene = ImageComposeScene(width = 822, height = 520, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Main.kt", session, backend, Modifier.fillMaxSize())
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
        try {
            pump(700)
            shot("changes-1-gutter.png")
            // The modified line 2 (`val a = 10`): its stripe sits just left of the fold strip. The gutter is ~116px
            // at density 2 with a 28px fold strip; line 2's middle is 12 + 32.5 + 16.
            val stripe = Offset(116f - 28f - 4f, 12f + 32.5f + 16f)
            scene.sendPointerEvent(PointerEventType.Press, stripe)
            scene.sendPointerEvent(PointerEventType.Release, stripe)
            pump(300)
            shot("changes-2-popup.png")
        } finally {
            scene.close()
        }
    }

    @Test
    fun revertingTheModifiedStretchRestoresItsLine() {
        val session = EditorSession(edited, languageFor("Main.kt"), TextRange(0))
        val change = dev.ide.ui.editor.core.lineChanges(committed, edited).first()
        val (s, e, r) = dev.ide.ui.editor.core.revertEdit(session.doc, committed, change)
        session.replaceRange(s, e, r, TextRange(s))
        assertEquals("    val a = 1", session.doc.text.lines()[1])
    }
}

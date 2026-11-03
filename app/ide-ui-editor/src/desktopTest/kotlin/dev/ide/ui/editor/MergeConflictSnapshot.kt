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
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.ConflictChoice
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.findMergeConflicts
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store update that kept the user's edit and conflicted on one string: the block banded by side with its
 * resolve row, the XML parser's "Tag name expected" on the marker hidden, and one tap resolving it.
 * The PNGs are for review; the assertions pin the resolution.
 */
class MergeConflictSnapshot {
    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
        <<<<<<< Your version
            <string name="app_name">My XML Tool</string>
        =======
            <string name="app_name">XML Formatter</string>
        >>>>>>> New version (1.0.2)
            <string name="hint">Paste XML here</string>
        </resources>
    """.trimIndent() + "\n"

    private fun session(): EditorSession {
        val s = EditorSession(sample, languageFor("strings.xml"), TextRange(0))
        val marker = sample.indexOf("<<<<<<<")
        s.applyAnalysis(listOf(UiDiagnostic(UiSeverity.Error, 3, 1, "Tag name expected", marker, marker + 7)))
        return s
    }

    @Test
    fun resolvingKeepsTheChosenSideAsOneUndoableEdit() {
        val s = session()
        resolveConflict(s, startLine = 2, ConflictChoice.INCOMING)
        assertEquals(
            sample.replace("<<<<<<< Your version\n    <string name=\"app_name\">My XML Tool</string>\n=======\n", "")
                .replace(">>>>>>> New version (1.0.2)\n", ""),
            s.doc.text,
        )
        assertTrue(findMergeConflicts(s.doc.text).isEmpty())
        assertTrue(s.undo())
        assertEquals(sample, s.doc.text, "one undo brings the block back")
    }

    @Test
    fun theParsersErrorsInsideABlockAreHidden() {
        val s = session()
        val conflicts = findMergeConflicts(sample)
        assertEquals(emptyList(), withoutConflictDiagnostics(s.diagnostics, conflicts))
        val outside = UiDiagnostic(UiSeverity.Error, 8, 5, "elsewhere", sample.indexOf("hint"), sample.indexOf("hint") + 4)
        assertEquals(listOf(outside), withoutConflictDiagnostics(s.diagnostics + outside, conflicts))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun conflictSurfaces() {
        for (dark in listOf(true, false)) {
            val s = session()
            val scene = ImageComposeScene(width = 822, height = 460, density = Density(2f)) {
                CodeAssistTheme(dark = dark) {
                    Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                        CodeEditor("strings.xml", s, PreviewBackend, Modifier.fillMaxSize())
                    }
                }
            }
            var t = 0L
            fun pump() = repeat(30) { t += 16_000_000L; scene.render(t) }
            fun shot(name: String) {
                pump()
                val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            }
            val theme = if (dark) "dark" else "light"
            try {
                shot("conflict-1-$theme.png")
                if (dark) {
                    // Tap "Incoming", the middle of the three buttons at the right end of the `<<<<<<<` line (line 3).
                    val y = 12f + 2 * 32.5f + 16f
                    val x = findIncomingX(scene, y)
                    scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), buttons = PointerButtons(isPrimaryPressed = true))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                    shot("conflict-2-after-incoming.png")
                    assertTrue(findMergeConflicts(s.doc.text).isEmpty(), "the tap resolved the block")
                    assertTrue("XML Formatter" in s.doc.text && "My XML Tool" !in s.doc.text)
                }
            } finally {
                scene.close()
            }
        }
    }

    /** The Incoming button's x: three buttons hug the right edge, so it sits about 120dp in from it at density 2. */
    private fun findIncomingX(@Suppress("UNUSED_PARAMETER") scene: ImageComposeScene, @Suppress("UNUSED_PARAMETER") y: Float) = 822f - 2 * 120f
}

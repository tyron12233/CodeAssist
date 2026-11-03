package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Three-finger undo/redo, driven by real multi-touch events against the editor. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class UndoGestureTest {

    private fun session(): EditorSession {
        val s = EditorSession("fun main() {\n}\n", languageFor("Main.kt"), TextRange(12))
        // Three separate edits, so there are three undo steps.
        for (line in listOf("\n    val a = 1", "\n    val b = 2", "\n    val c = 3")) {
            s.replaceRange(s.selection.start, s.selection.start, line, TextRange(s.selection.start + line.length))
        }
        return s
    }

    private class Rig(val session: EditorSession) {
        var t = 0L
        val scene = ImageComposeScene(width = 822, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Main.kt", session, PreviewBackend, Modifier.fillMaxSize())
                }
            }
        }

        fun pump(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
        }

        /** [fingers] touches side by side at [origin], moved together by [dx] in [steps], then lifted. */
        fun swipe(fingers: Int, origin: Offset, dx: Float, steps: Int = 12, shotMidway: String? = null) {
            fun at(i: Int, shift: Float, pressed: Boolean) =
                ComposeScenePointer(PointerId(i.toLong()), origin + Offset(shift, i * 60f), pressed, PointerType.Touch)
            // Fingers land one after another, as real ones do.
            for (n in 1..fingers) {
                scene.sendPointerEvent(PointerEventType.Press, (0 until n).map { at(it, 0f, true) })
                pump(16)
            }
            for (k in 1..steps) {
                scene.sendPointerEvent(PointerEventType.Move, (0 until fingers).map { at(it, dx * k / steps, true) })
                pump(16)
                if (k == steps && shotMidway != null) {
                    pump(150)
                    val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                    File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$shotMidway").apply { parentFile?.mkdirs() }.writeBytes(png)
                }
            }
            scene.sendPointerEvent(PointerEventType.Release, (0 until fingers).map { at(it, dx, false) })
            pump(60)
        }
    }

    @Test
    fun aThreeFingerSwipeLeftUndoesAStepPerStretchAndRightRedoes() {
        val rig = Rig(session())
        try {
            rig.pump(150)
            val origin = Offset(600f, 80f)
            // 72dp a step = 144px at density 2: 300px is two steps.
            rig.swipe(3, origin, -300f, shotMidway = "undo-gesture.png")
            assertEquals("fun main() {\n    val a = 1\n}\n", rig.session.doc.text, "two edits undone")
            rig.swipe(3, origin + Offset(-300f, 0f), 160f)
            assertEquals("fun main() {\n    val a = 1\n    val b = 2\n}\n", rig.session.doc.text, "one redone")
        } finally {
            rig.scene.close()
        }
    }

    @Test
    fun twoFingersDoNotUndo() {
        val rig = Rig(session())
        try {
            rig.pump(150)
            rig.swipe(2, Offset(600f, 80f), -300f)
            assertEquals("fun main() {\n    val a = 1\n    val b = 2\n    val c = 3\n}\n", rig.session.doc.text)
        } finally {
            rig.scene.close()
        }
    }
}

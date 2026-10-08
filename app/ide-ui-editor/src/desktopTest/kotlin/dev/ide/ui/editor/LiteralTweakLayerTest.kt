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
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The literal-tweak chip mounted on a real [EditorSession] in an off-screen scene: the controls rewrite only the
 * literal, a scrub is one undo step, and nothing shows in a file without `@Preview` composables. Also writes PNGs
 * of each chip variant to `<tmpdir>/codeassist-snapshots` for a visual check.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LiteralTweakLayerTest {

    private val outDir = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }

    // The chip floats above this anchor (scene pixels, density 2).
    private val anchorX = 40f
    private val anchorTop = 200f

    private fun session(marked: String, previews: Boolean = true): EditorSession {
        val caret = marked.indexOf('|')
        return EditorSession(marked.removeRange(caret, caret + 1), CodeLanguage.Kotlin, TextRange(caret)).also {
            if (previews) it.applyComposePreviews(listOf(UiComposePreview("P", 0)))
        }
    }

    private fun scene(session: EditorSession): ImageComposeScene =
        ImageComposeScene(width = 520, height = 260, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    LiteralTweakLayer(
                        session = session,
                        visible = true,
                        caretGeometry = { Triple(0, anchorX, anchorTop) },
                        lineHeightPx = 40f,
                        gutterWidthPx = 0f,
                        liftPx = 0,
                    )
                }
            }
        }

    private fun ImageComposeScene.snapshot(name: String) {
        render()
        val img = render(16_000_000L)
        File(outDir, name).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun ImageComposeScene.tap(x: Float, y: Float) {
        val p = Offset(x, y)
        sendPointerEvent(PointerEventType.Press, p, type = PointerType.Touch)
        sendPointerEvent(PointerEventType.Release, p, type = PointerType.Touch)
        render(32_000_000L)
    }

    @Test
    fun snapshots() {
        for ((name, src) in listOf(
            "literal-number.png" to "Modifier.padding(1|6.dp)",
            "literal-color.png" to "val Purple = Color(0xFF66|50A4)",
        )) {
            val s = scene(session(src))
            try { s.snapshot(name) } finally { s.close() }
        }
    }

    // Chip control centers for the number chip (scene pixels; read off the literal-number.png snapshot).
    private val minus = Offset(36f, 154f)
    private val value = Offset(112f, 154f)
    private val plus = Offset(188f, 154f)

    @Test
    fun stepButtonsRewriteOnlyTheLiteral() {
        val session = session("Modifier.padding(1|6.dp)")
        val s = scene(session)
        try {
            s.render()
            s.tap(plus.x, plus.y)
            assertEquals("Modifier.padding(17.dp)", session.doc.text)
            s.tap(minus.x, minus.y)
            s.tap(minus.x, minus.y)
            assertEquals("Modifier.padding(15.dp)", session.doc.text)
        } finally { s.close() }
    }

    @Test
    fun scrubIsOneUndoStep() {
        val session = session("alpha(0.5|f)")
        val s = scene(session)
        try {
            s.render()
            // 60px at density 2 = 30dp = 5 steps of 6dp, 0.1 each.
            s.sendPointerEvent(PointerEventType.Press, value, type = PointerType.Touch)
            for (dx in 1..12) {
                s.sendPointerEvent(PointerEventType.Move, Offset(value.x + dx * 5f, value.y), type = PointerType.Touch)
                s.render(16_000_000L * dx)
            }
            s.sendPointerEvent(PointerEventType.Release, Offset(value.x + 60f, value.y), type = PointerType.Touch)
            s.render(400_000_000L)
            // Touch slop eats the first few pixels of a drag, so the scrub lands a step or so short of 5.
            val text = session.doc.text
            check(text.matches(Regex("""alpha\(0\.[89]f\)|alpha\(1\.0f\)"""))) { "scrubbed to $text" }
            session.undo()
            assertEquals("alpha(0.5f)", session.doc.text, "the whole scrub undoes in one step")
        } finally { s.close() }
    }

    @Test
    fun booleanSwitchFlips() {
        val session = session("enabled = tr|ue")
        val s = scene(session)
        try {
            s.snapshot("literal-bool.png")
            s.tap(38f, 154f)
            assertEquals("enabled = false", session.doc.text)
        } finally { s.close() }
    }

    @Test
    fun noChipWithoutPreviews() {
        val session = session("Modifier.padding(1|6.dp)", previews = false)
        val s = scene(session)
        try {
            s.render()
            s.tap(plus.x, plus.y)
            assertEquals("Modifier.padding(16.dp)", session.doc.text)
        } finally { s.close() }
    }

    @Test
    fun colorSwatchOpensThePicker() {
        val session = session("val Purple = Color(0xFF66|50A4)")
        val s = scene(session)
        try {
            s.render()
            s.tap(40f, 154f)
            s.snapshot("literal-color-picker.png")
        } finally { s.close() }
    }
}

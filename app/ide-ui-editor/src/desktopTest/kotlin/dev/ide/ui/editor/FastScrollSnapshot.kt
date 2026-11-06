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
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiFileSymbol
import dev.ide.ui.backend.UiUsageHighlight
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The outline scrubber on a long file: scrolled into view, then its thumb dragged, the bubble naming the place. */
class FastScrollSnapshot {
    private val names = (1..30).map { "handleEvent$it" }
    private val sample = buildString {
        appendLine("class EventRouter {")
        for (n in names) {
            appendLine("    fun $n(e: Event) {")
            appendLine("        log(e)")
            appendLine("        dispatch(e)")
            appendLine("    }")
            appendLine()
        }
        appendLine("}")
    }

    private val structure: List<UiFileSymbol> = buildList {
        add(UiFileSymbol("EventRouter", null, "class", sample.indexOf("EventRouter"), sample.length - 1, 0))
        for (n in names) {
            val at = sample.indexOf("fun $n(") + 4
            add(UiFileSymbol(n, null, "function", at, sample.indexOf("    }", at) + 5, 1))
        }
    }

    private val backend = object : IdeBackend by PreviewBackend {
        override val editor: EditorService = object : EditorService by PreviewBackend {
            override suspend fun fileStructure(path: String, text: String) = structure
            override suspend fun usageHighlights(path: String, text: String, offset: Int): List<UiUsageHighlight>? = null
        }
    }

    @Test
    fun theEnclosingDeclarationsAreFoundOutermostFirst() {
        val inside = sample.indexOf("dispatch(e)", sample.indexOf("fun handleEvent12("))
        val lineStartOf = { o: Int -> sample.lastIndexOf('\n', o - 1) + 1 }
        assertEquals(listOf("EventRouter", "handleEvent12"), enclosingDeclarations(structure, inside, lineStartOf).map { it.name })
        val onFun = sample.indexOf("    fun handleEvent12(")
        assertEquals("handleEvent12", enclosingDeclarations(structure, onFun, lineStartOf).last().name, "the `fun` line is inside it")
    }

    @Test
    fun thumbAndScrollMapOntoEachOther() {
        assertEquals(0f, fastScrollThumbTop(500f, 100f, 0f, 2000f))
        assertEquals(400f, fastScrollThumbTop(500f, 100f, 2000f, 2000f))
        assertEquals(1000f, fastScrollOffsetFor(200f, 500f, 100f, 2000f))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun scrubbing() {
        val session = EditorSession(sample, languageFor("EventRouter.kt"), TextRange(0))
        val w = 822
        val scene = ImageComposeScene(width = w, height = 700, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("EventRouter.kt", session, backend, Modifier.fillMaxSize())
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
            pump(600) // the structure fetch is debounced 300ms
            // A wheel notch scrolls the code a little and brings the scrubber up.
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 300f), scrollDelta = Offset(0f, 1f))
            pump(250)
            shot("scrubber-1-shown.png")
            // Grab the thumb near the top right and drag it a good way down.
            val thumb = Offset(w - 14f, 12f + 50f)
            scene.sendPointerEvent(PointerEventType.Press, thumb, type = PointerType.Touch)
            pump(32)
            for (i in 1..20) { scene.sendPointerEvent(PointerEventType.Move, thumb + Offset(0f, i * 15f), type = PointerType.Touch); pump(16) }
            pump(200)
            shot("scrubber-2-dragging.png")
            scene.sendPointerEvent(PointerEventType.Release, thumb + Offset(0f, 300f), type = PointerType.Touch)
            pump(400)
            assertTrue(session.viewportTopLine > 40, "dragging the thumb 300px scrolled far down: top line ${session.viewportTopLine}")
        } finally {
            scene.close()
        }
    }
}

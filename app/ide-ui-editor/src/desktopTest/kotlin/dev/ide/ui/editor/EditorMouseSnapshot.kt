package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** A backend with documentation for every symbol and one fix for every range, so the popups have content. */
private object MouseBackend : IdeBackend by PreviewBackend {
    override val editor: EditorService = object : EditorService by PreviewBackend {
        override suspend fun quickDocAt(path: String, text: String, offset: Int) = UiQuickDoc(
            signature = "fun println(message: Any?)", name = "println", kind = "function",
            container = "kotlin.io", doc = "Prints the given [message] and the line separator to the standard output stream.",
            docFormat = "kdoc",
        )

        override suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int) =
            listOf(UiAction(0, "Create local variable 'undefinedThing'", UiActionKind.QUICK_FIX))
    }
}

/**
 * Off-screen renders of the editor's mouse surfaces, driven by real pointer events: a resting pointer on a
 * problem (its popup) and on a symbol (its documentation), a right-click (the context menu), and Ctrl held
 * over an identifier (the link underline). Not assertions; PNGs for review.
 */
class EditorMouseSnapshot {
    private val sample = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    val message = undefinedThing + name")
        appendLine("    println(message)")
        appendLine("}")
    }

    private fun session(): EditorSession {
        val s = EditorSession(sample, languageFor("Sample.kt"), TextRange(0))
        val start = sample.indexOf("undefinedThing")
        s.applyAnalysis(listOf(
            UiDiagnostic(UiSeverity.Error, 2, 19, "Unresolved reference 'undefinedThing'.", start, start + "undefinedThing".length),
        ))
        return s
    }

    /**
     * One editor, driven through the mouse surfaces in turn: a resting pointer on a problem, then on a symbol,
     * Ctrl held over an identifier, and a right-click. One scene throughout, as in the app.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun mouseSurfaces() {
        val editorSession = session()
        val scene = ImageComposeScene(width = 1200, height = 700, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Sample.kt", editorSession, MouseBackend, Modifier.fillMaxSize())
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
        fun at(line: Int, col: Int) = Offset(GUTTER_PX + col * CHAR_PX + CHAR_PX / 2, TOP_PX + line * LINE_PX + LINE_PX / 2)
        val away = Offset(900f, 600f)
        try {
            pump(200)
            shot("mouse-0-plain.png")

            scene.sendPointerEvent(PointerEventType.Enter, at(1, 22))
            scene.sendPointerEvent(PointerEventType.Move, at(1, 22))
            pump(1200)
            shot("mouse-1-hover-problem.png")
            scene.sendPointerEvent(PointerEventType.Move, away)
            pump(700)

            scene.sendPointerEvent(PointerEventType.Move, at(2, 6))
            pump(1200)
            shot("mouse-2-hover-doc.png")
            scene.sendPointerEvent(PointerEventType.Move, away)
            pump(700)

            val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, at(2, 6), keyboardModifiers = ctrl)
            scene.sendPointerEvent(PointerEventType.Move, at(2, 7), keyboardModifiers = ctrl)
            pump(300)
            shot("mouse-3-ctrl-link.png")
            scene.sendPointerEvent(PointerEventType.Move, away)
            pump(700)

            scene.sendPointerEvent(PointerEventType.Move, at(2, 6))
            scene.sendPointerEvent(PointerEventType.Press, at(2, 6), buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, at(2, 6), buttons = PointerButtons(), button = PointerButton.Secondary)
            pump(500)
            shot("mouse-4-context-menu.png")
        } finally {
            scene.close()
        }
    }

    private companion object {
        // Read off mouse-0-plain.png at density 2; adjust if the editor's metrics change.
        const val GUTTER_PX = 135f
        const val CHAR_PX = 16.2f
        const val TOP_PX = 12f
        const val LINE_PX = 32.5f
    }
}

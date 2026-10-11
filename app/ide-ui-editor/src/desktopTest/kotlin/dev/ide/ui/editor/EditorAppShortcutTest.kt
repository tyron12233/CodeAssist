package dev.ide.ui.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.ext.AppCommandHost
import dev.ide.ui.ext.AppCommands
import dev.ide.ui.ext.AppKeys
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An app-wide shortcut pressed while the editor has focus. The editor resolves its keys itself, Global
 * bindings included, and must hand an app command to the screen's runner (once, not again from the window
 * root) instead of dropping it or typing it into the buffer.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class EditorAppShortcutTest {

    @Test
    fun appShortcutsReachTheRunnerFromAFocusedEditor() {
        val text = "fun main() {\n    println(\"hi\")\n}\n"
        val session = EditorSession(text, languageFor("Main.kt"), TextRange(0))
        val ran = mutableListOf<String>()
        val registration = AppCommandHost.install { id -> ran += id; true }
        val scene = ImageComposeScene(width = 600, height = 300, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                CodeEditor(
                    "Main.kt", session, PreviewBackend,
                    Modifier.fillMaxSize().onPreviewKeyEvent(AppKeys::onPreviewKey).onKeyEvent(AppKeys::onKey),
                )
            }
        }
        try {
            scene.render()
            // A click gives the editor focus, as it would in the app.
            val at = Offset(300f, 20f)
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render(16_000_000L)

            for ((key, ctrl) in listOf(Key.W to true, Key.Tab to true)) {
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, 0, isCtrlPressed = ctrl))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, 0, isCtrlPressed = ctrl))
            }
            scene.render(32_000_000L)

            assertEquals(listOf(AppCommands.CLOSE_TAB, AppCommands.SWITCHER), ran, "each shortcut runs once")
            assertEquals(text, session.doc.text, "nothing was typed into the buffer")
        } finally {
            scene.close()
            registration.dispose()
        }
    }
}

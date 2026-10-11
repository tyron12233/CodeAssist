package dev.ide.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.ide.ui.ext.AppCommandHost
import dev.ide.ui.ext.AppCommands
import dev.ide.ui.ext.AppKeys
import dev.ide.ui.ext.Registration
import dev.ide.ui.platform.shortcutText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app-level key listener, attached at a window root as the app attaches it, with a focused child below
 * it standing in for whatever has focus. No keymap is installed, so the app's built-in defaults answer.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class AppKeysTest {

    private val ran = mutableListOf<String>()
    private var registration: Registration? = null

    @BeforeTest
    fun install() {
        registration = AppCommandHost.install { id -> ran += id; true }
    }

    @AfterTest
    fun reset() {
        registration?.dispose()
        AppCommandHost.interceptor = null
    }

    @Test
    fun anAppShortcutFromAFocusedChildReachesTheRunner() = withScene { scene ->
        assertTrue(press(scene, Key.W, ctrl = true), "the shortcut is consumed")
        press(scene, Key.Tab, ctrl = true)
        // Command+Tab is macOS's app switcher; the switcher binding is the physical Control key. (Compose's own
        // focus traversal may still consume the Tab, so the runner's log is what proves it.)
        press(scene, Key.Tab, meta = true)
        assertEquals(listOf(AppCommands.CLOSE_TAB, AppCommands.SWITCHER), ran)
    }

    @Test
    fun anUnboundKeyIsLeftAlone() = withScene { scene ->
        assertFalse(press(scene, Key.Q))
        assertTrue(ran.isEmpty(), "ran $ran")
    }

    @Test
    fun shiftTwiceOpensSearchEverywhere() = withScene { scene ->
        press(scene, Key.ShiftLeft)
        press(scene, Key.ShiftLeft)
        assertEquals(listOf(AppCommands.SEARCH_EVERYWHERE), ran)
    }

    @Test
    fun typingACapitalIsNotADoubleShift() = withScene { scene ->
        // Shift held for a capital A, then one more Shift tap: only one of the two was a clean tap.
        scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyDown, isShiftPressed = true))
        scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyDown, codePoint = 'A'.code, isShiftPressed = true))
        scene.sendKeyEvent(KeyEvent(Key.A, KeyEventType.KeyUp, isShiftPressed = true))
        scene.sendKeyEvent(KeyEvent(Key.ShiftLeft, KeyEventType.KeyUp))
        press(scene, Key.ShiftLeft)
        assertTrue(ran.isEmpty(), "ran $ran")
    }

    @Test
    fun theInterceptorSeesKeysBeforeAnything() = withScene { scene ->
        val seen = mutableListOf<Key>()
        AppCommandHost.interceptor = { ev -> if (ev.type == KeyEventType.KeyDown) seen += ev.key; true }
        press(scene, Key.W, ctrl = true)
        assertEquals(listOf(Key.W), seen)
        assertTrue(ran.isEmpty(), "a modal switcher owns the keys while it is open; ran $ran")
    }

    @Test
    fun escapeGoesBackToTheEditorWithoutConsumingIt() = withScene { scene ->
        assertFalse(press(scene, Key.Escape), "Escape stays available to whatever else wants it")
        assertEquals(listOf(AppCommands.FOCUS_EDITOR), ran)
    }

    @Test
    fun shortcutsAreWrittenTheWayThePlatformWritesThem() {
        assertEquals("Ctrl+Shift+N", shortcutText("primary+shift+N", commandKey = false))
        assertEquals("⇧⌘N", shortcutText("primary+shift+N", commandKey = true))
        assertEquals("Ctrl+Tab", shortcutText("ctrl+Tab", commandKey = false))
        assertEquals("⌃⇥", shortcutText("ctrl+Tab", commandKey = true))
        assertEquals("Ctrl+PgDn", shortcutText("ctrl+PageDown", commandKey = false))
        assertEquals("Ctrl+K Ctrl+D", shortcutText("primary+K primary+D", commandKey = false))
    }

    private fun withScene(block: (ImageComposeScene) -> Unit) {
        val focus = FocusRequester()
        val scene = ImageComposeScene(200, 200) {
            Box(Modifier.fillMaxSize().onPreviewKeyEvent(AppKeys::onPreviewKey).onKeyEvent(AppKeys::onKey)) {
                Box(Modifier.size(50.dp).focusRequester(focus).focusable())
            }
        }
        try {
            scene.render()
            focus.requestFocus()
            scene.render()
            block(scene)
        } finally {
            scene.close()
        }
    }

    /** Press and release [key]; returns whether the press was consumed. */
    private fun press(
        scene: ImageComposeScene, key: Key,
        ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false,
    ): Boolean {
        val down = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, 0, ctrl, meta, alt, shift))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, 0, ctrl, meta, alt, shift))
        return down
    }
}

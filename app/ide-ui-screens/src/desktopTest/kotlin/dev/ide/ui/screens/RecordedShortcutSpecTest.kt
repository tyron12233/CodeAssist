package dev.ide.ui.screens

import androidx.compose.ui.input.key.Key
import dev.ide.ui.ext.recordedShortcutSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a press records as in the shortcut recorders (the Keymap page and a plugin's shortcut setting). */
class RecordedShortcutSpecTest {

    private fun spec(key: Key, ctrl: Boolean = false, meta: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        recordedShortcutSpec(key, ctrl, meta, alt, shift)

    @Test
    fun aKeyThatTypesNeedsCtrlAltOrMeta() {
        assertNull(spec(Key.K), "a bare letter is text")
        assertNull(spec(Key.K, shift = true), "Shift+K types a capital")
        assertNull(spec(Key.Enter, shift = true))
        assertNull(spec(Key.DirectionUp), "a bare arrow moves the caret")
        assertEquals("primary+K", spec(Key.K, ctrl = true))
        assertEquals("primary+K", spec(Key.K, meta = true), "Ctrl and Meta both mean the platform's primary key")
        assertEquals("alt+shift+K", spec(Key.K, alt = true, shift = true))
        assertEquals("primary+alt+shift+Enter", spec(Key.Enter, ctrl = true, alt = true, shift = true))
    }

    @Test
    fun aKeyThatTypesNothingRecordsBareOrWithShift() {
        assertEquals("F5", spec(Key.F5))
        assertEquals("shift+F10", spec(Key.F10, shift = true), "the default Run shortcut can be recorded")
        assertEquals("primary+F12", spec(Key.F12, ctrl = true))
        assertEquals("Insert", spec(Key.Insert))
        assertEquals("Menu", spec(Key.Menu))
    }

    @Test
    fun aModifierOnItsOwnIsNotAShortcut() {
        assertNull(spec(Key.CtrlLeft, ctrl = true))
        assertNull(spec(Key.ShiftLeft, shift = true))
        assertNull(spec(Key.AltRight, alt = true))
    }
}

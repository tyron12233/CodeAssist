package dev.ide.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Halloween settings: the Pumpkin accent, the caret style and the seasonal-touches switch all survive a
 * save and load, and a value the store does not know falls back instead of reaching the UI.
 */
class SeasonalSettingsTest {

    private val prefs = HashMap<String, String>()
    private val store = SettingsStore(prefs::get, { k, v -> prefs[k] = v })

    @Test
    fun theHalloweenChoicesRoundTrip() {
        store.save(IdeSettings(accent = IdeSettings.ACCENT_PUMPKIN, caretStyle = IdeSettings.CARET_CANDLE, seasonalTouches = false))
        val back = store.load()
        assertEquals(IdeSettings.ACCENT_PUMPKIN, back.accent)
        assertEquals(IdeSettings.CARET_CANDLE, back.caretStyle)
        assertEquals(false, back.seasonalTouches)
    }

    @Test
    fun theGenericSettingsKeysAreTheOnesTheStoreReads() {
        // The Settings screen and the Home card's dress-up both write `settings.<page>.<control>`.
        prefs["settings.appearance.accent"] = "pumpkin"
        prefs["settings.editor.caretStyle"] = "block"
        prefs["settings.appearance.seasonal"] = "false"
        val s = store.load()
        assertEquals("pumpkin", s.accent)
        assertEquals("block", s.caretStyle)
        assertEquals(false, s.seasonalTouches)
    }

    @Test
    fun anUnknownCaretFallsBackToTheLine() {
        prefs["settings.editor.caretStyle"] = "broomstick"
        assertEquals(IdeSettings.CARET_LINE, store.load().caretStyle)
    }

    @Test
    fun seasonalTouchesAreOnByDefault() {
        assertTrue(store.load().seasonalTouches)
    }
}

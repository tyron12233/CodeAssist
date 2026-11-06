package dev.ide.core.settings

import androidx.compose.ui.graphics.Color
import dev.ide.platform.settings.SettingControl
import dev.ide.ui.theme.colors.ContributedAppTheme
import dev.ide.ui.theme.colors.ContributedAppThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A plugin's app theme is an Accent choice: listed while its plugin is loaded, and remembered by id regardless. */
class AppThemeSettingsTest {

    private val prefs = HashMap<String, String>()
    private val store = SettingsStore(prefs::get, { k, v -> prefs[k] = v })

    @Test
    fun aThemeChoiceIsKeptEvenWhileItsPluginIsAway() {
        prefs["settings.appearance.accent"] = "theme:com.example.themes.dune"
        assertEquals("theme:com.example.themes.dune", store.load().accent, "kept, so a restart without the plugin does not forget it")
        prefs["settings.appearance.accent"] = "not-an-accent"
        assertEquals(IdeSettings().accent, store.load().accent, "any other unknown value still falls back")
    }

    @Test
    fun aRegisteredThemeIsOfferedUnderAccentAndGoesWithItsPlugin() {
        fun accentOptions(): List<String> {
            val page = BuiltInSettingsPages.all(analyticsAvailable = false).first { it.id == BuiltInSettingsPages.APPEARANCE }
            val accent = page.controls().filterIsInstance<SettingControl.Choice>().first { it.key == "accent" }
            return accent.options.map { it.value }
        }
        val theme = ContributedAppTheme("com.example.themes.dune", "Dune", Color(0xFFC08A3E), emptyMap(), emptyMap())
        val handle = ContributedAppThemes.register(theme)
        try {
            assertTrue("theme:com.example.themes.dune" in accentOptions())
            assertEquals("lime", accentOptions()[1], "after the IDE's own accents, which keep their order")
        } finally {
            handle.dispose()
        }
        assertFalse("theme:com.example.themes.dune" in accentOptions())
    }
}

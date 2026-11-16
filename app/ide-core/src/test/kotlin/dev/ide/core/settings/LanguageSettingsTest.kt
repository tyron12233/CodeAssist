package dev.ide.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The interface language setting: it follows the device until one is chosen, and only an offered tag is kept. */
class LanguageSettingsTest {

    private val prefs = HashMap<String, String>()
    private val store = SettingsStore(prefs::get, { k, v -> prefs[k] = v })

    @Test
    fun theLanguageFollowsTheDeviceByDefault() {
        assertEquals(IdeSettings.LANGUAGE_SYSTEM, store.load().language)
    }

    @Test
    fun aChosenLanguageRoundTripsThroughTheKeyTheSettingsScreenWrites() {
        store.save(IdeSettings(language = "pt-BR"))
        assertEquals("pt-BR", prefs["settings.appearance.language"])
        assertEquals("pt-BR", store.load().language)
    }

    @Test
    fun aTagThatIsNotOfferedFallsBackToTheDevice() {
        prefs["settings.appearance.language"] = "tlh"
        assertEquals(IdeSettings.LANGUAGE_SYSTEM, store.load().language)
    }

    @Test
    fun theLanguagesPageOffersTheDeviceFirstThenEachLanguageByItsOwnName() {
        val control = BuiltInSettingsPages.all(analyticsAvailable = false)
            .first { it.id == BuiltInSettingsPages.APPEARANCE }
            .controls().filterIsInstance<dev.ide.platform.settings.SettingControl.Choice>()
            .first { it.key == "language" }
        assertEquals(IdeSettings.LANGUAGE_SYSTEM, control.options.first().value)
        assertEquals(IdeSettings.LANGUAGES.map { it.first }, control.options.drop(1).map { it.value })
        assertTrue(control.options.any { it.value == "ar" && it.label == "العربية" })
    }
}

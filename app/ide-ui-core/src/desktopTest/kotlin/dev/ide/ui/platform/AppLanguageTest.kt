package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.settings_title
import dev.ide.ui.theme.rememberUiFont
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The interface language chosen in Settings. Every tag the setting offers has to reach its own translation,
 * which is decided by how the tag is read back as a locale rather than by the tag itself.
 */
class AppLanguageTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    /**
     * Indonesian is not in this list: its strings are in `values-in`, the legacy code a current JDK no longer
     * reports, so the setting does not offer it on this runtime.
     */
    @Test
    fun everyOfferedLanguageShowsItsOwnStrings() {
        val titles = mapOf(
            "en" to "Settings",
            "ar" to "الإعدادات",
            "es" to "Configuración",
            "pt-BR" to "Configurações",
            "ru" to "Настройки",
            "zh" to "设置",
        )
        for ((tag, title) in titles) {
            assertEquals(title, captureIn(tag) { stringResource(Res.string.settings_title) }, "language $tag")
        }
    }

    @Test
    fun aChosenLanguageBringsItsDirectionAndItsFace() {
        assertEquals(LayoutDirection.Rtl, captureIn("ar") { LocalLayoutDirection.current })
        assertNotEquals(FontFamily.SansSerif, captureIn("ar") { rememberUiFont() }, "Arabic gets the bundled face")
        assertEquals(LayoutDirection.Ltr, captureIn("ru") { LocalLayoutDirection.current })
        assertEquals(FontFamily.SansSerif, captureIn("ru") { rememberUiFont() })
    }

    @Test
    fun goingBackToTheSystemLanguageRestoresTheLocaleTheProcessStartedWith() {
        captureIn("ar") { stringResource(Res.string.settings_title) }
        assertEquals("ar", Locale.getDefault().language)
        captureIn(null) { stringResource(Res.string.settings_title) }
        assertEquals(original, Locale.getDefault())
    }

    @Test
    fun rightToLeftLanguagesAreRecognisedInBothSpellings() {
        for (language in listOf("ar", "fa", "he", "iw", "ur")) assertEquals(LayoutDirection.Rtl, layoutDirectionOf(language), language)
        for (language in listOf("en", "id", "in", "zh", "ru")) assertEquals(LayoutDirection.Ltr, layoutDirectionOf(language), language)
    }

    /** Reads one value out of a composition running in the language [tag]. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> captureIn(tag: String?, value: @Composable () -> T): T {
        var captured: T? = null
        val scene = ImageComposeScene(width = 1, height = 1) { AppLanguage(tag) { captured = value() } }
        try {
            scene.render()
            scene.render(16_000_000L) // resource-backed values resolve asynchronously
        } finally {
            scene.close()
        }
        return captured ?: error("nothing was captured")
    }
}

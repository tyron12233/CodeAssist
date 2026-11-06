package dev.ide.ui.theme

import androidx.compose.ui.graphics.Color
import dev.ide.ui.theme.colors.AppThemeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** App theme documents, from Material Theme Builder or by hand, and the Material scheme each renders as. */
class AppThemeTest {

    /** The shape Material Theme Builder exports, trimmed to a few roles; the real file names them all. */
    private val builderExport = """
        {
          "description": "TYPE: CUSTOM\nMaterial Theme Builder export",
          "seed": "#8A5A2B",
          "coreColors": { "primary": "#8A5A2B" },
          "schemes": {
            "light": { "primary": "#7A4F1E", "onPrimary": "#FFFFFF", "surface": "#FFF8F4" },
            "light-medium-contrast": { "primary": "#000000" },
            "dark": { "primary": "#F0BC86", "onPrimary": "#462A00", "surface": "#19120C", "notARole": "#123456" }
          },
          "palettes": { "primary": { "0": "#000000" } }
        }
    """.trimIndent()

    @Test
    fun aThemeBuilderExportReadsItsLightAndDarkSchemesAndIgnoresTheRest() {
        val theme = assertNotNull(AppThemeJson.decode(builderExport, id = "p.dune", name = "Dune"))
        assertEquals(Color(0xFF8A5A2B), theme.seed)
        assertEquals(Color(0xFF7A4F1E), theme.light["primary"], "light, not the medium-contrast variant")
        assertEquals(Color(0xFFF0BC86), theme.dark["primary"])

        val dark = appThemeColorScheme(theme, dark = true)
        assertEquals(Color(0xFFF0BC86), dark.primary)
        assertEquals(Color(0xFF19120C), dark.surface)
        assertEquals(Color(0xFFF0BC86), appThemeColorScheme(theme, dark = true).primary)
        assertEquals(Color(0xFFFFF8F4), appThemeColorScheme(theme, dark = false).surface)
    }

    @Test
    fun aSeedAloneGeneratesTheWholeScheme() {
        val theme = assertNotNull(AppThemeJson.decode("""{ "seed": "#1E88E5" }""", id = "p.blue", name = ""))
        assertEquals("p.blue", theme.name, "no name anywhere falls back to the id")
        val scheme = appThemeColorScheme(theme, dark = true)
        val generated = expressiveColorSchemeFromSeed(Color(0xFF1E88E5), dark = true)
        assertEquals(generated.primary, scheme.primary)
        assertEquals(generated.surfaceContainer, scheme.surfaceContainer)
        assertNotEquals(expressiveColorScheme(CaAccent.Lime, dark = true).primary, scheme.primary)
    }

    @Test
    fun aHandWrittenThemeMayNameOnlySomeRolesAtTheTopLevel() {
        val theme = assertNotNull(AppThemeJson.decode("""{ "name": "Ink", "dark": { "primary": "#FF5370" } }""", "p.ink", ""))
        assertEquals("Ink", theme.name)
        val scheme = appThemeColorScheme(theme, dark = true)
        assertEquals(Color(0xFFFF5370), scheme.primary)
        assertEquals(expressiveColorScheme(CaAccent.Lime, dark = true).surface, scheme.surface, "the rest is the default theme")
    }

    @Test
    fun aDocumentWithNothingUsableIsRejected() {
        assertNull(AppThemeJson.decode("not json", "x", "X"))
        assertNull(AppThemeJson.decode("""{ "schemes": { "dark": { "primary": "nope" } } }""", "x", "X"))
    }
}

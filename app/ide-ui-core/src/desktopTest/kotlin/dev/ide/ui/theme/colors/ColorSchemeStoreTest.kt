package dev.ide.ui.theme.colors

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file format and the preference-backed store — the two places a user's work can be lost.
 *
 * A scheme leaves the device as JSON and comes back through the same path on another install, so a round
 * trip that drops an override is data loss, not a cosmetic bug. And the store is the one piece that has to
 * behave the same on a host whose preference API can only be written to, never deleted from.
 */
class ColorSchemeStoreTest {

    /** The flat key/value store every host actually provides, with the same "no delete" limitation. */
    private class FakePreferences {
        val values = HashMap<String, String>()
        fun get(key: String): String? = values[key]
        fun set(key: String, value: String) {
            values[key] = value
        }
    }

    private fun store(prefs: FakePreferences = FakePreferences()) = ColorSchemeStore(prefs::get, prefs::set)

    private val sample = EditorColorScheme(
        id = "midnight",
        name = "Midnight",
        basedOn = "one",
        dark = mapOf(
            ColorKeys.KEYWORD to AttributeStyle(foreground = Color(0xFFC678DD), bold = true),
            ColorKeys.KOTLIN_EXTENSION to AttributeStyle(inheritParent = true, italic = true),
            ColorKeys.EDITOR_SELECTION to AttributeStyle.bg(Color(0x553E4451)),
        ),
        light = mapOf(ColorKeys.KEYWORD to AttributeStyle.fg(Color(0xFFA626A4))),
    )

    @Test
    fun aSchemeSurvivesARoundTripThroughItsFileFormat() {
        val decoded = ColorSchemeJson.decode(ColorSchemeJson.encode(sample))
        assertEquals(sample.id, decoded?.id)
        assertEquals(sample.name, decoded?.name)
        assertEquals(sample.basedOn, decoded?.basedOn)
        assertEquals(sample.dark, decoded?.dark)
        assertEquals(sample.light, decoded?.light)
    }

    @Test
    fun alphaSurvivesTheRoundTrip() {
        // A selection band is chosen as much by its opacity as its hue; losing the alpha would silently
        // turn every imported selection into an opaque block over the code.
        val decoded = ColorSchemeJson.decode(ColorSchemeJson.encode(sample))
        assertEquals(Color(0x553E4451), decoded?.dark?.get(ColorKeys.EDITOR_SELECTION)?.background)
    }

    @Test
    fun exportIsStableSoAVersionedSchemeOnlyDiffsWhenItChanges() {
        assertEquals(ColorSchemeJson.encode(sample), ColorSchemeJson.encode(sample))
    }

    @Test
    fun anAbsentOverrideStaysAbsentRatherThanBeingFrozenIn() {
        // Writing out resolved values instead of overrides would pin every fallback at export time, so a
        // scheme shared today would stop tracking the attributes the IDE learns tomorrow.
        val decoded = ColorSchemeJson.decode(ColorSchemeJson.encode(sample))
        assertNull(decoded?.dark?.get(ColorKeys.FUNCTION))
        assertEquals(3, decoded?.dark?.size)
    }

    @Test
    fun handWrittenShorthandIsAccepted() {
        val decoded = ColorSchemeJson.decode(
            """{ "name": "Hand", "dark": { "keyword": "#CC7832", "string": { "fg": "6A8759" } } }""",
        )
        assertEquals("hand", decoded?.id)
        assertEquals(Color(0xFFCC7832), decoded?.dark?.get(ColorKeys.KEYWORD)?.foreground)
        assertEquals(Color(0xFF6A8759), decoded?.dark?.get(ColorKeys.STRING)?.foreground)
    }

    @Test
    fun rubbishIsRejectedRatherThanHalfDecoded() {
        assertNull(ColorSchemeJson.decode("not json at all"))
        assertNull(ColorSchemeJson.decode("""{ "dark": { "keyword": "#fff" } }"""), "a scheme needs a name")
        assertNull(ColorSchemeJson.decode("""{ "name": "Broken", "dark": { "keyword": """))
    }

    @Test
    fun savingIndexesAndDeletingForgets() {
        val prefs = FakePreferences()
        val s = store(prefs)
        s.save(sample)
        assertEquals(listOf("midnight"), s.userSchemes().map { it.id })
        assertEquals(sample.dark, s.byId("midnight")?.dark)

        s.delete("midnight")
        assertTrue(s.userSchemes().isEmpty())
        // The document is blanked as well as unindexed: a preference store cannot delete, so leaving the
        // JSON behind would resurrect the scheme the moment the id was reused.
        assertNull(s.byId("midnight"))
    }

    @Test
    fun deletingTheActiveSchemeFallsBackToTheDefault() {
        val s = store()
        s.save(sample)
        s.setActiveId("midnight")
        assertEquals("midnight", s.activeId())
        s.delete("midnight")
        assertEquals(BuiltInColorSchemes.DEFAULT_ID, s.activeId())
    }

    @Test
    fun aStaleActiveIdFallsBackRatherThanRenderingNothing() {
        val prefs = FakePreferences()
        prefs.set("colorScheme.active", "a-scheme-from-a-previous-install")
        assertEquals(BuiltInColorSchemes.DEFAULT_ID, store(prefs).activeId())
        assertEquals(BuiltInColorSchemes.DEFAULT, store(prefs).active())
    }

    @Test
    fun presetsAreReadOnly() {
        val s = store()
        s.save(BuiltInColorSchemes.DARCULA.copy(dark = emptyMap()))
        assertEquals(BuiltInColorSchemes.DARCULA.dark, s.byId("darcula")?.dark)
        s.delete("darcula")
        assertEquals(BuiltInColorSchemes.DARCULA, s.byId("darcula"))
    }

    @Test
    fun duplicatingAPresetGivesAnEditableCopyThatRemembersItsOrigin() {
        val s = store()
        val copy = s.duplicate(BuiltInColorSchemes.DARCULA, "My Darcula")
        assertEquals(false, copy.builtIn)
        assertEquals("darcula", copy.basedOn)
        assertEquals(BuiltInColorSchemes.DARCULA.dark, copy.dark)
        assertNotEquals("darcula", copy.id)
        assertEquals(copy.dark, s.byId(copy.id)?.dark)
    }

    @Test
    fun twoCopiesOfOnePresetAreTellableApart() {
        val s = store()
        val first = s.duplicate(BuiltInColorSchemes.ONE, "One copy")
        val second = s.duplicate(BuiltInColorSchemes.ONE, "One copy")
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.name, second.name)
    }

    @Test
    fun importingNeverOverwritesWhatIsAlreadyThere() {
        val s = store()
        s.save(sample)
        val imported = s.import(ColorSchemeJson.encode(sample))
        // Someone else's file arriving with the same id must land beside the user's work, not on top of it.
        assertNotEquals(sample.id, imported?.id)
        assertNotEquals(sample.name, imported?.name)
        assertEquals(2, s.userSchemes().size)
    }

    @Test
    fun importingRubbishChangesNothing() {
        val s = store()
        s.save(sample)
        assertNull(s.import("<xml>not a scheme</xml>"))
        assertEquals(1, s.userSchemes().size)
    }

    @Test
    fun hexParsingCoversTheFormsAPersonWrites() {
        assertEquals(Color(0xFFAABBCC), hexToColor("#abc"))
        assertEquals(Color(0xFFAABBCC), hexToColor("AABBCC"))
        assertEquals(Color(0x80112233), hexToColor("#80112233"))
        assertNull(hexToColor("#12345"))
        assertNull(hexToColor("chartreuse"))
        assertEquals("#AABBCC", Color(0xFFAABBCC).toHex())
        assertEquals("#80112233", Color(0x80112233).toHex())
    }
}

package dev.ide.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The names are the live store's icon-less plugins, which is who this icon is for. */
class GeneratedIconTest {

    @Test
    fun monogramsOfRealPluginNames() {
        assertEquals("CP", monogram("ColorPicker Plugin"))
        assertEquals("XF", monogram("XML Formater"))
        assertEquals("RB", monogram("Rainbown Brackets"))
        assertEquals("HC", monogram("Hardcoded Compose Strings"))
        assertEquals("TM", monogram("txtManipulation"))
        assertEquals("RT", monogram("Resource Tool"))
    }

    @Test
    fun aOneWordNameGetsOneLetter() = assertEquals("P", monogram("Prism"))

    @Test
    fun aNameThatIsOnlyPluginStillGetsALetter() = assertEquals("P", monogram("Plugin"))

    @Test
    fun aNameWithNoLettersIsNotBlank() = assertEquals("?", monogram("  "))

    @Test
    fun pluginsAreRecognisedByCategoryOrTag() {
        assertTrue(isPluginListing("build", emptyList()))
        assertTrue(isPluginListing("kotlin", listOf("Plugin")))
        assertFalse(isPluginListing("java", listOf("game")))
    }

    @Test
    fun theColoursFollowTheIdNotTheName() {
        // Java's String.hashCode, floor-mod 12. The admin preview reimplements it, and these indexes were
        // computed independently, so a change on either side shows up as a failure here rather than as a
        // plugin that is teal on the phone and pink in the preview.
        assertEquals(palette[2], generatedIconColors("xml-formater-9f1e2d"))
        assertEquals(palette[1], generatedIconColors("color-picker-1a2b3c"))
        assertEquals(palette[1], generatedIconColors("a"))
        assertNotEquals(generatedIconColors("a"), generatedIconColors("b"))
    }

    private val palette = (0 until 12).map { i -> generatedIconColors(seedFor(i)) }

    /** A seed landing on palette index [i], found by search so the test does not hardcode colours. */
    private fun seedFor(i: Int): String = generateSequence(0) { it + 1 }.map { "seed$it" }.first { it.hashCode().mod(12) == i }
}

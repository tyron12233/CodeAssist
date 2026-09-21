package dev.ide.ui.screens

import dev.ide.ui.theme.colors.ColorAttributes
import dev.ide.ui.theme.colors.ColorKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The preview's samples are the only documentation of what each attribute looks like in context, so the
 * things that can rot about them are worth pinning: a tag whose key no longer exists silently paints that
 * run in the default color, and a sample whose marked line numbers drift puts the caret and the squiggle
 * on the wrong rows.
 */
class ColorSchemeSamplesTest {

    @Test
    fun everyTaggedKeyIsARegisteredAttribute() {
        for (sample in ColorSchemeSamples.all) {
            for (key in sample.keys) {
                assertNotNull(ColorAttributes.byKey(key), "${sample.id} tags unregistered attribute '$key'")
            }
        }
    }

    @Test
    fun theSamplesBetweenThemShowEveryAttributeThatIsNotChrome() {
        val shown = ColorSchemeSamples.all.flatMap { it.keys }.toSet() + ColorSchemeSamples.CHROME_KEYS
        val missing = ColorAttributes.all().map { it.key }.filter { it !in shown }
        // Attributes with no color of their own are demonstrated by their parent; everything that can look
        // different from its parent has to appear somewhere, or the user cannot see what they are editing.
        val expectedGaps = setOf(
            ColorKeys.KEYWORD_MODIFIER, ColorKeys.TYPE_PARAMETER, ColorKeys.VARIABLE_PARAMETER,
            ColorKeys.NAMESPACE, ColorKeys.PUNCTUATION, ColorKeys.MODIFIER_STATIC,
            // Default text is what every UNTAGGED run in every sample is drawn in, so it is the one
            // attribute demonstrated by not being tagged.
            ColorKeys.TEXT,
        )
        assertTrue(
            missing.all { it in expectedGaps },
            "these attributes appear in no sample: ${missing - expectedGaps}",
        )
    }

    @Test
    fun theStagedEditorStateLandsOnRealLines() {
        for (sample in ColorSchemeSamples.all) {
            val count = sample.lines.size
            for ((what, line) in listOf(
                "current" to sample.currentLine,
                "selection" to sample.selectionLine,
                "error" to sample.errorLine,
                "warning" to sample.warningLine,
            )) {
                assertTrue(line in 0 until count, "${sample.id}: $what line $line is outside 0..${count - 1}")
            }
            // A marked range that runs past the end of its line would draw a squiggle under nothing.
            val errorLength = sample.lines[sample.errorLine].sumOf { it.text.length }
            assertTrue(
                sample.errorColumns.last < errorLength,
                "${sample.id}: the error range runs past the end of its line",
            )
        }
    }

    @Test
    fun markupSplitsIntoRunsAndKeepsTheTextIntact() {
        val runs = parseSample("[[keyword|val]] x = [[number|1]]").single()
        assertEquals(listOf("val", " x = ", "1"), runs.map { it.text })
        assertEquals(listOf(listOf(ColorKeys.KEYWORD), emptyList(), listOf(ColorKeys.NUMBER)), runs.map { it.keys })
    }

    @Test
    fun layeredKeysAreKeptInOrderSoTheMostSpecificWins() {
        val run = parseSample("[[function+modifier.deprecated|old]]").single().single()
        assertEquals(listOf(ColorKeys.FUNCTION, ColorKeys.MODIFIER_DEPRECATED), run.keys)
        assertEquals(ColorKeys.MODIFIER_DEPRECATED, run.primaryKey)
    }

    @Test
    fun codeFullOfBracketsStaysLiteral() {
        val text = "val xs = arr[[0]] + list[i]"
        assertEquals(text, parseSample(text).single().joinToString("") { it.text })
    }

    @Test
    fun aSamplesTextIsExactlyItsMarkupMinusTheTags() {
        val sample = ColorSchemeSamples.KOTLIN
        val plain = sample.lines.joinToString("\n") { line -> line.joinToString("") { it.text } }
        assertTrue(plain.contains("fun Greeting(name: String, times: Int = 1) {"))
        assertTrue(plain.lines().none { it.contains("[[") || it.contains("]]") })
    }

    @Test
    fun anAttributePicksTheSampleThatActuallyShowsIt() {
        assertEquals("xml", ColorSchemeSamples.bestFor(ColorKeys.XML_TAG).id)
        assertEquals("markdown", ColorSchemeSamples.bestFor(ColorKeys.MD_HEADING).id)
        assertEquals("kotlin", ColorSchemeSamples.bestFor(ColorKeys.KOTLIN_SUSPEND).id)
        // An attribute no sample tags still has to resolve to something rather than crash the screen.
        assertEquals("kotlin", ColorSchemeSamples.bestFor(ColorKeys.PUNCTUATION).id)
    }
}

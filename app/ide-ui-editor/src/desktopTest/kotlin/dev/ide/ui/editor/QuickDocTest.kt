package dev.ide.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure quick-doc markup renderer: HTML/KDoc inline markup → styled text + tag sections. */
class QuickDocTest {
    // A distinctive style so code runs are findable in the produced AnnotatedString.
    private val code = SpanStyle(color = Color.Red)

    @Test
    fun aFencedKdocSampleIsVerbatimCodeAndNotATag() {
        // A library KDoc as the index stores it: undecorated, with a sample whose `@Composable` is code.
        val doc = "Composes [content] into a [ComponentActivity].\n\n```kotlin\n@Composable\nfun Screen() {\n    Text(\"hi\")\n}\n```\n\n@param content the UI"
        val parsed = parseQuickDoc(doc, code)
        val text = parsed.description.text
        assertTrue("@Composable\nfun Screen() {\n    Text(\"hi\")\n}" in text, "sample verbatim, indentation kept: $text")
        assertTrue("```" !in text, text)
        assertEquals(listOf("Parameters"), parsed.sections.map { it.title }, "the sample's @Composable is not a tag")
        val coded = parsed.description.spanStyles.filter { it.item == code }.map { text.substring(it.start, it.end) }
        assertTrue(coded.any { "fun Screen()" in it }, "the sample is code-styled: $coded")
        assertTrue("content" in coded && "ComponentActivity" in coded, "KDoc links render as code: $coded")
    }

    @Test
    fun kdocLinksAndMarkdownLinks() {
        assertEquals("see bar and the docs", inlineMarkup("see [foo.Bar.bar] and [the docs](https://x)", code).text)
        assertEquals("use Label now", inlineMarkup("use [Label][pkg.Target] now", code).text)
        assertEquals("a [1, 2] list", inlineMarkup("a [1, 2] list", code).text, "non-identifier brackets stay literal")
    }

    @Test
    fun anUndecoratedDocKeepsItsBullets() {
        // Only a comment with its /** */ markers has per-line stars to strip.
        assertTrue("* one" in parseQuickDoc("Items:\n* one\n* two", code).description.text)
        assertEquals("Text.", parseQuickDoc("/**\n * Text.\n */", code).description.text)
    }

    @Test
    fun stripsHtmlAndDecodesEntities() {
        assertEquals("Use foo & bar.", inlineMarkup("Use <tt>foo</tt> &amp; bar.", code).text)
        assertEquals("a < b > c", inlineMarkup("a &lt; b &gt; c", code).text)
    }

    @Test
    fun codeSpansAreStyled() {
        val a = inlineMarkup("call `x` and {@code y} now", code)
        assertEquals("call x and y now", a.text)
        val coded = a.spanStyles.filter { it.item == code }.map { a.text.substring(it.start, it.end) }.toSet()
        assertEquals(setOf("x", "y"), coded)
    }

    @Test
    fun boldAndParagraphBreaks() {
        val a = inlineMarkup("a<p>**b** <b>c</b>", code)
        assertEquals("a\n\nb c", a.text)
        // both "b" and "c" are bold (no code style)
        assertTrue(a.spanStyles.none { it.item == code })
    }

    @Test
    fun linkTagKeepsLabelOrSimpleName() {
        assertEquals("see Foo here", inlineMarkup("see {@link pkg.Foo} here", code).text)
        assertEquals("see the bar method", inlineMarkup("see {@link pkg.Foo#bar the bar method} here", code).text.substringBefore(" here"))
    }

    @Test
    fun javadocPartitionsDescriptionAndTags() {
        val doc = "/**\n * Formats the input text.\n * @param fmt the format string\n * @return the result\n */"
        val c = parseQuickDoc(doc, code)
        assertEquals("Formats the input text.", c.description.text)
        assertEquals(listOf("Parameters", "Returns"), c.sections.map { it.title })
        assertTrue(c.sections[0].items[0].text.startsWith("fmt"))
        assertEquals("the result", c.sections[1].items[0].text)
    }

    @Test
    fun kdocBacktickAndPlainParagraphs() {
        val c = parseQuickDoc("Returns the `size`.\n\nMore detail.", code)
        assertEquals("Returns the size.\n\nMore detail.", c.description.text)
        assertTrue(c.sections.isEmpty())
    }
}

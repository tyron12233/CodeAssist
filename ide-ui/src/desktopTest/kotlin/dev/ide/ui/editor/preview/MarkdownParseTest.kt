package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pure Markdown model: block parser + inline markup renderer. */
class MarkdownParseTest {

    // ---- block parser ----

    @Test
    fun headingsWithLevelAndTrailingHashes() {
        val b = parseMarkdown("# Title\n### Deep ###")
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Heading(3, "Deep"), b[1])
    }

    @Test
    fun paragraphsJoinLinesAndSplitOnBlank() {
        val b = parseMarkdown("one\ntwo\n\nthree")
        assertEquals(listOf(MdBlock.Paragraph("one\ntwo"), MdBlock.Paragraph("three")), b)
    }

    @Test
    fun fencedCodeCapturesBodyAndLangAndIgnoresMarkers() {
        val b = parseMarkdown("```kotlin\nval x = 1\n# not a heading\n```")
        val code = assertIs<MdBlock.Code>(b.single())
        assertEquals("kotlin", code.lang)
        assertEquals("val x = 1\n# not a heading", code.code)
    }

    @Test
    fun bulletAndOrderedListsWithNesting() {
        val items = assertIs<MdBlock.Items>(parseMarkdown("- a\n  - b").single()).items
        assertEquals(MdListItem(0, ordered = false, ordinal = 0, text = "a"), items[0])
        assertEquals(MdListItem(1, ordered = false, ordinal = 0, text = "b"), items[1])

        val ordered = assertIs<MdBlock.Items>(parseMarkdown("1. one\n2. two").single()).items
        assertEquals(MdListItem(0, ordered = true, ordinal = 1, text = "one"), ordered[0])
        assertEquals(MdListItem(0, ordered = true, ordinal = 2, text = "two"), ordered[1])
    }

    @Test
    fun blockQuoteParsesInnerBlocks() {
        val quote = assertIs<MdBlock.Quote>(parseMarkdown("> # Q\n> body").single())
        assertEquals(MdBlock.Heading(1, "Q"), quote.blocks[0])
        assertEquals(MdBlock.Paragraph("body"), quote.blocks[1])
    }

    @Test
    fun thematicBreak() {
        assertEquals(MdBlock.Divider, parseMarkdown("---").single())
        assertEquals(MdBlock.Divider, parseMarkdown("***").single())
        // Not a break: a bullet item.
        assertIs<MdBlock.Items>(parseMarkdown("- item").single())
    }

    @Test
    fun toleratesUnclosedFence() {
        val code = assertIs<MdBlock.Code>(parseMarkdown("```\nunclosed").single())
        assertEquals("unclosed", code.code)
    }

    // ---- inline markup ----

    private val base = SpanStyle(color = Color(0xFF111111))
    private val codeStyle = SpanStyle(background = Color.Red)
    private val linkStyle = SpanStyle(color = Color.Blue)
    private val styles = MdStyles(base, codeStyle, linkStyle)

    private fun runsWith(text: String, predicate: (SpanStyle) -> Boolean): Set<String> {
        val a = buildInline(text, styles)
        return a.spanStyles.filter { predicate(it.item) }
            .map { a.text.substring(it.start, it.end) }.toSet()
    }

    @Test
    fun codeSpanStyled() {
        assertEquals("call x now", buildInline("call `x` now", styles).text)
        assertEquals(setOf("x"), runsWith("call `x` now") { it.background == Color.Red })
    }

    @Test
    fun boldAndItalicAndStrikethrough() {
        assertEquals("bold", buildInline("**bold**", styles).text)
        assertTrue(runsWith("**bold**") { it.fontWeight == FontWeight.Bold }.contains("bold"))
        assertTrue(runsWith("*it*") { it.fontStyle == FontStyle.Italic }.contains("it"))
        assertTrue(runsWith("~~gone~~") { it.textDecoration == TextDecoration.LineThrough }.contains("gone"))
    }

    @Test
    fun linksRenderTextAndStyle() {
        assertEquals("docs", buildInline("[docs](http://x)", styles).text)
        assertEquals(setOf("docs"), runsWith("[docs](http://x)") { it.color == Color.Blue })
        // Image renders its alt text.
        assertEquals("logo", buildInline("![logo](a.png)", styles).text)
    }

    @Test
    fun escapeAndUnderscoreInWord() {
        assertEquals("*not italic*", buildInline("\\*not italic\\*", styles).text)
        // Intraword underscores stay literal (no italic).
        assertEquals("a_b_c", buildInline("a_b_c", styles).text)
        assertTrue(runsWith("a_b_c") { it.fontStyle == FontStyle.Italic }.isEmpty())
        // Boundary underscores italicize.
        assertTrue(runsWith("_hi_") { it.fontStyle == FontStyle.Italic }.contains("hi"))
    }
}

package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The incremental styler invariant: after any sequence of [LineStyles.splice] calls, every line's
 * spans must equal what a from-scratch [LineStyles.reset] over the same document produces — i.e.
 * the stop-when-state-stabilizes walk never leaves a stale line behind (block comments opening and
 * closing across edits are exactly the hard case).
 */
class LineStylesTest {

    private fun spansSignature(s: LineStyles, lines: Int): List<String> =
        (0 until lines).map { l -> s.spansFor(l).joinToString(",") { "${it.start}-${it.end}:${it.type}" } }

    private fun assertIncrementalMatchesFresh(doc: EditorDocument, incremental: LineStyles, language: CodeLanguage) {
        val fresh = LineStyles(language)
        fresh.reset(doc)
        assertEquals(
            spansSignature(fresh, doc.lineCount),
            spansSignature(incremental, doc.lineCount),
        )
    }

    /** Apply [replacement] through both the document and the styler the way EditorSession does. */
    private fun edit(docIn: EditorDocument, styles: LineStyles, start: Int, end: Int, replacement: String): EditorDocument {
        val firstLine = docIn.lineForOffset(start)
        val lastLine = docIn.lineForOffset(end)
        val doc = docIn.replace(start, end, replacement)
        var breaks = 0
        for (c in replacement) if (c == '\n') breaks++
        styles.splice(doc, firstLine, lastLine - firstLine + 1, breaks + 1)
        return doc
    }

    @Test
    fun tokensMatchOldScannerShapes() {
        val line = "public static void main(String[] args) { // run"
        val res = styleLine(line, LexState.CODE, CodeLanguage.Java)
        val types = res.spans.map { it.type }
        assertTrue(TokenType.KEYWORD in types)
        assertTrue(TokenType.FUNC in types)   // main(
        assertTrue(TokenType.TYPE in types)   // String
        assertTrue(TokenType.COMMENT in types)
        assertEquals(LexState.CODE, res.exitState)
    }

    @Test
    fun blockCommentCarriesState() {
        val open = styleLine("int a; /* start", LexState.CODE, CodeLanguage.Java)
        assertEquals(LexState.BLOCK_COMMENT, open.exitState)
        val mid = styleLine("still comment", LexState.BLOCK_COMMENT, CodeLanguage.Java)
        assertEquals(LexState.BLOCK_COMMENT, mid.exitState)
        assertEquals(listOf(TokenType.COMMENT), mid.spans.map { it.type })
        val close = styleLine("end */ int b;", LexState.BLOCK_COMMENT, CodeLanguage.Java)
        assertEquals(LexState.CODE, close.exitState)
        assertTrue(close.spans.first().type == TokenType.COMMENT)
        assertTrue(TokenType.KEYWORD in close.spans.map { it.type }) // int after the close
    }

    @Test
    fun openingBlockCommentRipplesDown() {
        var doc = EditorDocument.of("int a;\nint b;\nint c;\n")
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        // type "/*" at the start of line 0 → everything below becomes comment
        doc = edit(doc, styles, 0, 0, "/*")
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
        assertEquals(listOf(TokenType.COMMENT), styles.spansFor(2).map { it.type })
        // now close it on line 1 → line 2 must be restyled back to code
        val line1Start = doc.lineStart(1)
        doc = edit(doc, styles, line1Start, line1Start, "*" + "/")
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
        assertTrue(TokenType.KEYWORD in styles.spansFor(2).map { it.type })
    }

    @Test
    fun xmlStringAcrossLines() {
        val doc = EditorDocument.of("<a name=\"first\nsecond\" attr=\"x\"/>")
        val styles = LineStyles(CodeLanguage.Xml)
        styles.reset(doc)
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Xml)
        assertEquals(LineSpanTypeAt(styles, 1, 0), TokenType.STRING) // line 1 starts inside the string
    }

    private fun LineSpanTypeAt(s: LineStyles, line: Int, col: Int): TokenType? =
        s.spansFor(line).firstOrNull { col >= it.start && col < it.end }?.type

    /** Token type covering [col] in a freshly styled [line], or null if the column is left uncolored. */
    private fun typeAt(line: String, col: Int, language: CodeLanguage): TokenType? =
        styleLine(line, LexState.CODE, language).spans.firstOrNull { col >= it.start && col < it.end }?.type

    @Test
    fun kotlinStringInterpolationHighlightsNestedCode() {
        // The reported bug: keywords + nested strings inside `${…}` were swallowed into the outer string.
        val line = "    println(\"I \${if (b) \"got\" else \"lost\"} focus.\")"
        // Anchor by content so the assertions survive if the leading indent changes.
        fun at(sub: String) = typeAt(line, line.indexOf(sub), CodeLanguage.Kotlin)
        assertEquals(TokenType.KEYWORD, at("if ("), "`if` inside \${} should be a keyword")
        assertEquals(TokenType.KEYWORD, at("else "), "`else` inside \${} should be a keyword")
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("got"), CodeLanguage.Kotlin), "nested \"got\" should be a string")
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("lost"), CodeLanguage.Kotlin), "nested \"lost\" should be a string")
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("I \$"), CodeLanguage.Kotlin), "the outer literal `I ` is a string")
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf(" focus"), CodeLanguage.Kotlin), "the trailing literal is a string")
        assertEquals(TokenType.FUNC, at("println"), "`println(` is a call")
        // `b` inside the interpolation is left for the semantic layer (not string-green).
        assertEquals(null, typeAt(line, line.indexOf("b)"), CodeLanguage.Kotlin), "the interpolated var is uncolored lexically")
    }

    @Test
    fun kotlinSimpleInterpolationLeavesNameUncolored() {
        val line = "val m = \"hi \$name!\""
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("hi"), CodeLanguage.Kotlin))
        assertEquals(null, typeAt(line, line.indexOf("name"), CodeLanguage.Kotlin), "\$name identifier is left for semantics")
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("!"), CodeLanguage.Kotlin), "the `!\"` tail is still string")
        assertEquals(TokenType.KEYWORD, typeAt(line, 0, CodeLanguage.Kotlin), "`val` is a keyword")
    }

    @Test
    fun kotlinRawStringCarriesStateAcrossLines() {
        val doc = EditorDocument.of("val s = \"\"\"abc\ndef\"\"\".trim()")
        val styles = LineStyles(CodeLanguage.Kotlin)
        styles.reset(doc)
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Kotlin)
        assertEquals(LexState.KT_RAW_STRING, styleLine(doc.lineText(0), LexState.CODE, CodeLanguage.Kotlin).exitState)
        assertEquals(TokenType.STRING, LineSpanTypeAt(styles, 1, 0), "line 1 opens inside the raw string")
        // `trim` after the closing `"""` on line 1 is back to code.
        assertEquals(TokenType.FUNC, LineSpanTypeAt(styles, 1, doc.lineText(1).indexOf("trim")))
    }

    @Test
    fun kotlinKeywordsHighlightOutsideStrings() {
        assertEquals(TokenType.KEYWORD, typeAt("fun foo() {}", 0, CodeLanguage.Kotlin))
        assertEquals(TokenType.KEYWORD, typeAt("when (x) {}", 0, CodeLanguage.Kotlin))
        assertEquals(TokenType.KEYWORD, typeAt("if (a) b else c", 0, CodeLanguage.Kotlin))
        assertEquals(TokenType.KEYWORD, typeAt("if (a) b else c", "if (a) b ".length, CodeLanguage.Kotlin))
    }

    @Test
    fun fuzzKotlinIncrementalEqualsFresh() {
        val rnd = Random(11)
        val snippets = listOf(
            "/*", "*" + "/", "//x", "\"s\"", "\"\"" + "\"", "\${", "}", "if ", "else ", "fun ",
            "\"a\$x b\${y}c\"", "\n", "}", "{", "a", " ",
        )
        var doc = EditorDocument.of(
            "fun f(b: Boolean) {\n" +
                "  val s = \"\"\"raw \$b\ntext\"\"\"\n" +
                "  println(\"I \${if (b) \"got\" else \"lost\"} f\")\n" +
                "}\n"
        )
        val styles = LineStyles(CodeLanguage.Kotlin)
        styles.reset(doc)
        repeat(800) {
            val len = doc.text.length
            val start = rnd.nextInt(len + 1)
            val del = rnd.nextInt(6)
            val end = (start + del).coerceAtMost(len)
            val ins = if (rnd.nextBoolean()) snippets[rnd.nextInt(snippets.size)] else ""
            doc = edit(doc, styles, start, end, ins)
            assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Kotlin)
        }
    }

    @Test
    fun fuzzIncrementalEqualsFresh() {
        val rnd = Random(7)
        val snippets = listOf("/*", "*" + "/", "//x", "\"s\"", "int ", "\n", "}", "{", "a", " ")
        var doc = EditorDocument.of("class A {\n    int x = 1; /* note */\n    // line\n    String s = \"v\";\n}\n")
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        repeat(800) {
            val len = doc.text.length
            val start = rnd.nextInt(len + 1)
            val del = rnd.nextInt(6)
            val end = (start + del).coerceAtMost(len)
            val ins = if (rnd.nextBoolean()) snippets[rnd.nextInt(snippets.size)] else ""
            doc = edit(doc, styles, start, end, ins)
            assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
        }
    }
}

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
    fun kotlinValueClassModifierIsAKeyword() {
        // `value` is a keyword only in `value class` (a soft keyword); as a plain identifier it stays uncolored.
        val decl = "value class Password(val v: String)"
        assertEquals(TokenType.KEYWORD, typeAt(decl, 0, CodeLanguage.Kotlin), "`value` before `class` is a keyword")
        assertEquals(TokenType.KEYWORD, typeAt(decl, decl.indexOf("class"), CodeLanguage.Kotlin), "`class` is a keyword")
        // A plain `value` identifier (a common name) must NOT be colored as a keyword.
        assertEquals(null, typeAt("val value = x.value", "val ".length, CodeLanguage.Kotlin), "`value` as an identifier is not a keyword")
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

    @Test
    fun bulkMultiLineInsertMatchesFresh() {
        // A single edit that inserts hundreds of lines drives splice() with inserted >> removed — the path
        // that used to do one O(N) add(firstLine) per inserted line (O(K·N) overall). Assert the incremental
        // result still equals a from-scratch reset and the line count is right.
        var doc = EditorDocument.of("class A {\n}\n")
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        val block = (0 until 500).joinToString("\n") { "    int x$it = $it;" } + "\n"
        val at = doc.lineStart(1) // start of the closing-brace line
        doc = edit(doc, styles, at, at, block)
        assertEquals(503, doc.lineCount) // 2 original + 500 inserted + trailing-newline line
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
    }

    @Test
    fun bulkMultiLineDeleteMatchesFresh() {
        // The mirror case: a single edit that removes hundreds of lines drives splice() with removed >>
        // inserted (the subList.clear() path). Build a tall doc, then delete most of it in one splice.
        var doc = EditorDocument.of((0 until 500).joinToString("\n") { "int a$it = $it;" } + "\n")
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        val from = doc.lineStart(10)
        val to = doc.lineStart(480)
        doc = edit(doc, styles, from, to, "")
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
    }

    @Test
    fun bulkInsertOpeningBlockCommentRipplesPastInsertedRegion() {
        // A bulk insert whose new lines open an unterminated block comment must restyle the lines BELOW the
        // inserted region too — exercises the re-tokenize walk continuing past the freshly-inserted placeholders.
        var doc = EditorDocument.of("int a;\nint b;\nint c;\n")
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        val block = "/* open\n" + (0 until 300).joinToString("\n") { "still comment $it" } + "\n"
        doc = edit(doc, styles, 0, 0, block)
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
        // Everything after the unterminated `/*` — including the pre-existing lines — is now comment.
        assertEquals(listOf(TokenType.COMMENT), styles.spansFor(doc.lineCount - 2).map { it.type })
    }

    @Test
    fun lazyTokenizationOfAFarLineCarriesStateFromTheTop() {
        // Querying a line WITHOUT having queried the ones above it must still carry lexer state from the top —
        // the lazy high-water prefix extends up to the requested line. Line 0 opens a block comment, so a
        // first-ever query of line 3 must see it as a comment.
        val doc = EditorDocument.of("/* open\nstill\nmore\nend of comment")
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        assertEquals(listOf(TokenType.COMMENT), styles.spansFor(3).map { it.type }, "far line inherits block-comment state")
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
    }

    @Test
    fun editRangeReachingBeyondHighWaterMatchesFresh() {
        // Delete a range that extends past the tokenized prefix: only lines 0..1 are tokenized (we query line 1)
        // before deleting lines 1..14. The splice must still produce a result equal to a fresh re-tokenization.
        var doc = EditorDocument.of((0 until 20).joinToString("\n") { "int a$it = $it;" })
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        styles.spansFor(1) // tokenize only lines 0..1 → highWater = 1
        val from = doc.lineStart(1)
        val to = doc.lineStart(15)
        doc = edit(doc, styles, from, to, "")
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
    }

    @Test
    fun revisionsAreStableAndBumpOnlyOnRetokenize() {
        val doc = EditorDocument.of("val a = 1\nval b = 2\nval c = 3")
        val styles = LineStyles(CodeLanguage.Kotlin)
        styles.reset(doc)
        val r0 = styles.revOf(0)
        val r1 = styles.revOf(1)
        // A repeat query returns the SAME revision (no re-tokenization) — the render cache relies on this.
        assertEquals(r0, styles.revOf(0))
        assertEquals(r1, styles.revOf(1))
        // Editing line 1 in place bumps its revision but leaves line 0's untouched.
        val l1 = doc.lineStart(1)
        edit(doc, styles, l1, l1, "x")
        assertEquals(r0, styles.revOf(0), "unedited line keeps its revision")
        assertTrue(styles.revOf(1) != r1, "edited line gets a fresh revision")
    }

    @Test
    fun fuzzLazyInterleavedEqualsFresh() {
        // Like the other fuzz, but between edits we query only a RANDOM single line (keeping the tokenized
        // prefix partial), so edits frequently reach past the high-water mark — the lazy-tokenization edge.
        val rnd = Random(29)
        val snippets = listOf("/*", "*" + "/", "//x", "\"s\"", "int ", "\n", "\n\n", "}", "{", "a", " ")
        var doc = EditorDocument.of((0 until 40).joinToString("\n") { "int x$it = $it; /* c$it */" })
        val styles = LineStyles(CodeLanguage.Java)
        styles.reset(doc)
        repeat(1200) { iter ->
            val len = doc.text.length
            val start = rnd.nextInt(len + 1)
            val end = (start + rnd.nextInt(8)).coerceAtMost(len)
            val ins = if (rnd.nextBoolean()) snippets[rnd.nextInt(snippets.size)] else ""
            doc = edit(doc, styles, start, end, ins)
            if (doc.lineCount > 0) styles.spansFor(rnd.nextInt(doc.lineCount)) // partial: keep highWater low
            if (iter % 25 == 0) assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
        }
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Java)
    }

    @Test
    fun markdownConstructsAreTyped() {
        assertEquals(TokenType.TYPE, typeAt("## Heading", 0, CodeLanguage.Markdown), "heading")
        assertEquals(TokenType.KEYWORD, typeAt("- item", 0, CodeLanguage.Markdown), "bullet marker")
        assertEquals(TokenType.KEYWORD, typeAt("12. item", 0, CodeLanguage.Markdown), "ordered marker")
        assertEquals(null, typeAt("- item", "- ".length, CodeLanguage.Markdown), "list text is uncolored")
        assertEquals(TokenType.COMMENT, typeAt("> quote", 0, CodeLanguage.Markdown), "block quote")
        assertEquals(TokenType.PUNCT, typeAt("---", 0, CodeLanguage.Markdown), "thematic break")
        assertEquals(TokenType.STRING, typeAt("use `code` here", "use ".length, CodeLanguage.Markdown), "inline code")
        val link = "see [docs](http://x)"
        assertEquals(TokenType.FUNC, typeAt(link, link.indexOf("[docs]"), CodeLanguage.Markdown), "link text")
        assertEquals(TokenType.PROPERTY, typeAt(link, link.indexOf("(http"), CodeLanguage.Markdown), "link url")
        // `#hashtag` (no space) is not a heading; a hyphen inside a word is not a break.
        assertEquals(null, typeAt("a-b not a rule", 0, CodeLanguage.Markdown))
    }

    @Test
    fun markdownFenceCarriesStateAcrossLines() {
        val doc = EditorDocument.of("```kotlin\nval x = 1\n# not a heading\n```\n# real")
        val styles = LineStyles(CodeLanguage.Markdown)
        styles.reset(doc)
        assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Markdown)
        assertEquals(LexState.MD_FENCE, styleLine(doc.lineText(0), LexState.CODE, CodeLanguage.Markdown).exitState)
        assertEquals(TokenType.STRING, LineSpanTypeAt(styles, 1, 0), "code inside the fence")
        assertEquals(TokenType.STRING, LineSpanTypeAt(styles, 2, 0), "`#` inside the fence is not a heading")
        assertEquals(LexState.CODE, styleLine(doc.lineText(3), LexState.MD_FENCE, CodeLanguage.Markdown).exitState)
        assertEquals(TokenType.TYPE, LineSpanTypeAt(styles, 4, 0), "heading after the fence closes")
    }

    @Test
    fun fuzzMarkdownIncrementalEqualsFresh() {
        val rnd = Random(13)
        val snippets = listOf("```", "~~~", "# ", "> ", "- ", "`x`", "[a](b)", "\n", "text", " ", "---")
        var doc = EditorDocument.of("# Title\n\ntext with `code`\n\n```\nfenced\n```\n- a\n- b\n")
        val styles = LineStyles(CodeLanguage.Markdown)
        styles.reset(doc)
        repeat(800) {
            val len = doc.text.length
            val start = rnd.nextInt(len + 1)
            val del = rnd.nextInt(6)
            val end = (start + del).coerceAtMost(len)
            val ins = if (rnd.nextBoolean()) snippets[rnd.nextInt(snippets.size)] else ""
            doc = edit(doc, styles, start, end, ins)
            assertIncrementalMatchesFresh(doc, styles, CodeLanguage.Markdown)
        }
    }
}

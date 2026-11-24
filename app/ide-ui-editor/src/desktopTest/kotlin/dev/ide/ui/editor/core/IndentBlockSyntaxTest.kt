package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.ext.EditorLanguageProfile
import dev.ide.ui.ext.SyntaxFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SyntaxFamily.INDENT_BLOCK: a Python-shaped language registered by profile alone colors, indents, dedents and
 * folds correctly, with Python's answers filling whatever the profile leaves empty.
 */
class IndentBlockSyntaxTest {

    private val python = CodeLanguage(
        EditorLanguageProfile(id = "python", suffixes = listOf(".py"), syntax = SyntaxFamily.INDENT_BLOCK, lineComment = "#")
    )

    private fun spans(line: String, state: Int = LexState.CODE) = styleLine(line, state, python)

    private fun typeAt(line: String, column: Int, state: Int = LexState.CODE): TokenType? =
        spans(line, state).spans.lastOrNull { column >= it.start && column < it.end }?.type

    /** The document after typing [ch] at the `|` in [marked], with the caret shown as `|`. */
    private fun type(marked: String, ch: Char): String {
        val pos = marked.indexOf('|')
        val text = marked.removeRange(pos, pos + 1)
        val edit = smartInsert(text, pos, pos, ch, python)
        val out = text.substring(0, edit.start) + edit.text + text.substring(edit.end)
        return out.substring(0, edit.caret) + "|" + out.substring(edit.caret)
    }

    private fun shiftEnter(marked: String): String {
        val pos = marked.indexOf('|')
        val text = marked.removeRange(pos, pos + 1)
        val edit = smartEnter(text, pos, python)
        val out = text.substring(0, edit.start) + edit.text + text.substring(edit.end)
        return out.substring(0, edit.caret) + "|" + out.substring(edit.caret)
    }

    // ---- coloring ------------------------------------------------------------------------------------

    @Test
    fun `hash starts a comment and floor division does not`() {
        val line = "x = a // b  # half"
        assertEquals(TokenType.OPERATOR, typeAt(line, line.indexOf("//")))
        assertEquals(TokenType.COMMENT, typeAt(line, line.indexOf("# half")))
        assertNull(typeAt(line, line.indexOf("b")))
    }

    @Test
    fun `python keywords apply when the profile names none`() {
        val line = "def run(self): return None"
        assertEquals(TokenType.KEYWORD, typeAt(line, 0))
        assertEquals(TokenType.FUNC, typeAt(line, line.indexOf("run")))
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(line, line.indexOf("return")))
        assertEquals(TokenType.KEYWORD, typeAt(line, line.indexOf("None")))
    }

    @Test
    fun `single quotes are strings, not chars`() {
        val line = "name = 'tyron'"
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("tyron")))
    }

    @Test
    fun `a docstring carries across lines and colors as documentation`() {
        val first = spans("    \"\"\"Sum the values.")
        assertNotEquals(LexState.CODE, first.exitState)
        assertEquals(TokenType.DOC_COMMENT, typeAt("    \"\"\"Sum the values.", 6))
        val middle = spans("    # still inside", first.exitState)
        assertEquals(first.exitState, middle.exitState)
        assertEquals(TokenType.DOC_COMMENT, middle.spans.single().type)
        val last = spans("    \"\"\" + x", first.exitState)
        assertEquals(LexState.CODE, last.exitState)
    }

    @Test
    fun `an assigned triple-quoted string is a value, not documentation`() {
        val line = "query = '''select"
        assertEquals(TokenType.RAW_STRING, typeAt(line, line.indexOf("select")))
        assertNotEquals(LexState.CODE, spans(line).exitState)
    }

    @Test
    fun `an f-string colors its expressions as code`() {
        val line = "msg = f\"hi {user.name.upper()} {{literal}} {count:>4}\""
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("f\"")))
        assertEquals(TokenType.FUNC, typeAt(line, line.indexOf("upper")))
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("literal")))
        assertNull(typeAt(line, line.indexOf("count")))
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf(">4")))
    }

    @Test
    fun `a plain string does not interpolate`() {
        val line = "s = \"{not_code()}\""
        assertEquals(TokenType.STRING, typeAt(line, line.indexOf("not_code")))
    }

    @Test
    fun `a decorator starts a line, and an at sign elsewhere is matrix multiplication`() {
        assertEquals(TokenType.ANNOTATION, typeAt("@app.route", 3))
        val product = "c = a @ b"
        assertEquals(TokenType.OPERATOR, typeAt(product, product.indexOf('@')))
    }

    @Test
    fun `numbers with exponents and underscores are one token`() {
        val line = "x = 1_000 + 2.5e-3"
        val number = spans(line).spans.filter { it.type == TokenType.NUMBER }
        assertEquals(listOf("1_000", "2.5e-3"), number.map { line.substring(it.start, it.end) })
    }

    // ---- typing --------------------------------------------------------------------------------------

    @Test
    fun `enter after a block opener indents`() {
        assertEquals("def f():\n    |", type("def f():|", '\n'))
        assertEquals("if a:  # why\n    |", type("if a:  # why|", '\n'))
    }

    @Test
    fun `enter after a block exit dedents`() {
        assertEquals("def f():\n    return 1\n|", type("def f():\n    return 1|", '\n'))
        assertEquals("for x in y:\n    if x:\n        continue\n    |", type("for x in y:\n    if x:\n        continue|", '\n'))
    }

    @Test
    fun `enter inside an open bracket hangs or aligns`() {
        assertEquals("call(\n    |", type("call(|", '\n'))
        assertEquals("result = call(a,\n              |", type("result = call(a,|", '\n'))
    }

    @Test
    fun `a header wrapped over several lines indents from where it started`() {
        val doc = "def f(a,\n      b):|"
        assertEquals("def f(a,\n      b):\n    |", type(doc, '\n'))
    }

    @Test
    fun `typing the colon of else moves it back to its if`() {
        assertEquals("if a:\n    x = 1\nelse:|", type("if a:\n    x = 1\n    else|", ':'))
    }

    @Test
    fun `else stays put when it is already level with an open header`() {
        val doc = "if a:\n    if b:\n        y()\n    else|"
        assertEquals("if a:\n    if b:\n        y()\n    else:|", type(doc, ':'))
    }

    @Test
    fun `except lines up with try, not with an if in between`() {
        val doc = "try:\n    if a:\n        b()\n        except|"
        assertEquals("try:\n    if a:\n        b()\nexcept:|", type(doc, ':'))
    }

    @Test
    fun `a colon inside a dict does not dedent`() {
        val doc = "if a:\n    d = {else_|"
        assertEquals("if a:\n    d = {else_:|", type(doc, ':'))
    }

    @Test
    fun `shift enter completes a header missing its colon`() {
        assertEquals("if ready:\n    |", shiftEnter("if re|ady"))
        assertEquals("x = 1\n|", shiftEnter("x = |1"))
    }

    @Test
    fun `the third quote opens a triple-quoted string`() {
        assertEquals("\"\"\"|\"\"\"", type("\"\"|", '"'))
        assertEquals("'''|'''", type("''|", '\''))
    }

    @Test
    fun `toggle comment uses the hash`() {
        val session = EditorSession("x = 1", python)
        session.toggleComment()
        assertTrue(session.doc.text.startsWith("#"), session.doc.text)
    }

    // ---- folding -------------------------------------------------------------------------------------

    @Test
    fun `blocks fold by indentation, and a docstring at column zero does not end one`() {
        val text = """
            |class A:
            |    def f(self):
            |        ""${'"'}
            |doc at column zero
            |        ""${'"'}
            |        return 1
            |
            |    def g(self):
            |        pass
            |x = 1
        """.trimMargin()
        val folds = indentBlockFolds(text, python.profile)
        val lines = text.lines()
        fun lineOf(offset: Int) = text.substring(0, offset).count { it == '\n' }
        val byHeader = folds.associate { lines[lineOf(it.start)].trim() to lineOf(it.end) }
        assertEquals(8, byHeader["class A:"])
        assertEquals(5, byHeader["def f(self):"])
        assertEquals(8, byHeader["def g(self):"])
        assertFalse(folds.any { lines[lineOf(it.start)].trim() == "x = 1" })
    }

    @Test
    fun `a header wrapped over lines folds from its last line`() {
        val text = "def f(a,\n      b):\n    return a\nx = 2"
        val folds = indentBlockFolds(text, python.profile)
        assertEquals(1, folds.size)
        assertEquals(text.indexOf("b):") + 3, folds.single().start)
        assertEquals(text.indexOf("return a") + "return a".length, folds.single().end)
    }

    // ---- the brace family now honors the profile's comment marker ----------------------------------------

    @Test
    fun `a brace language with a hash comment colors it, and slashes stop being a comment`() {
        val calc = CodeLanguage(
            EditorLanguageProfile(id = "calc", suffixes = listOf(".calc"), syntax = SyntaxFamily.C_FAMILY, lineComment = "#")
        )
        val line = "x = 6 // 2 # note"
        val styled = styleLine(line, LexState.CODE, calc).spans
        assertEquals(TokenType.COMMENT, styled.last().type)
        assertEquals(line.indexOf('#'), styled.last().start)
    }

    @Test
    fun `a brace language's multi-line strings carry across lines`() {
        val dart = CodeLanguage(
            EditorLanguageProfile(
                id = "dart", suffixes = listOf(".dart"), syntax = SyntaxFamily.C_FAMILY, lineComment = "//",
                multilineStrings = listOf("'''"), stringPrefixes = setOf("r"),
            )
        )
        val open = styleLine("var s = r'''first", LexState.CODE, dart)
        assertNotEquals(LexState.CODE, open.exitState)
        val close = styleLine("last''';", open.exitState, dart)
        assertEquals(LexState.CODE, close.exitState)
        assertEquals(TokenType.RAW_STRING, close.spans.first().type)
    }
}

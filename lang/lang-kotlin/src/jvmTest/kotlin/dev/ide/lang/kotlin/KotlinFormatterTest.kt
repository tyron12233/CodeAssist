package dev.ide.lang.kotlin

import dev.ide.lang.formatting.BracePlacement
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.WrapPolicy
import dev.ide.lang.incremental.DocumentEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-rolled Kotlin re-indenter (no IntelliJ formatting model in the embeddable compiler). Drives the
 * pure [KotlinFormatter.reformat] entry: re-indents from brace/paren depth, trims trailing whitespace,
 * collapses blank-line runs, and keeps a single final newline, while leaving multi-line string / comment
 * content byte-for-byte intact. Asserts idempotency and that range formatting touches only the selection.
 */
class KotlinFormatterTest {

    private fun apply(src: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(src)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    private fun fmt(src: String, style: FormatStyle = FormatStyle.GOOGLE): String =
        apply(src, KotlinFormatter.reformat("Test.kt", src, style, 0, src.length))

    /**
     * The interior of a multi-line comment survives, which is the formatter's one absolute rule: a reflowed
     * comment is an edit the user did not ask for and cannot see coming.
     *
     * Regression. The guard used to be `e is PsiComment || e is KDoc`, and those classes stopped existing in
     * the tree when the backend moved to the vendored parser (a comment is a TOKEN there, and trivia, so it is
     * neither that class nor even in `children`). The test suite did not notice: every other formatter
     * assertion is about code, and re-indenting code around an unprotected comment still produces
     * well-indented code.
     */
    @Test
    fun leavesTheInsideOfAMultiLineCommentAlone() {
        val src = "class A {\n/*\n      keep   this\n   ragged\n*/\nfun f() {}\n}\n"
        val out = fmt(src)
        assertTrue(out.contains("      keep   this"), "block comment interior reflowed; got:\n$out")
        assertTrue(out.contains("   ragged"), "block comment interior reflowed; got:\n$out")

        val doc = "class A {\n/**\n *     a   doc   line\n */\nfun f() {}\n}\n"
        val docOut = fmt(doc)
        assertTrue(docOut.contains(" *     a   doc   line"), "KDoc interior reflowed; got:\n$docOut")
    }

    @Test
    fun reindentsNestedBlocks() {
        val out = fmt("class A {\nfun f() {\nval x = 1\n}\n}\n")
        assertEquals("class A {\n  fun f() {\n    val x = 1\n  }\n}\n", out)
    }

    @Test
    fun trimsTrailingAndCollapsesBlankLines() {
        val out = fmt("val a = 1   \n\n\n\nval b = 2\n")
        assertEquals("val a = 1\n\nval b = 2\n", out)
    }

    @Test
    fun idempotent() {
        val once = fmt("class A {\n        fun f() {\nval x=1\n}\n}\n")
        assertEquals(once, fmt(once))
        assertTrue("a formatted buffer yields no edits") {
            KotlinFormatter.reformat("Test.kt", once, FormatStyle.GOOGLE, 0, once.length).isEmpty()
        }
    }

    @Test
    fun androidPresetUsesFourSpaces() {
        val out = fmt("class A {\nval x = 1\n}\n", FormatStyle.ANDROID)
        assertEquals("class A {\n    val x = 1\n}\n", out)
    }

    @Test
    fun preservesMultilineStringContent() {
        val src = "fun f() {\nval s = \"\"\"\n      keep   me   \n  \"\"\"\n}\n"
        val out = fmt(src)
        // The indentation AND trailing spaces inside the triple-quoted string must survive untouched.
        assertTrue("string body preserved verbatim: <$out>") { out.contains("\n      keep   me   \n") }
    }

    @Test
    fun blankLinesToKeepIsHonored() {
        val src = "val a = 1\n\n\n\n\nval b = 2\n"
        val out = fmt(src, FormatStyle.GOOGLE.copy(styleId = "custom", blankLinesToKeep = 2))
        assertEquals("val a = 1\n\n\nval b = 2\n", out)
    }

    @Test
    fun normalizesInlineSpacing() {
        val out = fmt("fun f() {\nval x = a+b\ng(1 ,2)\n}\n", FormatStyle.GOOGLE)
        assertTrue("operators spaced: <$out>") { out.contains("a + b") }
        assertTrue("comma normalized: <$out>") { out.contains("g(1, 2)") }
    }

    @Test
    fun spacingTogglesOff() {
        val out = fmt("fun f() {\nval x = a + b\n}\n", FormatStyle.GOOGLE.copy(styleId = "custom", spaceAroundOperators = false))
        assertTrue("operators tightened: <$out>") { out.contains("a+b") }
    }

    @Test
    fun ignoresBracePlacementAndWrapping() {
        // Brace placement and wrapping need a reflow engine the Kotlin re-indenter does not have, so toggling
        // them must not change the Kotlin output.
        val src = "class A {\nfun f() {\nval x = 1\n}\n}\n"
        val plain = fmt(src, FormatStyle.GOOGLE)
        val withJavaOnly = fmt(
            src,
            FormatStyle.GOOGLE.copy(
                styleId = "custom",
                bracePlacement = BracePlacement.NEXT_LINE,
                wrapMethodArguments = WrapPolicy.ONE_PER_LINE,
                wrapBinaryExpressions = WrapPolicy.ONE_PER_LINE,
            ),
        )
        assertEquals(plain, withJavaOnly)
    }

    @Test
    fun rangeFormatOnlyTouchesSelection() {
        val src = "class A {\nval x = 1\nval y = 2\n}\n"
        val lineStart = src.indexOf("val x")
        val lineEnd = src.indexOf('\n', lineStart)
        val out = apply(src, KotlinFormatter.reformat("Test.kt", src, FormatStyle.GOOGLE, lineStart, lineEnd))
        assertTrue("selected line was re-indented: <$out>") { out.contains("\n  val x = 1\n") }
        assertTrue("unselected line left alone: <$out>") { out.contains("\nval y = 2\n") }
    }
}

package dev.ide.lang.jdt

import dev.ide.lang.formatting.BracePlacement
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.WrapPolicy
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.jdt.formatting.JdtFormattingService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Java formatter on Eclipse JDT's own [org.eclipse.jdt.core.formatter.CodeFormatter], driven with a
 * Google-style option map. Verifies the visually dominant rules (2-space indent, end-of-line braces),
 * idempotency (a second pass is a no-op), and the preset switch (Android = 4 spaces). Uses [runSync] +
 * [StubFile] from TestSupport so no coroutine runtime is needed.
 */
class JdtFormattingServiceTest {

    private val svc = JdtFormattingService("17")

    private fun apply(src: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(src)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    private fun fmt(src: String, style: FormatStyle = FormatStyle.GOOGLE): String =
        apply(src, runSync { svc.format(StubFile("/A.java", src), src, style) })

    @Test
    fun formatsToGoogleStyle() {
        val out = fmt("class A{int f(){return 1;}}")
        assertTrue("type brace on the same line: <$out>") { out.contains("class A {") }
        assertTrue("members 2-space indented: <$out>") { out.contains("\n  int f() {") }
        assertTrue("body 4-space (two levels) indented: <$out>") { out.contains("\n    return 1;") }
    }

    @Test
    fun alreadyFormattedIsNoOp() {
        val once = fmt("class A{int f(){return 1;}}")
        assertEquals(once, fmt(once))
        assertTrue("a formatted buffer yields no edits") {
            runSync { svc.format(StubFile("/A.java", once), once, FormatStyle.GOOGLE) }.isEmpty()
        }
    }

    @Test
    fun androidPresetUsesFourSpaces() {
        val out = fmt("class A{int x;}", FormatStyle.ANDROID)
        assertTrue("Android preset indents members with 4 spaces: <$out>") { out.contains("\n    int x;") }
    }

    @Test
    fun braceNextLinePlacesBraceOnItsOwnLine() {
        val style = FormatStyle.GOOGLE.copy(styleId = "custom", bracePlacement = BracePlacement.NEXT_LINE)
        val out = fmt("class A{int x;}", style)
        assertTrue("type brace moved to the next line: <$out>") { out.contains("class A\n{") }
    }

    @Test
    fun wrappingPolicyBreaksLongCalls() {
        val src = "class A{ void m(){ foo(aaaa, bbbb, cccc, dddd, eeee, ffff); } }"
        val wrapped = fmt(src, FormatStyle.GOOGLE.copy(styleId = "custom", maxLineLength = 30, wrapMethodArguments = WrapPolicy.ONE_PER_LINE))
        val flat = fmt(src, FormatStyle.GOOGLE.copy(styleId = "custom", maxLineLength = 200, wrapMethodArguments = WrapPolicy.NEVER))
        assertTrue("one-per-line wrapping yields more lines than no-split: <$wrapped>") { wrapped.lines().size > flat.lines().size }
    }

    @Test
    fun blankLinesBeforeMethodApply() {
        val src = "class A{ void a(){} void b(){} }"
        val out = fmt(src, FormatStyle.GOOGLE.copy(styleId = "custom", blankLinesBeforeMethod = 2))
        assertTrue("two blank lines kept before a method: <$out>") { out.contains("\n\n\n") }
    }

    @Test
    fun spacingTogglesApply() {
        val style = FormatStyle.GOOGLE.copy(
            styleId = "custom",
            spaceAroundOperators = false,
            spaceWithinParens = true,
        )
        val out = fmt("class A{int f(int a){return a+1;}}", style)
        assertTrue("operators tightened: <$out>") { out.contains("a+1") }
        assertTrue("inner-paren spaces inserted: <$out>") { out.contains("( int a )") }
    }
}

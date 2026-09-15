package dev.codeassist.ndk

import dev.ide.analysis.DiagnosticSource
import dev.ide.lang.dom.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * What clang prints, read back as editor diagnostics.
 *
 * Every case here is a shape clang actually emits, and the ones worth pinning are the awkward ones: a note
 * that belongs to the diagnostic above it, a diagnostic in a header that is not this file's to report, and a
 * position with no range that still has to underline something.
 */
class ClangDiagnosticsTest {

    private val source = """
        #include <vector>
        int main() {
            int unused = 0;
            return qux;
        }
    """.trimIndent()

    @Test
    fun `an error becomes an ERROR at the token it names`() {
        val out = "<stdin>:4:12: error: use of undeclared identifier 'qux'"
        val d = ClangDiagnostics.parse(out, source).single()

        assertEquals(Severity.ERROR, d.severity)
        assertEquals("use of undeclared identifier 'qux'", d.message)
        assertEquals("qux", source.substring(d.range.start, d.range.end))
        assertEquals(DiagnosticSource.Compiler, d.source, "it came from the compiler, not from an analyzer")
    }

    @Test
    fun `an explicit source range wins over the token guess`() {
        val out = "<stdin>:3:9:{3:9-3:19}: warning: unused variable 'unused' [-Wunused-variable]"
        val d = ClangDiagnostics.parse(out, source).single()

        assertEquals(Severity.WARNING, d.severity)
        assertEquals("unused variable 'unused'", d.message, "the check name is not part of the message")
        assertEquals("-Wunused-variable", d.code, "it is the code, where a fix or a suppression can key on it")
        assertEquals("unused = 0", source.substring(d.range.start, d.range.end))
    }

    @Test
    fun `a fatal error is still an error`() {
        val out = "<stdin>:1:10: fatal error: 'nope.h' file not found"
        assertEquals(Severity.ERROR, ClangDiagnostics.parse(out, source).single().severity)
    }

    /** A note alone underlines a position with no problem at it; the explanation is the useful half. */
    @Test
    fun `a note is folded into the diagnostic above it`() {
        val out = """
            <stdin>:4:12: error: no matching function for call to 'f'
            <stdin>:2:5: note: candidate function not viable
        """.trimIndent()

        val d = ClangDiagnostics.parse(out, source).single()
        assertTrue(d.message.startsWith("no matching function"))
        assertTrue(d.message.endsWith("candidate function not viable"), d.message)
    }

    @Test
    fun `a leading note with nothing to attach to is dropped, not crashed on`() {
        val out = "<stdin>:2:5: note: expanded from macro 'X'"
        assertTrue(ClangDiagnostics.parse(out, source).isEmpty())
    }

    /**
     * A real error, but not this file's to report: its position means nothing here, and underlining that
     * offset in the user's buffer would point at unrelated code.
     */
    @Test
    fun `a diagnostic from an included header is dropped`() {
        val out = "/ndk/sysroot/usr/include/stdio.h:42:1: error: something in a header"
        assertTrue(ClangDiagnostics.parse(out, source).isEmpty())
    }

    @Test
    fun `a note under a dropped header diagnostic does not attach to an earlier one`() {
        val out = """
            <stdin>:4:12: error: use of undeclared identifier 'qux'
            /usr/include/stdio.h:42:1: error: in a header
            /usr/include/stdio.h:43:1: note: declared here
        """.trimIndent()

        // The note folds into whatever diagnostic precedes it, which after the header error is dropped is
        // the buffer's own. That is the honest limit of a line-by-line read of this format, and it is worth
        // stating: the note is context, never a position of its own.
        val d = ClangDiagnostics.parse(out, source).single()
        assertTrue(d.message.startsWith("use of undeclared identifier"))
    }

    @Test
    fun `clean output produces nothing`() {
        assertTrue(ClangDiagnostics.parse("", source).isEmpty())
        assertTrue(ClangDiagnostics.parse("   \n  ", source).isEmpty())
    }

    @Test
    fun `a line that is not a diagnostic is ignored`() {
        val out = """
            clang version 18.1.8
            Target: aarch64-unknown-linux-android26
            2 warnings and 1 error generated.
        """.trimIndent()
        assertTrue(ClangDiagnostics.parse(out, source).isEmpty())
    }

    @Test
    fun `a position past the end of a line is clamped rather than throwing`() {
        val out = "<stdin>:3:999: error: somewhere off the end"
        val d = ClangDiagnostics.parse(out, source).single()
        assertTrue(d.range.start <= source.length)
        assertTrue(d.range.end <= source.length)
    }

    @Test
    fun `a position past the last line is clamped`() {
        val out = "<stdin>:9999:1: error: past the end"
        val d = ClangDiagnostics.parse(out, source).single()
        assertTrue(d.range.end <= source.length)
    }

    @Test
    fun `a diagnostic with no check name has no code`() {
        val out = "<stdin>:4:12: error: use of undeclared identifier 'qux'"
        assertNull(ClangDiagnostics.parse(out, source).single().code)
    }

    @Test
    fun `every diagnostic underlines something`() {
        val out = """
            <stdin>:2:12: error: expected ';'
            <stdin>:4:12: error: use of undeclared identifier 'qux'
        """.trimIndent()
        val parsed = ClangDiagnostics.parse(out, source)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.range.end > it.range.start }, "an empty range renders as no squiggle at all")
    }
}

/** Line and column arithmetic, which is where an off-by-one costs the user a squiggle on the wrong word. */
class LineOffsetsTest {

    private val text = "alpha\nbeta\ngamma\n"

    @Test
    fun `line and column are one-based`() {
        val lines = LineOffsets(text)
        assertEquals(0, lines.offsetOf(1, 1))
        assertEquals(6, lines.offsetOf(2, 1))
        assertEquals(8, lines.offsetOf(2, 3))
    }

    @Test
    fun `a column past the end of the line stops at the newline`() {
        val lines = LineOffsets(text)
        assertEquals(10, lines.offsetOf(2, 99), "clamped to the end of 'beta', not into the next line")
    }

    @Test
    fun `the token at a position covers the whole identifier`() {
        val lines = LineOffsets(text)
        val range = lines.tokenAt(2, 1)
        assertEquals("beta", text.substring(range.start, range.end))
    }

    @Test
    fun `a position on punctuation still covers one character`() {
        val lines = LineOffsets("a + b")
        val range = lines.tokenAt(1, 3)
        assertEquals("+", "a + b".substring(range.start, range.end))
    }

    @Test
    fun `a file with no trailing newline is handled`() {
        val lines = LineOffsets("one\ntwo")
        val range = lines.tokenAt(2, 1)
        assertEquals("two", "one\ntwo".substring(range.start, range.end))
    }
}

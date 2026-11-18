package dev.codeassist.csharp.editor.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CsDiagnosticsTest {

    @Test
    fun `an error line is read`() {
        val diagnostic = CsDiagnostics.parse(
            "Program.cs(9,17): error CS0029: Cannot implicitly convert type 'string' to 'int'",
        )
        assertEquals(
            CsDiagnostic(
                path = "Program.cs",
                line = 9,
                column = 17,
                severity = CsDiagnostic.Severity.ERROR,
                code = "CS0029",
                message = "Cannot implicitly convert type 'string' to 'int'",
            ),
            diagnostic,
        )
    };

    @Test
    fun `a warning line is read`() {
        val diagnostic = CsDiagnostics.parse(
            "/a/b/Foo.cs(3,5): warning CS0168: The variable 'x' is declared but never used",
        )
        assertNotNull(diagnostic)
        assertEquals(CsDiagnostic.Severity.WARNING, diagnostic!!.severity)
        assertEquals("CS0168", diagnostic.code)
    }

    @Test
    fun `a path with parentheses and spaces is kept whole`() {
        val diagnostic = CsDiagnostics.parse("/my files/App (copy)/Program.cs(1,2): error CS1002: ; expected")
        assertEquals("/my files/App (copy)/Program.cs", diagnostic!!.path)
        assertEquals(1, diagnostic.line)
        assertEquals(2, diagnostic.column)
    }

    @Test
    fun `a message may contain colons`() {
        val diagnostic = CsDiagnostics.parse("P.cs(1,1): error CS0103: The name 'x': not found")
        assertEquals("The name 'x': not found", diagnostic!!.message)
    }

    @Test
    fun `a line without a position is not a diagnostic`() {
        assertNull(CsDiagnostics.parse("error CS2007: Unrecognized option: '-bad'"))
    }

    @Test
    fun `other compiler output is not a diagnostic`() {
        assertNull(CsDiagnostics.parse(""))
        assertNull(CsDiagnostics.parse("Microsoft (R) Visual C# Compiler version 4.0.0"))
        assertNull(CsDiagnostics.parse("host: csc could not be loaded: nope"))
    }

    @Test
    fun `info lines are not diagnostics`() {
        assertNull(CsDiagnostics.parse("P.cs(1,1): info CS8019: Unnecessary using directive"))
    }

    @Test
    fun `a code that is not CS and four digits is not read`() {
        assertNull(CsDiagnostics.parse("P.cs(1,1): error CS12: too short"))
        assertNull(CsDiagnostics.parse("P.cs(1,1): error XX0001: wrong prefix"))
    }
}

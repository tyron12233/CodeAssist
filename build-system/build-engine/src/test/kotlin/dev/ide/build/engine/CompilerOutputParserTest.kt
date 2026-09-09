package dev.ide.build.engine

import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilerOutputParserTest {

    private fun parseAll(source: String, vararg lines: String): List<BuildDiagnostic> {
        val out = ArrayList<BuildDiagnostic>()
        val p = CompilerOutputParser(source)
        for (l in lines) p.accept(l) { out += it }
        p.flush { out += it }
        return out
    }

    @Test fun gnuKotlincFormatWithColumn() {
        val d = parseAll("kotlin", "/src/Foo.kt:12:5: error: unresolved reference: foo").single()
        assertEquals(BuildSeverity.ERROR, d.severity)
        assertEquals("unresolved reference: foo", d.message)
        assertEquals("/src/Foo.kt", d.location?.path)
        assertEquals(12, d.location?.line)
        assertEquals(5, d.location?.column)
    }

    @Test fun gnuJavacWarningNoColumn() {
        val d = parseAll("java", "/src/Foo.java:3: warning: [deprecation] uses a deprecated API").single()
        assertEquals(BuildSeverity.WARNING, d.severity)
        assertEquals(3, d.location?.line)
        assertEquals(-1, d.location?.column)
    }

    @Test fun ecjBlockFormatLocatesAndExtractsMessage() {
        val d = parseAll(
            "ecj",
            "----------",
            "1. ERROR in /src/Foo.java (at line 5)",
            "\tSystem.out.prin(x);",
            "\t           ^^^^",
            "The method prin(int) is undefined for the type PrintStream",
            "----------",
            "1 problem (1 error)",
        )
        // The "1 problem (1 error)" summary has no error/warning token → not a diagnostic.
        val problem = d.single()
        assertEquals(BuildSeverity.ERROR, problem.severity)
        assertEquals("/src/Foo.java", problem.location?.path)
        assertEquals(5, problem.location?.line)
        assertEquals("The method prin(int) is undefined for the type PrintStream", problem.message)
        assertEquals("System.out.prin(x);", problem.detail)
    }

    @Test fun streamsPerProblemAcrossBackToBackBlocks() {
        val out = ArrayList<BuildDiagnostic>()
        val p = CompilerOutputParser("ecj")
        // Two problems with no intervening rule line — a new header closes the previous block.
        listOf(
            "1. ERROR in /a/A.java (at line 1)",
            "\tbad();",
            "\t^^^",
            "first message",
            "2. WARNING in /a/B.java (at line 2)",
            "\talsoBad();",
            "\t^^^",
            "second message",
            "----------",
        ).forEach { line -> p.accept(line) { out += it } }
        p.flush { out += it }
        assertEquals(2, out.size)
        assertEquals(BuildSeverity.ERROR, out[0].severity)
        assertEquals("first message", out[0].message)
        assertEquals(BuildSeverity.WARNING, out[1].severity)
        assertEquals("/a/B.java", out[1].location?.path)
    }

    @Test fun unlocatedErrorStillSurfaced() {
        val d = parseAll("kotlin", "error: no main manifest attribute").single()
        assertEquals(BuildSeverity.ERROR, d.severity)
        assertNull(d.location)
    }

    @Test fun pureChatterIsIgnored() {
        val d = parseAll("d8", "Compiling 42 classes", "Done in 1.2s")
        assertTrue(d.isEmpty())
    }

    @Test fun reportToolDiagnosticsStreamsToContext() {
        val collected = ArrayList<BuildDiagnostic>()
        val ctx = SimpleTaskContext(onDiagnostic = { collected += it })
        ctx.reportToolDiagnostics(
            "aapt2",
            listOf("/res/layout/a.xml:7: error: resource not found"),
            DiagnosticKind.RESOURCE,
        )
        val d = collected.single()
        assertEquals(DiagnosticKind.RESOURCE, d.kind)
        assertEquals("aapt2", d.source)
        assertEquals(7, d.location?.line)
    }
}

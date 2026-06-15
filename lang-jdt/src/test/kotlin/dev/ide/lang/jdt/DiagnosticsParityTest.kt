package dev.ide.lang.jdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parity guard for the analysis optimization: `analyze()` and the IDE's compiler diagnostic provider now
 * source diagnostics from the cached low-level resolve ([JdtSourceAnalyzer.diagnose]) instead of a
 * disk-scanning binding DOM parse ([JdtSourceAnalyzer.parse] → `.diagnostics`). Both are ecj run with the
 * same `JavaCore` options, so the problem set must be identical — only the entry point (and its cost)
 * differs. This test asserts that equivalence on a spread of samples; if the two ever diverge, the
 * "optimization" has changed what the user sees and must be reconciled or reverted.
 */
class DiagnosticsParityTest {

    private fun <T> withAnalyzer(block: (JdtSourceAnalyzer, java.nio.file.Path) -> T): T {
        val (analyzer, dir) = workspaceWith()
        return try { block(analyzer, dir) } finally { dir.toFile().deleteRecursively() }
    }

    private fun domMessages(analyzer: JdtSourceAnalyzer, dir: java.nio.file.Path, code: String): List<String> =
        analyzer.parse(StubFile(dir.resolve("app/T.java").toString(), code), code).diagnostics
            .map { "${it.severity}@${it.range.start}:${it.message}" }.sorted()

    private fun lowLevelMessages(analyzer: JdtSourceAnalyzer, dir: java.nio.file.Path, code: String): List<String> =
        analyzer.diagnose(StubFile(dir.resolve("app/T.java").toString(), code), code)
            .map { "${it.severity}@${it.range.start}:${it.message}" }.sorted()

    private val samples = listOf(
        "clean" to "package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1); } }",
        "type-mismatch" to "package app; class T { void m() { String s = 5; } }",
        "missing-semicolon" to "package app; class T { void m() { int x = 1 } }",
        "broken-inline-lambda" to "package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1) } }",
        "broken-block-lambda" to "package app; class T { void m() { java.util.List.of(1).stream().map(vp -> { return 1; }) } }",
        "undefined-method" to "package app; class T { void m() { nonexistentMethod(); } }",
        "undefined-variable" to "package app; class T { void m() { int y = undefinedVar; } }",
        "mixed-broken-and-real" to "package app; class T { void m() { String s = 5; java.util.List.of(1).stream().map(it -> 1) } }",
        "unused-local" to "package app; class T { void m() { int unused = 42; } }",
        "unreachable-code" to "package app; class T { int m() { return 1; int dead = 2; } }",
        "definite-assignment" to "package app; class T { int m() { int z; return z; } }",
        "two-type-errors" to "package app; class T { void m() { String a = 1; Integer b = \"x\"; } }",
    )

    @Test
    fun lowLevelDiagnosticsMatchTheDomParse() {
        withAnalyzer { analyzer, dir ->
            val mismatches = StringBuilder()
            for ((name, code) in samples) {
                val dom = domMessages(analyzer, dir, code)
                val low = lowLevelMessages(analyzer, dir, code)
                if (dom != low) {
                    mismatches.append("\n[$name]\n  DOM       : $dom\n  low-level : $low\n")
                }
            }
            assertTrue(mismatches.isEmpty(), "low-level diagnose() diverged from the DOM parse:$mismatches")
        }
    }

    // The DiagnosticsReproTest invariants, asserted directly against the NEW low-level path (those tests
    // exercise the DOM parse; these confirm the in-memory path keeps the same broken-statement filtering).

    @Test
    fun lowLevelSuppressesSpuriousErrorInBrokenLambda() {
        withAnalyzer { analyzer, dir ->
            val msgs = analyzer.diagnose(StubFile(dir.resolve("app/T.java").toString(),
                "package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1) } }"),
                "package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1) } }").map { it.message }
            assertFalse(msgs.any { "is not applicable for the arguments" in it }, "spurious type error must be suppressed: $msgs")
            assertTrue(msgs.any { "insert \";\"" in it }, "missing-semicolon syntax error must remain: $msgs")
        }
    }

    @Test
    fun lowLevelKeepsGenuineTypeError() {
        withAnalyzer { analyzer, dir ->
            val code = "package app; class T { void m() { String s = 5; } }"
            val msgs = analyzer.diagnose(StubFile(dir.resolve("app/T.java").toString(), code), code).map { it.message }
            assertTrue(msgs.any { "Type mismatch" in it }, "real type error must be reported: $msgs")
        }
    }

    @Test
    fun lowLevelCleanFileHasNoDiagnostics() {
        withAnalyzer { analyzer, dir ->
            val code = "package app; class T { void m() { java.util.List.of(1).stream().map(it -> 1); } }"
            assertEquals(emptyList(), analyzer.diagnose(StubFile(dir.resolve("app/T.java").toString(), code), code).map { it.message })
        }
    }
}

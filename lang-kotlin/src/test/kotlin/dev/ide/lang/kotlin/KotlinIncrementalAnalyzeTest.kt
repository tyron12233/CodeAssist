package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The incremental (scoped) analyze path must be INDISTINGUISHABLE from a full re-analyze. A body-only edit of
 * one function re-analyzes just that function and reuses every other declaration's diagnostics, re-anchored to
 * its shifted offset — so this compares scoped output (analyzer that saw v1 then v2) against full output (a
 * fresh analyzer that sees only v2), for several edit shapes, asserting the diagnostic sets are identical.
 */
class KotlinIncrementalAnalyzeTest {

    private fun key(d: Diagnostic) = "${d.range.start}-${d.range.end}:${d.severity}:${d.code}:${d.message}"

    private fun diagsOf(srcDir: Path, file: String, text: String, analyzer: KotlinSourceAnalyzer): List<String> {
        val doc = SnippetDoc(text, DiskFile(srcDir.resolve(file)))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file).diagnostics
        }.map(::key).sorted()
    }

    /** v1 → v2 on one analyzer (scoped) must equal v2 on a fresh analyzer (full). */
    private fun assertScopedEqualsFull(v1: String, v2: String) {
        val srcDir = tempProject(mapOf("Big.kt" to v2)) // disk = final version (the model resolves against it)
        val incremental = KotlinSourceAnalyzer(fakeContext(srcDir))
        diagsOf(srcDir, "Big.kt", v1, incremental)            // seed the cache with v1
        val scoped = diagsOf(srcDir, "Big.kt", v2, incremental) // v2 → exercises the scoped/reuse path

        val full = diagsOf(srcDir, "Big.kt", v2, KotlinSourceAnalyzer(fakeContext(srcDir))) // fresh → full walk
        assertEquals(full, scoped, "scoped analyze diverged from full analyze")
    }

    @Test
    fun bodyEditThatRemovesADiagnosticMatchesFull() {
        val v1 = file(bodyA = "val x = bogusUnresolved", bodyB = "val unusedB = 1")
        val v2 = file(bodyA = "val x = 1", bodyB = "val unusedB = 1") // fixed the unresolved ref in A
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun bodyEditThatAddsADiagnosticMatchesFull() {
        val v1 = file(bodyA = "val x = 1", bodyB = "val unusedB = 1")
        val v2 = file(bodyA = "val x = stillMissing", bodyB = "val unusedB = 1") // introduced an unresolved ref
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun bodyEditThatShiftsLaterDeclarationsMatchesFull() {
        // Growing A's body shifts B and C downward — the reused diagnostics for B/C must be re-anchored.
        val v1 = file(bodyA = "val x = 1", bodyB = "var couldBeVal = 2")
        val v2 = file(bodyA = "val x = 1\n    val y = 2\n    val z = nopeMissing\n    println(y)", bodyB = "var couldBeVal = 2")
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun bodyEditChangingCrossDeclarationUsageMatchesFull() {
        // screenB's body starts referencing the private helper → helper's "unused" warning must clear, even
        // though helper's own text is unchanged (a whole-file check that the scoped path must not stale-reuse).
        val withHelper = { bodyB: String ->
            "package demo\nprivate fun helper() {}\nfun screenA() {}\nfun screenB() {\n    $bodyB\n}\n"
        }
        assertScopedEqualsFull(withHelper("val x = 1"), withHelper("helper()"))
        assertScopedEqualsFull(withHelper("helper()"), withHelper("val x = 1"))
    }

    @Test
    fun signatureEditFallsBackToFull() {
        // Changing A's parameter list is a header change → must NOT scope; still must equal full.
        val v1 = file(sigA = "fun screenA(seed: Int)", bodyA = "val x = 1", bodyB = "val q = 1")
        val v2 = file(sigA = "fun screenA(seed: Int, extra: String)", bodyA = "val x = 1", bodyB = "val q = 1")
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun importEditFallsBackToFull() {
        val v1 = "package demo\nimport kotlin.math.PI\n" + body()
        val v2 = "package demo\n" + body() // removed the import → file-level + every decl's resolution may change
        assertScopedEqualsFull(v1, v2)
    }

    companion object {
        private fun body() = KotlinIncrementalAnalyzeTest().file()
    }

    /** A small multi-declaration file with pluggable pieces, so each test perturbs exactly one spot. */
    private fun file(
        sigA: String = "fun screenA(seed: Int)",
        bodyA: String = "val x = 1",
        bodyB: String = "val y = 1",
    ): String = buildString {
        appendLine("package demo")
        appendLine()
        appendLine("data class Model(val id: String, val value: Int)")
        appendLine()
        appendLine("$sigA {")
        appendLine("    $bodyA")
        appendLine("}")
        appendLine()
        appendLine("fun screenB() {")
        appendLine("    $bodyB")
        appendLine("}")
        appendLine()
        appendLine("fun screenC(): Int {")
        appendLine("    return 42")
        appendLine("}")
    }
}

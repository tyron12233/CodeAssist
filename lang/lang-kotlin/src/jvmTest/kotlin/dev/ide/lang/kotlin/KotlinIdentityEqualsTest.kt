package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `===` on an operand with no identity is the compiler's FORBIDDEN_IDENTITY_EQUALS: a value class whatever the
 * other side, or a primitive against another final type. The same primitive on both sides, a primitive against
 * its own supertype and a `null` operand are the compiler's warnings or fine, and stay clean here.
 */
class KotlinIdentityEqualsTest {

    private fun forbidden(code: String): List<String> {
        val dir = tempProject(mapOf("Use.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        try {
            val doc = SnippetDoc(code, DiskFile(dir.resolve("Use.kt")))
            return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                .filter { it.severity == Severity.ERROR && it.code == KotlinDiagnosticCodes.FORBIDDEN_IDENTITY_EQUALS }
                .map { code.substring(it.range.start, it.range.end) }
        } finally {
            analyzer.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun impossibleIsChecks() {
        val code = "package demo\nclass A\nclass B\nopen class C\ninterface I\nopen class D : C()\n" +
            "fun f(a: A, c: C, i: I, s: String) {\n  a is B; a !is B; c is D; c is I; a is I; s is A; c is A?\n}\n"
        val dir = tempProject(mapOf("Use.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        val got = try {
            val doc = SnippetDoc(code, DiskFile(dir.resolve("Use.kt")))
            runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                .filter { it.severity == Severity.ERROR && it.code == KotlinDiagnosticCodes.IMPOSSIBLE_IS_CHECK }
                .map { code.substring(it.range.start, it.range.end) }
        } finally {
            analyzer.dispose()
            dir.toFile().deleteRecursively()
        }
        // `c is I` can hold for a subclass of C implementing I; `a is I` cannot, A being final. `c is A?` is
        // impossible too: `c` is never null, so the `?` buys nothing (K2's classVsClassIsCheckNullable.kt).
        assertEquals(listOf("a is B", "a !is B", "a is I", "s is A", "c is A?"), got, "got $got")
    }

    @Test
    fun valueClassesAndMismatchedPrimitives() {
        val got = forbidden(
            "package demo\n@JvmInline value class Id(val v: Int)\n" +
                "fun f(a: Id, b: Id?, any: Any, x: Long, y: Int, n: Number, s: String) {\n" +
                "  a === b; b === null; any === a\n  x === 3; y === s; y === y; y === n; y === any\n}\n"
        )
        assertEquals(listOf("a === b", "any === a", "x === 3", "y === s"), got, "got $got")
    }
}

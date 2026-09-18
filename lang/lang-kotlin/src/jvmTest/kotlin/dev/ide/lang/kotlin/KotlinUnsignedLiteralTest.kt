package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The type of an integer literal carrying the UNSIGNED suffix.
 *
 * Literal typing read the `L` suffix and the decimal value and nothing else, so `3uL` was a `Long` (it does
 * end with `L`) and `3u` was an `Int` (`"3u".toLongOrNull()` is null, and the branch defaults). Assigning
 * either to its own type was then reported as a type mismatch against the literal's own spelling. Found by
 * sweeping the checkers over the Kotlin standard library, whose `_UArrays.kt` is written in them.
 */
class KotlinUnsignedLiteralTest {

    private fun errors(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    @Test
    fun anUnsignedLiteralTypesAsUIntAndAnUnsignedLongAsULong() {
        val diags = errors(
            "Unsigned.kt",
            """
            package demo
            val a: UInt = 3u
            val b: UInt = 3U
            val c: ULong = 3uL
            val d: ULong = 3UL
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "each literal is assigned to its own type; got ${diags.map { it.message }}")
    }

    @Test
    fun anUnsignedHexLiteralWidensAtUIntsCeilingNotIntsIntoULong() {
        val diags = errors(
            "UnsignedHex.kt",
            """
            package demo
            val small: UInt = 0xFFu
            val full: UInt = 0xFFFFFFFFu
            val wide: ULong = 0xFFFFFFFFFFu
            val binary: UInt = 0b1010u
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "0xFFFFFFFF overflows Int and fits UInt; got ${diags.map { it.message }}")
    }

    @Test
    fun theSignedLiteralsAreUnchanged() {
        val diags = errors(
            "Signed.kt",
            """
            package demo
            val i: Int = 3
            val l: Long = 3L
            val big: Long = 0xFFFFFFFF
            val f: Float = 1.5f
            val d: Double = 1.5
            val hex: Int = 0xFF
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "got ${diags.map { it.message }}")
    }

    /** Underscores are separators, so a suffix behind one is still the suffix. */
    @Test
    fun aGroupedLiteralKeepsItsSuffix() {
        val diags = errors(
            "Grouped.kt",
            "package demo\nval n: UInt = 1_000_000u\nval m: ULong = 1_000_000_000_000uL\n",
        )
        assertTrue(diags.isEmpty(), "got ${diags.map { it.message }}")
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

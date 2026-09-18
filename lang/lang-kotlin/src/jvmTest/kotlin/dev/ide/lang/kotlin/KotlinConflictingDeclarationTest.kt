package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `kt.conflictingDeclaration` — what counts as the same signature.
 *
 * Overloads are keyed by their parameter types, so anything the key leaves out silently merges two legal
 * declarations into one "conflict". `vararg` was left out: `maxOf(a: Int, b: Int)` and
 * `maxOf(a: Int, vararg other: Int)` both keyed as `(Int,Int)`. Found by sweeping the checkers over the
 * Kotlin standard library, whose generated `_Comparisons.kt` pairs every numeric `minOf`/`maxOf` that way.
 */
class KotlinConflictingDeclarationTest {

    private fun conflicts(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == KotlinDiagnosticCodes.CONFLICTING_DECLARATION }
    }

    @Test
    fun aVarargOverloadDoesNotConflictWithTheFixedArityOne() {
        val diags = conflicts(
            "Varargs.kt",
            "package demo\nfun maxOf(a: Int, b: Int): Int = a\nfun maxOf(a: Int, vararg other: Int): Int = a\n",
        )
        assertTrue(diags.isEmpty(), "vararg is part of the signature; got ${diags.map { it.message }}")
    }

    @Test
    fun differingOnlyInParameterNamesIsStillAConflict() {
        val diags = conflicts(
            "SameShape.kt",
            "package demo\nfun f(a: Int): Int = a\nfun f(b: Int): Int = b\n",
        )
        val d = assertNotNull(diags.firstOrNull(), "same parameter types IS a conflict")
        assertTrue(d.message.contains("'f'"), d.message)
    }

    @Test
    fun twoVarargOverloadsOfTheSameTypeStillConflict() {
        val diags = conflicts(
            "TwoVarargs.kt",
            "package demo\nfun g(vararg a: Int) {}\nfun g(vararg b: Int) {}\n",
        )
        assertTrue(diags.isNotEmpty(), "both are `(vararg Int)`")
    }

    @Test
    fun differentParameterTypesAreOverloads() {
        val diags = conflicts(
            "Overloads.kt",
            "package demo\nfun h(a: Int) {}\nfun h(a: String) {}\nfun h(a: Int, b: Int) {}\n",
        )
        assertTrue(diags.isEmpty(), "got ${diags.map { it.message }}")
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

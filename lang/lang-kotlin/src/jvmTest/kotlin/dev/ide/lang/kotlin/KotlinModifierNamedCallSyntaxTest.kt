package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A name that is also a soft modifier keyword (`error`, `value`, `inline`, `private`, `context`) where the
 * parser has just finished a declaration: an enum entry with arguments or a body (KT-90707), and a call on the
 * line after a local class (KT-90782). Both were reported as syntax errors on code the compiler accepts, found
 * by sweeping the checkers over the compiler's own diagnostic test data. The fix is upstream's, backported into
 * the vendored grammar; `ModifierNamedCallParseTest` in `:kotlin-syntax` pins the tree, this pins what the
 * editor shows, through the lazy parse and on-demand body expansion it actually uses.
 */
class KotlinModifierNamedCallSyntaxTest {

    private fun errors(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR }
    }

    @Test
    fun enumEntriesNamedLikeModifiers() {
        val diags = errors(
            "ModifierEntries.kt",
            """
            package demo

            enum class E1 { error, value, inline, ; }

            enum class E2(val code: String) {
                correct("correct"),
                error("error"),
                value("value"),
                inline("inline"),
                ;
            }

            enum class E3 { error {}, value {}, inline {}, ; }

            enum class E4(val code: String) {
                error("error") {},
                value("value") {},
                inline("inline") {},
                ;
            }

            fun test(): String {
                E1.error
                E3.inline
                return E2.error.code + E4.value.code
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "every entry parses and resolves; got ${diags.map { it.code + " " + it.message }}")
    }

    @Test
    fun aCallNamedLikeAModifierAfterALocalClass() {
        val diags = errors(
            "LocalClassThenCall.kt",
            """
            package demo

            annotation class Ann

            fun value(f: () -> Unit) {}
            fun private(s: String, f: () -> Unit = {}) {}
            fun context(s: String) {}

            fun a() {
                abstract class C
                value {}
            }

            fun b() {
                data class C(val x: Int)
                private("boom")
            }

            fun c() {
                class C
                context("boom")
            }

            fun d() {
                class C
                private("boom") {}
            }

            fun e() {
                class C1
                private constructor()

                class C2
                @Ann constructor()
            }
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == KotlinDiagnosticCodes.SYNTAX },
            "each call is a statement of its own, and a `constructor` still belongs to the class; got ${diags.map { it.message }}",
        )
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

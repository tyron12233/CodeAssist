package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Overload resolution: the OVERLOAD_RESOLUTION_AMBIGUITY diagnostic (two-plus applicable overloads with no
 * most-specific one) and its conservative gates — a call that a most-specific overload resolves, or that
 * involves lambdas / generics / unknown types, must NOT be flagged (no false positives).
 */
class KotlinOverloadResolutionTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }
    private fun b(fn: String) = "package demo\n$fn\n"

    @Test
    fun symmetricOverloadsAreAmbiguous() {
        val d = diagnose("O1.kt", b("fun f(x: Int, y: Any) {}\nfun f(x: Any, y: Int) {}\nfun g() { f(1, 1) }"))
        assertTrue(d.any { it.code == "kt.overloadAmbiguity" }, "f(Int,Any)/f(Any,Int) applied to (1,1) is ambiguous; got $d")
    }

    @Test
    fun nullArgToTwoNullableOverloadsIsAmbiguous() {
        val d = diagnose("O2.kt", b("fun p(x: String?) {}\nfun p(x: Int?) {}\nfun g() { p(null) }"))
        assertTrue(d.any { it.code == "kt.overloadAmbiguity" }, "p(null) matches both nullable overloads; got $d")
    }

    @Test
    fun mostSpecificOverloadIsNotAmbiguous() {
        // `h(String)` is more specific than `h(Any)` → resolves, not ambiguous.
        val d = diagnose("O3.kt", b("fun h(x: Any) {}\nfun h(x: String) {}\nfun g() { h(\"a\") }"))
        assertTrue(d.none { it.code == "kt.overloadAmbiguity" }, "a most-specific overload resolves the call; got $d")
    }

    @Test
    fun distinctArityIsNotAmbiguous() {
        val d = diagnose("O4.kt", b("fun k(x: Int) {}\nfun k(x: String) {}\nfun g() { k(1) }"))
        assertTrue(d.none { it.code == "kt.overloadAmbiguity" }, "only one overload is applicable; got $d")
    }

    @Test
    fun lambdaOverloadsAreNotAmbiguous() {
        // Functional parameters are not compared for specificity → conservatively never flagged.
        val d = diagnose("O5.kt", b("fun w(b: () -> Unit) {}\nfun w(b: (Int) -> Unit) {}\nfun g() { w { } }"))
        assertTrue(d.none { it.code == "kt.overloadAmbiguity" }, "lambda overloads must not be flagged ambiguous; got $d")
    }

    @Test
    fun genericAndSingleFunctionAreClean() {
        assertTrue(diagnose("O6.kt", b("fun <T> id(x: T): T = x\nfun g() { id(1) }")).none { it.code == "kt.overloadAmbiguity" }, "generic")
        assertTrue(diagnose("O7.kt", b("fun s(x: Int) {}\nfun g() { s(1) }")).none { it.code == "kt.overloadAmbiguity" }, "single fn")
    }

    @Test
    fun diamondSupertypeAmbiguityIsFlagged() {
        // `B` is both an `A` and an `I`; `m(A)` and `m(I)` both apply and neither parameter is more specific.
        val d = diagnose(
            "O8.kt",
            b("open class A\ninterface I\nclass B : A(), I\nfun m(x: A) {}\nfun m(x: I) {}\nfun g(x: B) { m(x) }"),
        )
        assertTrue(d.any { it.code == "kt.overloadAmbiguity" }, "B fits both unrelated supertypes → ambiguous; got $d")
    }

    @Test
    fun numericArgumentResolvesToExactOverload() {
        // Regression guard: an Int argument resolves to the Int overload, NOT a false Byte-vs-Short ambiguity from
        // the numeric-coercion excusal (the `println(anInt)` bug).
        val d = diagnose("O9.kt", b("fun f(y: Int) { println(y) }"))
        assertTrue(d.none { it.code == "kt.overloadAmbiguity" }, "println(Int) must not be ambiguous; got $d")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Generic type-argument UPPER-BOUND checks: a class type reference whose argument violates the declared bound
 * (`Box<String>` where `class Box<T : Number>`). Conservative — only definite violations (a final argument
 * outside the bound's hierarchy, or a nullable argument under a non-nullable bound); a satisfying argument, an
 * unbounded parameter, or a star projection is left alone.
 */
class KotlinGenericBoundsTest {
    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }
    private fun b(fn: String) = "package demo\nclass Box<T : Number>(val v: T)\nclass Cmp<T : CharSequence>\n$fn\n"

    @Test fun boundViolatedInTypeRefIsFlagged() {
        assertTrue(diagnose("G1.kt", b("fun f(x: Box<String>) {}")).any { it.code == "kt.upperBoundViolated" }, "String is not a Number")
        assertTrue(diagnose("G2.kt", b("fun f(x: Cmp<Int>) {}")).any { it.code == "kt.upperBoundViolated" }, "Int is not a CharSequence")
    }

    @Test fun nullableArgUnderNonNullBoundIsFlagged() {
        assertTrue(diagnose("G3.kt", b("fun f(x: Box<Int?>) {}")).any { it.code == "kt.upperBoundViolated" }, "Int? is nullable, bound Number is not")
    }

    @Test fun satisfyingBoundsAreClean() {
        val ok = listOf(
            "fun f(x: Box<Int>) {}",       // Int : Number
            "fun f(x: Box<Long>) {}",      // Long : Number
            "fun f(x: Cmp<String>) {}",    // String : CharSequence
            "fun <T : Number> g(x: Box<T>) {}", // a type parameter within the same bound
        )
        for (o in ok) assertTrue(diagnose("Gok.kt", b(o)).none { it.code == "kt.upperBoundViolated" }, "`$o` must be clean; got ${diagnose("Gok.kt", b(o))}")
    }

    @Test fun unboundedAndStarAreClean() {
        val d = diagnose("G5.kt", "package demo\nfun f(x: List<String>, y: List<*>) {}")
        assertTrue(d.none { it.code == "kt.upperBoundViolated" }, "an unbounded List / star projection is fine; got $d")
    }

    @Test fun sourceFunctionExplicitTypeArgBound() {
        assertTrue(
            diagnose("GF1.kt", "package demo\nfun <T : Number> g(): T? = null\nfun h() { val x = g<String>() }").any { it.code == "kt.upperBoundViolated" },
            "g<String>() violates the T : Number bound on a source function",
        )
        assertTrue(
            diagnose("GF2.kt", "package demo\nfun <T : Number> g(): T? = null\nfun h() { val x = g<Int>() }").none { it.code == "kt.upperBoundViolated" },
            "g<Int>() satisfies the bound",
        )
    }

    // --- generic argument-type mismatch (2026-07-07) ---

    @Test fun genericArgumentMismatchIsFlagged() {
        assertTrue(diagnose("GM1.kt", "package demo\nfun f() { val xs: List<String> = listOf(1) }").any { it.code == "kt.typeMismatch" }, "List<String> = List<Int>")
        assertTrue(diagnose("GM2.kt", "package demo\nfun f() { val m: Map<String, Int> = mapOf(1 to 2) }").any { it.code == "kt.typeMismatch" }, "Map key mismatch")
        assertTrue(diagnose("GM3.kt", "package demo\nclass Box<T>(val v: T)\nfun f() { val b: Box<String> = Box(1) }").any { it.code == "kt.typeMismatch" }, "Box<String> = Box<Int>")
    }

    @Test fun compatibleGenericArgumentsAreClean() {
        val ok = listOf(
            "fun f() { val xs: List<Any> = listOf(\"x\") }",     // covariant: String <: Any
            "fun f() { val xs: List<Number> = listOf(1) }",      // Int <: Number
            "fun f() { val xs: List<String> = listOf(\"x\") }",  // exact
            "fun f() { val xs: List<String> = emptyList() }",    // no concrete arg to clash
        )
        for (o in ok) assertTrue(diagnose("GMok.kt", "package demo\n$o").none { it.code == "kt.typeMismatch" }, "`$o` must be clean; got ${diagnose("GMok.kt", "package demo\n$o")}")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

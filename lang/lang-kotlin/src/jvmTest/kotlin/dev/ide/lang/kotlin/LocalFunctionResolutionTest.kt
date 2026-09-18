package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local (nested) function resolution — a `fun helper()` declared inside another function's body. Locals used to
 * get a name-and-kind-only symbol visible strictly BEFORE the caret, which produced a wall of false errors: any
 * call with arguments read as "Too many arguments (expected 0)", a named argument as "Cannot find a parameter
 * with this name", a recursive self-call as "Unresolved reference", and a local extension was unresolvable on
 * its own receiver. These pin the fixed behaviour, including the cases that MUST still be reported (a genuine
 * arity/type error, and Kotlin's rejection of a forward reference to a later sibling).
 */
class LocalFunctionResolutionTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun b(fn: String) = "package demo\n$fn\n"

    private fun nav(file: String, code: String): List<NavTarget> {
        val caret = code.indexOf('|')
        return analyzer.navigationTargets(DiskFile(srcDir.resolve(file)), code.removeRange(caret, caret + 1), caret, NavKind.DECLARATION)
    }

    // --- calls that must be CLEAN (the false-error wall) ---

    @Test
    fun callWithArgumentsIsClean() {
        val d = diagnose("L1.kt", b("fun f() {\n  fun g(a: Int, b: String) { println(a + b.length) }\n  g(1, \"x\")\n}"))
        assertTrue(d.isEmpty(), "a local call matching its parameters is clean; got $d")
    }

    @Test
    fun recursiveCallResolves() {
        // The callee sits INSIDE its own declaration, which the "declared before the caret" window excluded.
        val d = diagnose("L2.kt", b("fun f() {\n  fun fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)\n  println(fact(3))\n}"))
        assertTrue(d.isEmpty(), "a self-recursive local call resolves; got $d")
    }

    @Test
    fun defaultedAndVarargParametersAreHonoured() {
        val defaults = diagnose("L3.kt", b("fun f() {\n  fun g(a: Int = 1, b: Int = 2) = a + b\n  println(g())\n  println(g(5))\n}"))
        assertTrue(defaults.isEmpty(), "omitting defaulted parameters is clean; got $defaults")
        val vararg = diagnose("L4.kt", b("fun f() {\n  fun g(vararg xs: Int) = xs.size\n  println(g(1, 2, 3))\n}"))
        assertTrue(vararg.isEmpty(), "a vararg local absorbs trailing arguments; got $vararg")
    }

    @Test
    fun namedArgumentsResolveToParameters() {
        val d = diagnose("L5.kt", b("fun f() {\n  fun g(a: Int, b: Int) = a + b\n  println(g(a = 1, b = 2))\n}"))
        assertTrue(d.isEmpty(), "named arguments bind to the local's parameters; got $d")
    }

    @Test
    fun genericLocalInfersThroughItsCall() {
        val d = diagnose("L6.kt", b("fun f() {\n  fun <T> first(l: List<T>): T = l[0]\n  println(first(listOf(\"a\")).length)\n}"))
        assertTrue(d.isEmpty(), "a generic local resolves and its result types; got $d")
    }

    @Test
    fun extensionLocalResolvesOnItsReceiver() {
        val d = diagnose("L7.kt", b("fun f() {\n  fun String.twice() = this + this\n  println(\"a\".twice())\n}"))
        assertTrue(d.isEmpty(), "a local extension resolves on its receiver; got $d")
    }

    @Test
    fun localShadowsASameNamedTopLevel() {
        val d = diagnose("L8.kt", b("fun g(a: Int) { println(a) }\nfun f() {\n  fun g() { println(0) }\n  g()\n}"))
        assertTrue(d.isEmpty(), "the local shadows the top-level, so its arity is the one that applies; got $d")
    }

    @Test
    fun localInsideALambdaResolves() {
        val d = diagnose("L9.kt", b("fun f() {\n  run {\n    fun g(a: Int) = a\n    println(g(1))\n  }\n}"))
        assertTrue(d.isEmpty(), "a local declared inside a lambda body resolves; got $d")
    }

    // --- errors that must STILL be reported ---

    @Test
    fun genuineArityErrorsAreStillFlagged() {
        val d = diagnose("L10.kt", b("fun f() {\n  fun g(a: Int) { println(a) }\n  g()\n  g(1, 2)\n}"))
        assertTrue(
            d.any { it.code == "kt.argumentCount" && "required parameter" in it.message },
            "the missing required argument is reported; got $d",
        )
        assertTrue(
            d.any { it.code == "kt.argumentCount" && it.message.startsWith("Too many arguments") },
            "the extra argument is reported; got $d",
        )
    }

    @Test
    fun argumentTypeMismatchIsFlagged() {
        val d = diagnose("L11.kt", b("fun f() {\n  fun g(a: Int) { println(a) }\n  g(\"x\")\n}"))
        assertTrue(d.any { it.code == "kt.typeMismatch" }, "a wrong argument type is reported as a mismatch; got $d")
    }

    @Test
    fun inferredReturnTypeIsCheckedAtTheCallSite() {
        val d = diagnose("L12.kt", b("fun f() {\n  fun g() = \"hi\"\n  val s: Int = g()\n  println(s)\n}"))
        assertTrue(d.any { it.code == "kt.typeMismatch" }, "the expression-body return type reaches the call; got $d")
    }

    @Test
    fun forwardReferenceToALaterSiblingStaysUnresolved() {
        // Kotlin has no forward reference between sibling local functions — only self-recursion works.
        val d = diagnose("L13.kt", b("fun f() {\n  g()\n  fun g() { println(1) }\n}"))
        assertTrue(d.any { it.code == "kt.unresolved" }, "a forward reference is still unresolved; got $d")
    }

    // --- navigation + completion ---

    @Test
    fun navigationReachesTheDeclarationFromARecursiveCall() {
        val code = "package demo\nfun f() {\n  fun helper(n: Int) { hel|per(n - 1) }\n  helper(2)\n}"
        val t = nav("L14.kt", code)
        assertEquals(
            code.replace("|", "").indexOf("helper"),
            t.firstOrNull()?.offset,
            "a recursive call navigates to the local declaration; got $t",
        )
    }

    @Test
    fun completionOffersTheLocalWithItsParameters() {
        val r = runBlocking {
            analyzer.completeAtCaret(srcDir, "L15.kt", "package demo\nfun f() {\n  fun helper(a: Int) = a\n  hel|\n}")
        }
        assertTrue(
            r.items.any { it.label == "helper(a: Int)" },
            "the local completes with its real signature; got ${r.items.map { it.label }.take(10)}",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

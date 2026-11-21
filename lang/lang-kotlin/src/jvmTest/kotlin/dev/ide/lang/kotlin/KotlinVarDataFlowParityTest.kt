package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smart casts on local variables that K2's data-flow analysis makes and the editor missed (or made wrongly),
 * found by running Kotlin's compiler test data through the checkers. Each test is one rule of the data-flow
 * pass ([dev.ide.lang.kotlin.resolve.KotlinVarNullFlow]); the `...StillReported` tests pin the errors K2 keeps.
 */
class KotlinVarDataFlowParityTest {

    /** A loop forgets, on entry, only what it assigns: a check inside the body holds for the rest of that
     *  iteration, and what the loop never touches stays known (smartCasts/loops, varnotnull/forEachWithBreak). */
    @Test
    fun aVarAssignedInALoopIsNarrowedWithinTheIteration() {
        clean(
            "class S(val n: S?) { fun go() {}; fun next(): S? = n }\n" +
                "fun list(start: S): S? { var e: S? = start; for (i in 0..42) { if (e == null) break; e.go(); e = e.next() }; return e }"
        )
        clean("fun main() { var result: String? = null; var i = 0; while (result == null) { if (i == 10) result = \"x\" else i++ }; result.length }")
        clean("fun x(): Boolean = true\nfun foo(pp: String?): Int { var p = pp; while (true) { p!!.length; if (x()) break; p = null }; return p.length }")
        clean("fun f(c: Boolean) { var x: String? = \"\"; do { x!!.length; x = if (c) null else \"a\" } while (c) }")
    }

    @Test
    fun aVarAssignedInALoopIsStillReported() {
        assertCodes(
            "fun f(i: Int) { var x: String? = \"\"; x!!; do { x.length; x = null } while (i < 10) }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes(
            "fun f(i: Int) { var x: String? = \"\"; for (k in 0..i) { x.length; x = null } }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes("fun f() { var x: String? = \"\"; while (x != null) { x = null }; x.length }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
    }

    /** A lambda called in place (a stdlib scope function, a `callsInPlace` contract, an `inline` function's
     *  lambda parameter) is part of the flow where it is passed (lambdasWithContracts, cfg/inlineLambdasAreInplace). */
    @Test
    fun aLambdaCalledInPlaceIsPartOfTheFlow() {
        clean("inline fun callsInPlaceInline(x: () -> Unit) = x()\nfun f() { var x: String? = null; callsInPlaceInline { x = null }; x = \"\"; x.length }")
        clean("inline fun run2(block: () -> Unit) = block()\nfun f() { var l: String? = null; run2 { l = \"z\" }; if (l == null) { l = \"z\" }; l.isBlank() }")
        clean("fun f() { var x: String? = null; run { x = \"a\" }; x.length }")
        clean("fun f(s: String?) { var x: String? = s; x ?: return; listOf(1).forEach { x.length } }")
        clean(
            "import kotlin.contracts.*\n@OptIn(ExperimentalContracts::class)\nfun once(block: () -> Unit) { contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }; block() }\n" +
                "fun f() { var x: String? = null; once { x = \"\" }; x.length }"
        )
    }

    @Test
    fun aLambdaThatMayNotRunOrRunsLaterIsStillReported() {
        assertCodes(
            "fun f() { var x: String? = \"\"; listOf(1).forEach { x = null }; x.length }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes(
            "fun <T> any(x: T) = x\nfun f() { var x: String? = \"\"; val l = any { x = null }; x = \"\"; l(); x.length }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes(
            "fun f() { var x: String? = \"\"; val l = { x = null }; l(); x.length }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
    }

    /** A closure that may run at any time (a local class, a local function, two lambdas one call runs in either
     *  order, one whose call is resolved inside another's arguments) makes what it writes unstable, inside it
     *  too (smartCasts/variables/objectMembers, capturedByMultipleLambdas, varnotnull/varCapturedInClosure). */
    @Test
    fun aVarWrittenByAConcurrentClosureIsUnstable() {
        assertCodes("fun f() { var x: String? = \"\"; class C { init { x = null } }; x.length }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        assertCodes(
            "fun f(): Int { var s: String? = \"\"; fun c(): Int { if (s == null) return 0; s = null; return s.length }; return c() }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        val foo = "import kotlin.contracts.*\n@OptIn(ExperimentalContracts::class)\n" +
            "fun foo(f1: () -> Unit, f2: () -> Unit) { contract { callsInPlace(f1, InvocationKind.EXACTLY_ONCE); callsInPlace(f2, InvocationKind.EXACTLY_ONCE) }; f2(); f1() }\n"
        assertCodes(foo + "fun f() { var s: String? = null; s = \"\"; foo({ s.length }, { s = null }) }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        clean(foo + "fun f() { var s: String? = null; s = \"\"; foo({ s.length }, { s.length }) }")
        // A variable declared inside the closure is the closure's own (smartCasts/kt10240).
        clean("fun myRun(f: () -> Unit) { f() }\nfun d() { myRun { var k: String? = \"a\"; while (k != null) { k.length; k = null } } }")
    }

    /** `var b = a` makes `b` an alias of `a` until either is reassigned: a check on one narrows the other
     *  (smartCasts/variables/aliasing, loops/doWhileWithAssignment with bounds). */
    @Test
    fun aVarAliasSharesTheNarrowing() {
        clean("fun f(a0: String?) { var a = a0; var b = a; var c = b; if (a != null) { b.length; c.length }; if (b is String) { a.length; c.length } }")
        // An operator assignment does not count as a reassignment on loop entry (K2, KT-57563).
        clean("fun f(i: Int, x: String?) { var y = x; do { require(y != null); x.length; y += \"\" } while (i < 10) }")
        clean("fun f(a0: String?) { var a: String? = a0; val b = a; if (a != null) { b.length }; a = null; if (b != null) b.length }")
    }

    @Test
    fun aBrokenAliasIsStillReported() {
        assertCodes(
            "fun f(i: Int, x: String?) { var y = x; do { require(y != null); x.length; y = null } while (i < 10) }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes("fun f(a1: String?): Int { val a2: String? = a1; if (a2 != null) return a1.length; return 0 }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        assertCodes(
            "fun f(a0: String?) { var a: String? = a0; var b = a; b = null; if (a != null) { b.length } }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes(
            "fun f(a0: String?) { var a: String? = a0; val b = a; a = null; if (b != null) { a.length } }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
    }

    /** An assignment narrows a `var` to what was assigned, a constructor call or another non-null value
     *  included (controlFlowAnalysis/kt2845, varnotnull/inference). */
    @Test
    fun anAssignedNonNullValueNarrows() {
        clean("fun f(): Int { var list: MutableList<Int>?; try { list = ArrayList(); list.add(3); return 0 } finally { } }")
        clean("fun f() { var s: String?; s = \"a\"; var s1 = \"\"; if (s != null) { s1 = s; s1.length; val s2 = s; s2.length } }")
        // A loop that only ever assigns non-null leaves the value known after it (K2's processWhileLoopExit).
        clean("fun f(l: List<Int>) { var x: String?; x = \"\"; for (i in l) { x = \"a\" }; x.length }")
        clean("fun f(c: Boolean) { var x: String?; x = \"\"; while (c) { x = \"a\" }; x.length }")
    }

    /** A declared type is what a declaration has, whatever its initializer is: only a later assignment narrows
     *  (ExtensionCallInvoke: `val i: Int? = 1; i.doIt()` is an unsafe call). */
    @Test
    fun anExplicitlyTypedInitializerDoesNotNarrow() {
        assertCodes("fun f() { val i: String? = \"a\"; i.length }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        clean("fun f() { var i = \"a\"; i.length; var j: String?; j = \"b\"; j.length }")
    }

    private fun clean(code: String) {
        val errors = errors(code)
        assertTrue(errors.isEmpty(), "expected `$code` clean, got $errors")
    }

    private fun assertCodes(code: String, vararg expected: String) {
        val errors = errors(code)
        assertEquals(expected.toList(), errors.map { it.code }, "for `$code`: $errors")
    }

    private fun errors(code: String): List<Diagnostic> = runBlocking {
        val text = "package demo\n$code\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath())))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }
}

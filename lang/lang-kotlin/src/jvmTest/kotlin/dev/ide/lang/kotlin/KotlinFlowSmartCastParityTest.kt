package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smart casts the compiler makes and the editor missed, found by running Kotlin's own diagnostic test data
 * (compiler/testData/diagnostics/tests) through the checkers. Each test is one root cause in the data-flow
 * layer; the `...StillReported` tests keep the real errors next to each fix.
 */
class KotlinFlowSmartCastParityTest {

    /** A `!!`, a hard cast or a safe-call receiver anywhere in an expression evaluated before the use proves the
     *  value non-null, not only one written as a whole statement. */
    @Test
    fun aNonNullProofInsideAnEarlierExpressionNarrows() {
        clean("fun foo(x: String): String? = x\nfun g(x: String?): Int { foo(x!!)?.subSequence(0, x.length); return x.length }")
        clean("fun g(x: List<Int>?): Int? = x?.subList(0, x.size)?.get(x.size - 1)")
        clean("fun g(s: String?, t: String?): Int = s!!.length + s.length + (t as String).length + t.length")
        clean("class C(val a: Int, val b: String, val c: String) {\n" +
            "    constructor(x: String?, y: String?) : this(x!!.length, x.length.toString() + y!!, \"\") { x.length + y.length }\n}")
    }

    /** An `if` or `when` every completing branch of which proves the value (or whose condition decides it). */
    @Test
    fun branchesThatAllProveTheValueNarrowAfterwards() {
        clean("fun g(a: String?, c: Boolean): Int { if (c) { a!! } else { requireNotNull(a) }; return a.length }")
        clean("fun g(a: String?): Int { if (a == null) { println(); return 0 } else println(); return a.length }")
        clean("fun g(a: String?, k: Int): Int { when (k) { 1 -> a!!; else -> return 0 }; return a.length }")
        clean("fun g(x: String?): Int { x ?: x!!.length; return x.length }")
    }

    /** A loop is left only through its exits; when every exit proves the value, so does the loop. */
    @Test
    fun loopsWhoseEveryExitProvesTheValueNarrowAfterwards() {
        clean("fun x(): Boolean = true\nfun g(p: String?): Int { do { p!!.length } while (!x()); return p.length }")
        clean("fun x(): Boolean = true\nfun g(p: String?): Int { while (true) { p!!.length; if (x()) break }; return p.length }")
        clean("fun g(s: String?): Int { while (s!!.length > 0) { s.length }; return s.length }")
        clean("fun g(s: String?): Int { while (s == null) { }; return s.length }")
        clean("fun g(s: String?): Int { do { } while (s!!.length > 0); return s.length }")
        clean(
            "fun x(): Boolean = true\nfun g(p: String?, r: String?, q: String?): Int {\n" +
                "    while (true) { q!!.length; do { do { p!!.length } while (!x()) } while (r == null); if (!x()) break }\n" +
                "    r.length; q.length; return p.length\n}"
        )
    }

    @Test
    fun aLoopWithAnUnprovenExitIsStillReported() {
        assertCodes(
            "fun x(): Boolean = true\nfun g(p: String?): Int { while (true) { if (x()) break; p!!.length }; return p.length }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes(
            "fun x(): Boolean = true\nfun g(p: String?): Int { do { if (x()) continue; p!!.length } while (x()); return p.length }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes("fun g(s: String?): Int { while (s != null) { }; return s.length }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
    }

    /** `a?.b != null` proves both `a` and the path `a.b`, as does a contract or a precondition over it. */
    @Test
    fun aSafeChainGuardProvesItsReceiverAndPath() {
        val decls = "class C { val d: Int? = 2 }\nclass A { val b: Int? = 1; val e: C? = C() }\n"
        clean(decls + "fun g(a: A?): Int { require(a?.b != null); return a.b.inc() }")
        clean(decls + "fun g(a: A?): Int { require(a?.e?.d != null); val k: C = a.e; return a.e.d + k.hashCode() }")
        clean(decls + "fun g(a: A?): Int { require(a!!.b != null); return a.b.inc() }")
        clean("fun g(x: String?): Int = if (x?.isEmpty() == true) x.length else 0")
        clean("fun g(s: String?): Int = if (true == (s != null)) s.length else 0")
    }

    /** A `val` copy of a value is narrowed by a check on the original, and the other way around. */
    @Test
    fun aValAliasSharesTheNarrowing() {
        clean("class C<T>(val data: T)\nfun g(x: C<out Any?>): Int { val y = x.data; require(y is String); return x.data.length }")
        clean("fun g(a: String?): Int { val b = a; if (a != null) return b.length; return 0 }")
        assertCodes(
            "fun g(a0: String?): Int { var a = a0; val b = a; a = \"\"; if (a != null) return b.length; return 0 }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
    }

    /** A guard on an outer variable does not speak for an inner one of the same name. */
    @Test
    fun aShadowingDeclarationIsStillReported() {
        assertCodes(
            "fun g(x: String?, l: List<String?>): Int { x!!; return l.sumOf { x -> x.length } }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
    }

    /** The same walk proves a TYPE: a cast inside an earlier expression, a failed earlier `when` branch, and an
     *  `as?` whose result was proven non-null. */
    @Test
    fun aCastOrFailedBranchBeforeTheUseNarrowsTheType() {
        val ab = "interface A { fun foo() }\ninterface B { fun bar() }\n"
        clean(ab + "fun id(b: B) = b\nfun g(a: Any) { val b: B = id(a as B); a.bar(); b.bar() }")
        clean(ab + "fun g(x: Any?) { when { x !is A -> {}; x !is B -> x.foo(); else -> { x.foo(); x.bar() } } }")
        clean(ab + "fun g(x: Any?) { when (x) { !is A -> {}; is B -> { x.foo(); x.bar() } } }")
        clean(ab + "fun g(x: Any?) { when (val y = x) { is A -> { x.foo(); y.foo() } } }")
        clean("fun g(a: Any?): Int { (a as? String)!!; return a.length }")
        clean("fun g(a: Any?): Int { a as? String ?: return 0; return a.length }")
        clean("fun g(a: Any?): Int? = (a as? String)?.indexOf(a.lowercase())")
        clean("fun g(): Int { var c: Any = \"\"; (c as String).length; return c.length }")
    }

    @Test
    fun anUnprovenCastIsStillReported() {
        val ab = "interface A { fun foo() }\ninterface B { fun bar() }\n"
        assertCodes(ab + "fun g(x: Any, c: Boolean) { if (c) x as A; x.foo() }", KotlinDiagnosticCodes.UNRESOLVED)
        // K2 resolves the name before it asks whether the call is safe: `Any?` has no `length` at all, so the one
        // diagnostic is the unresolved reference (as `smartCasts/lambdasWithContracts` marks it).
        assertCodes(
            "fun g(a: Any?): Int { a as? String; return a.length }",
            KotlinDiagnosticCodes.UNRESOLVED,
        )
        assertCodes(
            "fun g(): Int { var c: Any = \"\"; (c as String).length; c = 1; return c.length }",
            KotlinDiagnosticCodes.UNRESOLVED,
        )
    }

    /** More places a value is proven non-null: a plain constructor parameter in an initializer, an anonymous
     *  object's supertype arguments, a `val` assigned once after its declaration, an elvis used as a condition,
     *  and an elvis whose right side is a call that returns `Nothing`. */
    @Test
    fun moreProofSitesNarrow() {
        clean("class A(a: String?) { val b = if (a != null) a.length else 1\n init { if (a != null) { val c = a.length } } }")
        clean("abstract class A(val s: String)\nfun foo(o: String?): Int { val a = object : A(o!!) {}; return o.length }")
        clean("fun <E> m(): E = TODO()\nfun f(): Int { val x: String?; x = m<String?>()!!; return x.length }")
        clean("interface A { fun foo(); val b: Boolean }\nfun f(x: A?) { if (x?.b ?: return) x.foo() }")
        clean("interface O { val expired: Boolean?; fun go() }\nfun f(o: Any) { val order = o as? O; if (order?.expired ?: false) order.go() }")
        clean("fun <R> callIt(fn: () -> R): R = TODO()\nfun f(p1: String?): Int { p1 ?: callIt { TODO() }; return p1.length }")
    }

    /** A value proven null fits any nullable parameter, and a check against a type the value already has keeps
     *  the declared (narrower) type. */
    @Test
    fun aProvenNullOrAWiderCheckIsNotAMismatch() {
        clean("fun foo(x: String?) = x\nclass T\nfun bar(i: T?) { if (i == null) foo(i); if (null == i) foo(i); when (i) { null -> foo(i); else -> {} } }")
        clean("interface A<T>\nfun <T> foo(b: A<T>) = b\nfun <T> test(a: A<T>) { if (a is Any) { foo(a) }; foo(a) }")
        assertCodes("fun foo(x: String?) = x\nclass T\nfun bar(i: T?) { if (i != null) foo(i) }", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** A guard on an access path is believed only where Kotlin would smart-cast it: not through a member that can
     *  change (open, `var`, custom getter, interface), not after the root is reassigned, not into a `val` whose
     *  declared type makes it a value of its own. */
    @Test
    fun anUnstableOrInvalidatedGuardIsStillReported() {
        assertCodes("interface Q { val data: String? }\nfun f(q: Q): Int = if (q.data != null) q.data.length else 0", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        assertCodes("open class Q { open val data: String? = null }\nfun f(q: Q): Int { q.data as String; return q.data.length }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        clean("open class Q { open val data: String? = null }\nclass F : Q()\nfun f(q: F): Int { q.data as String; return q.data.length }")
        clean("class Q { val data: String? = null }\nfun f(q: Q?): Int { require(q?.data != null); return q.data.length }")
        // A reassigned `var` is judged by the class it was last assigned.
        val open = "open class Q { open val data: String? = null }\nclass F : Q()\nfun mk(): Q = F()\n"
        clean(open + "fun f(): Int { var q: Q = mk(); q = F(); q.data as String; return q.data.length }")
        assertCodes(open + "fun f(): Int { var q: Q = F(); q = mk(); q.data as String; return q.data.length }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        assertCodes(
            "class My(val x: My?)\nfun f(z: My) { var y = z; if (y.x != null) { y.x.x; y = My(null); y.x.x } }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
        assertCodes("fun f(a1: String?): Int { val a2: String? = a1; if (a2 != null) return a1.length; return 0 }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
    }

    /** `a || b` true (and `a && b` false) proves what both sides prove; a `when (val v = …)` subject variable is
     *  stable; a scope function runs its lambda in place; a lambda that never completes makes its call `Nothing`. */
    @Test
    fun compoundConditionsScopeCallsAndSubjectVariablesNarrow() {
        val ab = "interface A { fun foo(): Boolean }\ninterface B : A\ninterface C : A\n"
        clean(ab + "fun f(a: A?) { if (a is B || a is C) a.foo() }")
        clean("fun f(x: Any?): Int = if (x is String || false) x.length else 0")
        clean("interface A { fun foo() }\nfun f(x: Any?) { when (val y = x) { is A -> { x.foo(); y.foo() } } }")
        clean("class C { val s: String? = null }\nfun f(p: C) { p.s ?: run { return }; p.s.length }")
        clean("inline fun <R> callIt(fn: () -> R): R = fn()\nfun f(p: String?): Int { p ?: callIt { return 0 }; return p.length }")
        clean("interface A\ninterface C\nfun f(b: Any?): C? = if (b is A && b is C) b else null")
    }

    /** `when` guards are read by the walk (the old blanket back-off on any `when` naming the value is gone), and
     *  an in-place lambda nested in another call's arguments proves nothing after it, as in Kotlin. */
    @Test
    fun whenGuardsAreReadAndPostponedLambdasProveNothing() {
        clean("fun f(s: String?): Int = when { s == null -> 0; else -> s.length }")
        clean("fun f(s: String?): Int = when (s) { null -> 0; else -> s.length }")
        clean("fun String.foo() = true\nfun f(s: String?): Int = when { s == null -> 1; s.foo() -> 2; else -> 3 }")
        clean("fun f(s: String?): Int? = when (true) { (s != null) -> s.length; else -> null }")
        clean("fun f(s: String?): Int? = when (s != null) { true -> s.length; else -> null }")
        assertCodes("fun f(s: String?): Int { when { s == null -> println() }; return s.length }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        assertCodes(
            "fun <T> foo(x: T?, y: Int) {}\nfun <T> id(x: T): T = x\nfun <T> n(): T? = null\n" +
                "fun f(x: String?): Int { foo(id(run { x as String; n<Int>() }), 1); return x.length }",
            KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
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

package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SMARTCAST_IMPOSSIBLE: a member reached through a check on a value the compiler will not smart cast. Reduced
 * from the compiler's own test data (`smartCasts/`); each unstable case is paired with the stable one Kotlin
 * accepts. Also MEMBER_PROJECTED_OUT, the other half of what a value's type does not let a member do.
 */
class KotlinSmartCastStabilityTest {

    private val sci = KotlinDiagnosticCodes.SMARTCAST_IMPOSSIBLE

    @Test
    fun mutableAndCustomGetterMembersAreNotSmartCast() {
        assertCodes("class X { var x: String? = null\n fun f(): Int { if (x != null) return x.length; return 0 } }", sci)
        assertCodes("class X { private val x: String? = null\n val y: CharSequence? get() = x\n fun f(): Int { if (y != null) return y.length; return 0 } }", sci)
        clean("class X { val x: String? = null\n fun f(): Int { if (x != null) return x.length; return 0 } }")
        // An open property may be overridden with a getter; `hashCode` needs the non-null member.
        assertCodes("sealed class My(open val x: Int?) { init { if (x != null) { x.hashCode() } } }", sci)
        clean("class My(val x: Int?) { init { if (x != null) { x.hashCode() } } }")
    }

    @Test
    fun anIsCheckOnAnUnstableValue() {
        assertCodes("open class A\nclass B : A() { fun foo() {} }\ninterface KI { val a: A }\nfun KI.bar() { if (a is B) { a.foo() } }", sci)
        clean("open class A\nclass B : A() { fun foo() {} }\nclass KI(val a: A)\nfun KI.bar() { if (a is B) { a.foo() } }")
        assertCodes("class D { operator fun getValue(t: Any?, p: Any?): Any? = 1 }\nfun f() { val v: Any? by D()\n if (v is Int) { v.inc() } }", sci)
    }

    @Test
    fun aCapturedVarChangedByAClosure() {
        // Written in a local function created before the read.
        assertCodes("fun foo() { var i: Int? = 5\n if (i != null) { fun can(): Boolean { i = null; return true }\n i.hashCode() } }", sci)
        // Read in a lambda that is not called in place, written after it.
        assertCodes("fun later(f: () -> Unit) {}\nfun t() { var x: Any? = \"\"\n require(x is String)\n later { x.length }\n x = \"\" }", sci)
        // Read in a lambda called in place: fine.
        clean("fun t() { var x: Any? = \"\"\n require(x is String)\n run { x.length }\n x = \"\" }")
        // Nothing writes it after the closure exists: fine.
        clean("fun later(f: () -> Unit) {}\nfun t() { var x: Any? = \"\"\n require(x is String)\n later { x.length } }")
    }

    /** MEMBER_PROJECTED_OUT: a parameter of the class's `T` on a receiver that projects `T` out. */
    @Test
    fun aProjectedOutParameterTakesNoArgument() {
        val mpo = KotlinDiagnosticCodes.MEMBER_PROJECTED_OUT
        val inv = "class Inv<T> { fun f(t: T): T = t\n fun outf(): T = TODO() }\n"
        assertCodes(inv + "fun t(a: Inv<out Int>) { a.f(1) }", mpo)
        assertCodes(inv + "fun t(a: Inv<*>) { a.f(1) }", mpo)
        assertCodes("fun t(list: MutableList<out Number>) { list.add(42) }", mpo)
        clean(inv + "fun t(a: Inv<in Int>, b: Inv<Int>, c: Inv<*>) { a.f(1); b.f(1); c.outf() }")
        // The very captured type a stable receiver hands out goes back in.
        clean("fun t(a: Array<*>) { a.set(0, a.get(0)) }")
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
        val a = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath())))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }
}

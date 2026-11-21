package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Names the compiler reports unresolved, or a type-argument count it rejects, in shapes the simple-name checks
 * did not reach: a segment of a qualified type (`Outer.Foo`), a nested class reached through an instance
 * (`p.Nested()`), a call's explicit type arguments (`foo<Int, String>()`) and a raw generic type nested in
 * another type's arguments (`x is List<Map>`). Each case keeps the valid spelling beside it clean.
 */
class KotlinQualifiedResolutionTest {

    private fun errors(code: String): List<Pair<String, String?>> {
        val dir = tempProject(mapOf("Use.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        try {
            val doc = SnippetDoc(code, DiskFile(dir.resolve("Use.kt")))
            return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                .filter { it.severity == Severity.ERROR }
                .map { d: Diagnostic -> code.substring(d.range.start, d.range.end) to d.code }
        } finally {
            analyzer.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unknownSegmentOfAQualifiedType() {
        val errs = errors(
            "package demo\nclass Outer { class Nested { class Deep }\n  object O }\n" +
                "fun f(a: Outer.Foo, b: Outer.Nested.Deep, c: Outer.O, d: Outer.Nested.Bar) {}\n"
        )
        val unresolved = errs.filter { it.second == KotlinDiagnosticCodes.UNRESOLVED }.map { it.first }
        assertEquals(listOf("Foo", "Bar"), unresolved, "got $errs")
    }

    @Test
    fun classHeaderDoesNotSeeItsOwnNestedClasses() {
        val errs = errors(
            "package demo\ninterface I<T>\nclass Outer {\n  open class Sibling\n" +
                "  class A(p: Nested) : Sibling(), I<Nested> {\n    class Nested\n    fun f(n: Nested) {}\n  }\n}\n"
        )
        val unresolved = errs.filter { it.second == KotlinDiagnosticCodes.UNRESOLVED }.map { it.first }
        assertEquals(listOf("Nested"), unresolved, "only the supertype argument is outside Nested's scope; got $errs")
    }

    @Test
    fun outerTypeParametersStopAtANestedClass() {
        val errs = errors(
            "package demo\nclass Foo<T> {\n  companion object { fun a(x: T) {} }\n  class Nested { fun b(x: T) {} }\n" +
                "  inner class Inner { fun c(x: T) {} }\n  val o = object { fun d(x: T) {} }\n  fun <R> e() { class L { fun f(x: T, y: R) {} } }\n}\n"
        )
        val unresolved = errs.filter { it.second == KotlinDiagnosticCodes.UNRESOLVED }.map { it.first }
        assertEquals(listOf("T", "T"), unresolved, "only the companion and the nested class are shut out; got $errs")
    }

    @Test
    fun nestedClassThroughAnInstance() {
        val errs = errors(
            "package demo\nclass Outer { class Nested\n  inner class In\n  object Obj }\n" +
                "fun f(p: Outer) { p.Nested(); p.Obj; p.In(); Outer.Nested(); Outer.Obj }\n"
        )
        val unresolved = errs.filter { it.second == KotlinDiagnosticCodes.UNRESOLVED }.map { it.first }
        assertEquals(listOf("Nested", "Obj"), unresolved, "got $errs")
    }

    @Test
    fun explicitTypeArgumentCountOfACall() {
        val errs = errors(
            "package demo\nfun <T> one(t: T) = t\nclass Box<T>(val t: T)\nclass Plain\n" +
                "fun f() { one<Int, String>(1); one<Int>(1); Box<Int, Int>(1); Box<Int>(1); Plain<Int>() }\n"
        )
        val counts = errs.filter { it.second == KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT }.map { it.first }
        assertEquals(listOf("<Int, String>", "<Int, Int>", "<Int>"), counts, "got $errs")
        val aliased = errors("package demo\ntypealias P<A, B> = Pair<A, B>\nval p = P<Int>(1, 2)\nval q = P<Int, Int>(1, 2)\nfun f(x: P<Int, Int, Int>) {}\n")
            .filter { it.second == KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT }.map { it.first }
        assertEquals(listOf("<Int>", "<Int, Int, Int>"), aliased, "an alias takes its own type parameters")
    }

    @Test
    fun rawGenericTypeAsAnArgument() {
        val errs = errors(
            "package demo\nfun f(a: Any, b: Map<String, Int>) { if (a is List<Map>) {}\n  if (a is List<*>) {}\n  if (a is List) {} }\n"
        )
        val counts = errs.filter { it.second == KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT }.map { it.first }
        assertEquals(listOf("Map"), counts, "only the nested raw Map is an error; got $errs")
    }

    @Test
    fun constructorAndFunctionOfTheSameSignatureConflict() {
        val errs = errors(
            "package demo\nclass Same(val x: Int) { constructor() : this(0) }\nfun Same() = 1\nfun Same(s: String) = 2\n" +
                "class Factory(val x: Int)\nfun Factory(): Factory = Factory(0)\n"
        )
        val conflicts = errs.filter { it.second == KotlinDiagnosticCodes.CONFLICTING_DECLARATION }.map { it.first }
        assertEquals(listOf("()", "Same"), conflicts, "only `constructor()` and `fun Same()` clash; got $errs")
    }

    @Test
    fun unknownCallableReferences() {
        val errs = errors(
            "package demo\nclass A(val x: Int) { fun m() = 1 }\nfun top() = 1\n" +
                "fun f(a: A, s: String) {\n  ::top; ::nope; A::m; A::x; A::gone; a::m; a::gone; s::length; s::gone; Nope::m; ::A\n}\n"
        )
        val unresolved = errs.filter { it.second == KotlinDiagnosticCodes.UNRESOLVED }.map { it.first }
        assertEquals(listOf("nope", "gone", "gone", "gone"), unresolved, "a receiver name is judged on evidence only, as `x.y` is; got $errs")
    }

    @Test
    fun referenceToOverloadsWithoutAnExpectedType() {
        val errs = errors(
            "package demo\nfun foo() {}\nfun foo(s: String) {}\nfun one() {}\n" +
                "val a = ::foo\nval b: () -> Unit = ::foo\nval c = ::one\nfun g() = run { ::foo }\n"
        )
        val ambiguous = errs.filter { it.second == KotlinDiagnosticCodes.OVERLOAD_AMBIGUITY }.map { it.first }
        assertEquals(listOf("foo"), ambiguous, "only `val a = ::foo` has nothing to pick an overload by; got $errs")
    }

    @Test
    fun boundViolatedThroughATypealias() {
        val errs = errors(
            "package demo\nclass Num<T : Number>(val x: T)\ntypealias N<T> = Num<T>\ntypealias N2<T> = N<T>\n" +
                "val a = N<String>(\"\")\nval b = N2<String>(\"\")\nval c = N<Int>(1)\nfun f(x: N2<String>, y: N<Long>) {}\n"
        )
        val bounds = errs.filter { it.second == KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED }.map { it.first }
        assertEquals(listOf("String", "String", "String"), bounds, "got $errs")
    }

    @Test
    fun rawLibraryTypeInADeclaration() {
        val errs = errors("package demo\nfun f(b: Map): List<String> = emptyList()\n")
        assertTrue(errs.any { it.first == "Map" && it.second == KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT }, "got $errs")
    }
}

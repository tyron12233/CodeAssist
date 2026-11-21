package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valid code the editor flagged, found by running the Kotlin compiler's own diagnostic test data through the
 * checkers (`KotlinCompilerTestDataSweepTest`). Each test is one root cause, named after what Kotlin allows;
 * the snippets are reduced from the compiler test files the sweep reported.
 */
class KotlinCompilerParityTest {

    /** A `when` over a sealed-interface hierarchy is exhaustive when a branch covers each subtype through any of
     *  its supertypes; `Type<Args>::member` is not a call. */
    @Test
    fun sealedInterfaceTrianglesAndGenericCallableReferences() {
        clean(
            "sealed interface A\nsealed interface B : A\nsealed interface C : B\nclass E : A\nclass F : B\n" +
                "abstract class G : B\nclass H : G(), C\nfun test_1(a: A): Int = when (a) { is E -> 0; is F -> 1; is G -> 2 }",
        )
        assertCodes("sealed interface A\nclass E : A\nclass F : A\nfun t(a: A): Int = when (a) { is E -> 0 }", KotlinDiagnosticCodes.WHEN_EXHAUSTIVE)
        clean("val p = Pair<String, String>::first")
    }

    /** An enum's `values()`/`valueOf()`/`entries` are bare inside its body and its companion, and nowhere else. */
    @Test
    fun enumStaticsAreBareInsideTheEnum() {
        clean(
            "enum class E {\n ENTRY;\n companion object { fun bar(): Array<E> = values(); fun baz(): E = valueOf(\"ENTRY\") }\n" +
                " fun rab(): Array<E> = values()\n fun n(): Int = entries.size\n}",
        )
        assertCodes("fun x() = values()", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** Kotlin 2.3's nested type aliases resolve inside their class and through it. */
    @Test
    fun nestedTypeAliasesResolve() {
        clean("class Box { class Inner(val v: Int); typealias I = Inner; fun make(): I = I(1); fun n(): Int = make().v }")
        clean("class Class { class Annotated1; typealias TA = Annotated1 }\nval x: Class.TA = Class.TA()")
        assertCodes("class Box { class Inner; typealias I = Inner }\nfun f(): I? = null", KotlinDiagnosticCodes.UNRESOLVED)
        clean("open class Base { class Cell(val v: Int); typealias CT = Cell }\nclass Sub : Base() { val test1: CT = Cell(42) }")
    }

    /** A class's companion, and its superclasses' companions, are implicit receivers in its body. */
    @Test
    fun companionsAreImplicitReceiversInTheirClass() {
        clean(
            "open class A { companion object { fun bar() = 1 }; init { val a: Int = foo(); val b: Int = bar() } }\n" +
                "open class B: A() { companion object { fun bar() = \"\" }; init { val a: String = foo(); val b: String = bar() } }\n" +
                "fun A.Companion.foo() = 1\nfun B.Companion.foo() = \"\"\nclass C: A() { init { val a: Int = foo() } }",
        )
        assertCodes("open class A { companion object }\nfun A.Companion.foo() = 1\nfun t() = foo()", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** A lambda on the right of an infix call fills its parameter, receiver included, and an annotated,
     *  parenthesized receiver type (`(@Marker A).() -> Unit`) is still that receiver. */
    @Test
    fun infixLambdasAndAnnotatedReceiverTypes() {
        clean("class Inv<T> { fun get(): T = TODO() }\ninfix fun <T> T.check(f: Inv<T>.() -> Unit) {}\nfun t(s: String) { s check { get().length } }")
        clean("@DslMarker @Target(AnnotationTarget.TYPE) annotation class L1\nclass A { fun a() = 1 }\nfun foo1(x: (@L1 A).() -> Unit) {}\nfun t() { foo1 { a() } }")
        clean("fun f(g: ((Int) -> String)?): String? = g?.invoke(1)")
    }

    /** `@DslMarker`: a bare call reaching past a closer receiver with the same marker is a DSL scope violation,
     *  whether the marker sits on the receiver class or on the function type's receiver. */
    @Test
    fun dslMarkersStopCallsThroughOuterReceivers() {
        val v = KotlinDiagnosticCodes.DSL_SCOPE_VIOLATION
        assertCodes(
            "@DslMarker annotation class Html\n@Html class Body { fun p() {} }\n@Html class Head { fun title() {} }\n" +
                "fun body(b: Body.() -> Unit) {}\nfun head(h: Head.() -> Unit) {}\nfun t() { body { p(); head { title(); p() } } }",
            v,
        )
        assertCodes(
            "@DslMarker @Target(AnnotationTarget.TYPE) annotation class L1\nclass A { fun a() = 1 }\nclass B { fun b() = 2 }\n" +
                "fun foo1(x: (@L1 A).() -> Unit) {}\nfun bar1(x: (@L1 B).() -> Unit) {}\nfun t() { foo1 { a(); bar1 { b(); a() } } }",
            v,
        )
        clean("class X { fun x() {} }\nclass Y { fun y() {} }\nfun xs(b: X.() -> Unit) {}\nfun ys(b: Y.() -> Unit) {}\nfun t() { xs { ys { x(); y() } } }")
    }

    /** `@Suppress("K2_NAME")` drops the diagnostic it names, errors included, and nothing else. */
    @Test
    fun suppressHonoursK2Names() {
        clean("fun foo(x: String) {}\n@Suppress(\"ARGUMENT_TYPE_MISMATCH\") fun bar() { foo(10) }")
        assertCodes("fun foo(x: String) {}\n@Suppress(\"UNUSED_VARIABLE\") fun bar() { foo(10) }", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** Opt-in: the named array form opts in, and an out-of-scope top-level function is not the callee. */
    @Test
    fun optInRecognisesArraysAndOnlyCalleesInScope() {
        clean("@RequiresOptIn annotation class MyOptIn\n@MyOptIn fun foo() {}\n@OptIn(markerClass = [MyOptIn::class]) class MyClass { fun test() { foo() } }")
        clean("class MyBuilder { fun contract(contract: String) { contract.length } }\nfun t() { MyBuilder().apply { contract(\"test\"); this@apply.contract(\"\") } }")
        assertCodes("@RequiresOptIn annotation class MyOptIn\n@MyOptIn fun foo() {}\nfun t() { foo() }", KotlinDiagnosticCodes.OPT_IN_USAGE_ERROR)
    }

    /** Small K2 rules: `@Repeatable` repeats, a trailing lambda fits `Any`, backticked labels, data-class
     *  generated members, JVM-mapped members implemented the Kotlin way, members beating extensions. */
    @Test
    fun smallResolutionRulesMatchK2() {
        clean("@Repeatable annotation class repann\n@repann @repann class DoubleAnnotated")
        assertCodes("annotation class single\n@single @single class Twice", KotlinDiagnosticCodes.MODIFIERS)
        clean("fun foo(a: Any) {}\nfun f() { foo { } }")
        clean("fun `yield`(): Unit { return@`yield` }")
        clean("interface T { fun component1(): Int }\ndata class A(val x: Int) : T")
        clean("class C { fun t(x: UInt) {} }\nfun C.t(x: ULong) {}\nfun g(c: C) { c.t(9U) }")
    }

    /** A classifier is looked up through every enclosing class, including a local or anonymous one, and through
     *  its own companion and (transitive, typealiased) superCLASSES. */
    @Test
    fun nestedClassifiersResolveThroughTheWholeClassScope() {
        clean("abstract class A { abstract class Nested }\ntypealias TA = A\nclass B : TA() { class NestedInB : Nested() }")
        clean(
            "abstract class BaseClass { interface NestedInterface }\nabstract class IntermediateClass : BaseClass()\n" +
                "val u: Any = object : IntermediateClass() { inner class Inner : NestedInterface }",
        )
        clean("abstract class Outer { interface Nested { fun bar() } }\nfun main() { object : Outer() { fun foo(n: Nested) { n.bar() } } }")
        clean("abstract class Some { companion object { class InCompanion }; abstract val x: InCompanion }")
        clean("class X() { companion object { class Y() {} }; val y : Y = Y() }")
        clean("abstract class Foo<T> { inner class Inner; abstract fun render(context: Inner) }\ntypealias TA = Foo<String>\nclass Test : TA() { override fun render(context: Inner) {} }")
        clean("class Base { private class Private; fun test() { object { val x: Private = Private(); fun foo() { val z: Private = Private() } } } }")
        clean("class A { private open class B; fun f() { class C : B() } }")
        clean("fun check() { class A { val bar get() = B().foo; private inner class B { var foo: String? = null } } }")
    }

    /** What Kotlin does NOT put in scope: an interface's nested types, a supertype companion's nested types, and
     *  `_` outside a call's type arguments. */
    @Test
    fun classifiersOutsideTheScopeAreStillReported() {
        val u = KotlinDiagnosticCodes.UNRESOLVED
        assertCodes("abstract class A { abstract class Nested }\nclass B : A() { class C : Missing() }", u)
        assertCodes("interface I { interface N }\nclass C : I { fun f(n: N) {} }", u)
        assertCodes("interface I { interface N }\nval b: Any = object : I { inner class X : N }", u)
        assertCodes("open class A { companion object { class FromCompanion } }\nclass B : A() { val e: FromCompanion? = null }", u)
        assertCodes("val list2: List<`_`>? = null", u)
    }

    /** A member property of function type is invoked ahead of any extension of the same name. */
    @Test
    fun aFunctionTypedMemberPropertyOutranksAnExtensionOfItsName() {
        clean("class Rule(val apply: () -> Unit)\nfun f(rule: Rule?) { rule?.apply(); if (rule != null) rule.apply() }")
        clean("class Rule(val apply: (Int) -> String)\nfun f(rule: Rule): Int = rule.apply(1).length")
    }

    /** A Java class's methods implement the Kotlin members they map to (`size()` is `size`). */
    @Test
    fun aJavaCollectionSubclassNeedsNoMappedMembers() {
        clean("class Foo1() : java.util.ArrayList<Int>()")
        clean("class M : java.util.HashMap<String, Int>()")
    }

    /** An unqualified `super.m()` reaches `m` in whichever supertype declares it. */
    @Test
    fun anUnqualifiedSuperCallReachesAnInterfaceDefault() {
        clean(
            "open class GB<T> { open fun foo(x: T): T = x }\ninterface GI<T> { fun bar(x: T): T = x }\n" +
                "class SD : GB<Int>(), GI<String> { override fun foo(x: Int): Int = super.foo(x); override fun bar(x: String): String = super.bar(x) }",
        )
    }

    /** An anonymous function's receiver is its implicit `this`, not its return type. */
    @Test
    fun anAnonymousFunctionWithAReceiverHasThatReceiver() {
        clean("class Foo { fun bar() {} }\nval f = fun Foo.() { bar() }\nval g = fun Foo.(): Int { bar(); return 1 }")
        clean("class Foo { fun bar() {} }\nfun use(b: Foo.() -> Unit) {}\nfun t() { use(fun Foo.() { this.bar() }) }")
    }

    /** A top-level property of extension function type is invoked on a matching receiver. */
    @Test
    fun aTopLevelExtensionTypedPropertyIsInvokedOnItsReceiver() {
        clean("object O\nval foo: O.() -> Int = { 1 }\nfun test(): Int = O.foo()")
    }

    /** `_` asks inference for a type argument; the synthetic function families resolve once their package is
     *  imported. */
    @Test
    fun underscoreTypeArgumentsAndSyntheticFunctionTypesResolve() {
        clean("fun <K, V> pairOf(k: K, v: V): Pair<K, V> = k to v\nfun t(): Int = pairOf<_, String>(1, \"a\").first")
        clean("import kotlin.coroutines.*\nclass C: SuspendFunction0<Unit> { override suspend fun invoke() {} }")
        assertCodes("class C: SuspendFunction0<Unit> { override suspend fun invoke() {} }", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** Every enum class is a `kotlin.Enum` of itself, and a `var` constructor parameter's reference is mutable. */
    @Test
    fun implicitSupertypesAndMutablePropertyReferences() {
        clean("enum class E { ENTRY }\nval a: Enum<E> = E.ENTRY\nfun f(x: Enum<*>) = x.name\nval b = f(E.ENTRY)")
        clean("import kotlin.reflect.*\nclass A(var g: A) { fun test() { val gRef: KMutableProperty1<A, A> = A::g } }")
    }

    /** The declaration's expected type reaches neither a `throw` operand nor a call's receiver. */
    @Test
    fun expectedTypesStopAtThrowAndReceivers() {
        clean("fun <T> a(): T = null!!\nfun t() { val b: Unit = throw a() }")
        clean("fun <T> make(): T = null!!\nfun t() { val s: String = make<Int>().toString() }")
    }

    /** `Outer<A>.Inner<B>` names the inner class, with the outer's arguments in front of it. */
    @Test
    fun aQualifiedInnerTypeWithOuterArgumentsResolves() {
        clean("class A<T> { inner class B }\nfun t(a: A<Int>) { val b : A<Int>.B = a.B() }")
        clean("class Outer<T> { inner class Inner<S>; fun <U, S> newOuterInner(): Outer<U>.Inner<S> = Outer<U>().Inner<S>() }")
    }

    /** Overloads: a lambda's declared parameter count picks the function type, and a candidate needing no vararg
     *  expansion beats one that does. */
    @Test
    fun overloadsAreChosenByLambdaArityAndVarargUse() {
        val foo = "fun foo(x: (a: Int) -> Unit): Int = 1\nfun foo(x: (a: Int, b: String) -> Unit): String = \"\"\n"
        clean(foo + "fun t1(): Int = foo { x -> }\nfun t2(): String = foo { x, y -> }\nfun t3(): Int = foo {}")
        clean(
            "object Right\nobject Wrong\ninterface A<T>\ninterface B<T> : A<T>\nfun <T> foo(vararg t: T) = Wrong\n" +
                "fun <T> foo(t: A<T>) = Wrong\nfun <T> foo(t: B<T>) = Right\nfun test(b: B<Int>): Right = foo(b)",
        )
    }

    /** A bare `return@label` makes the lambda's result Unit. */
    @Test
    fun aBareLabeledReturnMakesTheLambdaUnit() {
        clean("fun completed(): String = \"...\"\nval p = false\nfun expectUnit(x: Unit) = x\nfun test1() = run { if (p) return@run; completed() }\nfun main() { expectUnit(test1()) }")
    }

    /** A body that cannot fall off its end needs no trailing return: a jump inside a local's initializer, an
     *  elvis whose left side is always null, `null!!`, a jump on the left of `&&`. */
    @Test
    fun bodiesThatCannotFallThroughNeedNoReturn() {
        clean("fun foo(x: String): String {\n val a = try { x } finally { return x }\n}")
        clean("fun s(s: String?): Int {\n val s1 = (if (s != null) { return s.length } else { null }) ?: return 0\n}")
        clean("fun f(x: Any): Any { null!! }")
        clean("fun r(): Boolean {\n (return true) && (return false)\n}")
        assertCodes("fun g(x: Int): Int {\n val y = x + 1\n}", KotlinDiagnosticCodes.MISSING_RETURN)
    }

    /** A SAM constructor takes any function value, and `Type<Args>::member` constructs nothing. */
    @Test
    fun samConstructorsAndGenericCallableReferencesAreNotInstantiations() {
        clean("fun interface MyRunnable { fun foo(x: Int): Boolean }\nfun foo(m: MyRunnable) {}\nfun main() { val x = { i: Int -> i > 1 }; foo(MyRunnable(x)) }")
        clean("fun interface Cmp { fun c(a: Int, b: Int): Int }\nfun t(f: (Int, Int) -> Int): Cmp = Cmp(f)")
        clean("interface Expect<T> { fun and(x: Expect<T>.() -> Unit): Expect<T> }\nfun <T> id(t: T): T = t\nval r = id(Expect<Int>::and)")
        assertCodes("interface I\nval i = I()", KotlinDiagnosticCodes.ABSTRACT_INSTANTIATION)
    }

    /** Overloads compare with type parameters erased to their bounds, and by type-parameter count; a
     *  `HIDDEN`-deprecated declaration clashes with nothing. */
    @Test
    fun genericOverloadsConflictOnlyAfterErasure() {
        clean("object Test { fun <T> foo(actual: T) {}; fun <T : Number> foo(actual: T) {} }")
        clean("abstract class SuperClass { fun <T> foo() {}; @JvmName(\"foo2\") fun foo() {} }")
        clean("class Aaa() { val a = 1; @Deprecated(\"a\", level = DeprecationLevel.HIDDEN) val a = \"\" }")
        val c = KotlinDiagnosticCodes.CONFLICTING_DECLARATION
        assertCodes("fun <T> same(t: T) {}\nfun <U> same(t: U) {}", c, c)
        // HIDDEN exempts only final, non-local declarations.
        assertCodes("open class B() { open fun b() {}; @Deprecated(\"a\", level = DeprecationLevel.HIDDEN) fun b() {} }", c, c)
        assertCodes("fun outer() { fun l() {}; @Deprecated(\"a\", level = DeprecationLevel.HIDDEN) fun l() {} }", c, c)
    }

    /** A `Nothing` function whose body can complete still needs a way out. */
    @Test
    fun aNothingFunctionThatFallsThroughIsReported() {
        assertCodes("fun returnNothingEmpty(): Nothing {\n}", KotlinDiagnosticCodes.MISSING_RETURN)
        clean("fun ok(): Nothing { TODO() }")
    }

    /** `b()` binds to an `operator fun B.invoke()` that an implicit receiver declares. */
    @Test
    fun memberExtensionInvokeMakesAValueCallable() {
        clean("class B\nclass A { operator fun B.invoke() = 4 }\nfun test(a: A, b: B) { with (a) { b() } }")
        clean("class Foo\ninterface A { operator fun Foo.invoke() {}; fun test(foo: Foo) { foo() } }")
        assertCodes("fun t() { val x = 5; x() }", KotlinDiagnosticCodes.NOT_CALLABLE)
    }

    /** The primitive built-ins have companions (`Int.MAX_VALUE`), so their names are values. */
    @Test
    fun builtinCompanionsAreValues() {
        clean("val i = Int\nval c = Char\nval m = Int.MAX_VALUE")
    }

    /** A star import of an enum class puts its entries in scope by name. */
    @Test
    fun aStarImportOfAnEnumBringsItsEntries() {
        clean("import kotlin.annotation.AnnotationTarget.*\n@Target(ANNOTATION_CLASS, FUNCTION)\nannotation class A")
        assertCodes("import kotlin.annotation.AnnotationTarget.*\n@Target(NOT_AN_ENTRY)\nannotation class A", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** A labeled lambda fills its argument like an unlabeled one, receiver included. */
    @Test
    fun aLabeledLambdaKeepsItsReceiver() {
        clean("interface TI<R> { fun emit(r: R); fun get(): R }\nfun <R1> build(block: TI<R1>.() -> Unit): R1 = TODO()\nfun test() { build l1@{ emit(\"1\") } }")
        clean("fun t(xs: List<String>): Int { var n = 0; xs.forEach lbl@{ n += it.length; if (it.isEmpty()) return@lbl }; return n }")
    }

    /** `let(regex::find)` types as `MatchResult?`: a callable reference's result binds the callee's result type. */
    @Test
    fun aCallableReferenceArgumentBindsTheResult() {
        clean("private val R = Regex(\"x\")\nfun d(s: String?): String? = s?.let(R::find)?.groupValues?.get(1)")
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

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

    /** Explicit type arguments are checked against the bounds as written, across every overload, and a
     *  generic owner on the left of `::` makes an unbound property reference. */
    @Test
    fun boundsAcrossOverloadsAndGenericOwnerReferences() {
        clean("open class Foo\nclass Bar : Foo()\ninline fun <reified T : Bar> nameOf(): String = \"\"\nfun <T : Foo> nameOf(x: Int = 0): String = \"\"\nfun t() { nameOf<Bar>(); nameOf<Foo>() }")
        clean("fun <T, R : Number?> multipleTypeArguments(vararg xs: R) {}\nfun t() { multipleTypeArguments<Nothing?, Nothing?>() }")
        assertCodes("fun <T : Number> g() {}\nfun t() { g<String>() }", KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED)
        clean("class Generic<T>\nclass Klass<T> { var mutableProperty: Generic<T> = Generic() }\nfun t() { val mp = Klass<Int>::mutableProperty; mp.set(Klass<Int>(), Generic<Int>()) }")
    }

    /** `super@Label` uses the labeled class's supertypes, a `Nothing` receiver takes any member, and a
     *  companion `invoke` contributes its parameter names to a call through the class name. */
    @Test
    fun labeledSuperNothingReceiversAndCompanionInvokeNames() {
        clean("open class Base { open fun bar() = 1 }\nopen class B2 { open fun baz() = 2 }\nclass Outer : Base() { inner class In : B2() { val s = super@Outer.bar() + super.baz() } }")
        clean("fun todo(): Nothing = TODO()\nfun t() { todo().bar(1) }")
        clean("class A\nclass Case2 { companion object { operator fun invoke(a: A) = \"\" } }\nfun t(a: A) { Case2(a = a) }")
        assertCodes("class Case3(val x: Int)\nfun t() { Case3(y = 1) }", KotlinDiagnosticCodes.NAMED_ARGUMENT)
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

    /** A call's type arguments are types, never projections: `foo<*>()`, `A<in Int>()`. */
    @Test
    fun projectionsAmongACallsTypeArgumentsAreReported() {
        val code = KotlinDiagnosticCodes.PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT
        assertCodes("fun <T> getT(): T? = null\nfun t() { getT<*>() }", code)
        assertCodes("class A<T, R>\nfun t() { A<in Int, out String>() }", code, code)
        clean("fun <T> getT(): T? = null\nclass A<T>\nfun t(): A<*>? { getT<Int>(); val l: List<out Number> = listOf(1); return null }")
    }

    /** A public-API inline function cannot make a `super` call; an object literal's own `super` is local. */
    @Test
    fun superCallsFromPublicInlineFunctionsAreReported() {
        val code = KotlinDiagnosticCodes.SUPER_CALL_FROM_PUBLIC_INLINE
        assertCodes("open class A { open fun f() = 1 }\nclass B : A() { inline fun g() = super.f() }", code)
        assertCodes("open class A { open fun f() = 1 }\nclass B : A() { inline fun g() { run { super.f() } } }", code)
        clean("open class A { open fun f() = 1 }\nclass B : A() { private inline fun g() = super.f(); fun h() = super.f() + g() }")
        clean("open class A { open fun f() = 1 }\nclass B : A() { inline fun g() = object : A() { override fun f() = super.f() }.f() }")
        clean("open class A { open fun f() = 1 }\nclass B : A() { internal inline fun g() = super.f() }")
    }

    /** A projection written on a typealias argument conflicts with the variance its expansion puts there. */
    @Test
    fun conflictingProjectionsThroughATypeAliasAreReported() {
        val code = KotlinDiagnosticCodes.CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION
        val decls = "class In<in T>\nclass Out<out T>\nclass Inv<T>\ntypealias In1<T> = In<T>\ntypealias Out1<T> = Out<T>\n" +
            "typealias Inv2<T> = Inv<in T>\ntypealias InAlias<T> = In1<T>\n"
        assertCodes(decls + "fun f(x: In1<out Int>) = x", code)
        assertCodes(decls + "fun f(x: Out1<in Int>) = x", code)
        assertCodes(decls + "fun f(x: Inv2<out Int>) = x", code)
        assertCodes(decls + "fun f(x: InAlias<out Int>) = x", code)
        clean(decls + "fun f(a: In1<Int>, b: Out1<*>, c: Inv2<in Int>, d: InAlias<Int>) {}")
    }

    /** A lambda called on the spot, or written as a statement whose value nobody reads, gets no parameter types
     *  from anywhere. */
    @Test
    fun lambdaParametersWithoutAnExpectedTypeNeedTypes() {
        val code = KotlinDiagnosticCodes.PARAMETER_TYPE_REQUIRED
        assertCodes("val larg = { a -> }(123)", code)
        assertCodes("fun t() { { a -> }\n println() }", code)
        clean("val larg = { a: Int -> a }(123)\nfun t(): (Int) -> Int { return { a -> a } }\nval f: (Int) -> Unit = { a -> }")
    }

    /** `private` with `open`/`abstract` is fine on a class but not on a member, and `in out` is no variance. */
    @Test
    fun privateOpenMembersAndInOutProjectionsAreIncompatible() {
        val code = KotlinDiagnosticCodes.INCOMPATIBLE_MODIFIERS
        assertCodes("open class A { private open fun f() {} }", code, code)
        assertCodes("class Foo<T>\nfun t(foo: Foo<in out Int>) = foo", code, code)
        clean("private open class A { private fun f() {} }\nprivate abstract class B")
    }

    /** An enum entry named `name` or `ordinal` clashes with what `Enum` declares. */
    @Test
    fun enumEntriesNamedLikeEnumPropertiesAreRedeclarations() {
        val code = KotlinDiagnosticCodes.CONFLICTING_DECLARATION
        assertCodes("enum class A { name, B }", code, code)
        clean("enum class A { NAME, Ordinal }")
    }

    /** A `return` out of an expression body needs the function's type written down. */
    @Test
    fun returnsFromImplicitlyTypedExpressionBodiesAreReported() {
        val code = KotlinDiagnosticCodes.RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE
        assertCodes("fun f(b: Boolean) = run { if (b) return 1; 2 }", code)
        clean("fun f(b: Boolean): Int = run { if (b) return 1; 2 }\nfun g(b: Boolean) = run { fun h(): Int { return 1 }; h() }\n" +
            "fun k(xs: List<Int>) = xs.map l@{ if (it > 1) return@l 0; it }")
    }

    /** Enum entries are built in order, and a member initializer runs inside every entry's constructor. */
    @Test
    fun enumEntriesReadBeforeTheyAreBuiltAreReported() {
        val code = KotlinDiagnosticCodes.UNINITIALIZED_ENUM_ENTRY
        assertCodes("enum class A(val v: A?) { A1(A2), A2(A1) }", code)
        assertCodes("enum class E { X, Y; val n = when (this) { X -> 1; else -> 2 } }", code)
        clean("enum class E { X, Y; val n by lazy { when (this) { X -> 1; else -> 2 } }; fun f() = X }\n" +
            "enum class F(val f: () -> F) { A({ B }), B({ A }) }")
    }

    /** A fun interface has exactly one abstract function, which is neither generic nor defaulted. */
    @Test
    fun funInterfaceShapesAreChecked() {
        val code = KotlinDiagnosticCodes.FUN_INTERFACE_SHAPE
        assertCodes("fun interface Foo1", code)
        assertCodes("fun interface Foo3 { fun foo(); fun bar() }", code)
        assertCodes("fun interface Foo8 { fun <T> invoke(x: T) }", code)
        assertCodes("fun interface Foo5 { fun f(); val prop: Int }", code)
        clean("interface Base { fun base() }\nfun interface Good : Base\nfun interface Good2 : Base { fun more() {} }\n" +
            "fun interface Good3 { fun run(); val p: Int get() = 1 }\nfun interface Good4 : Comparable<Int>")
    }

    /** No array type bounds a type parameter. */
    @Test
    fun arrayUpperBoundsAreReported() {
        val code = KotlinDiagnosticCodes.UPPER_BOUND_CANNOT_BE_ARRAY
        assertCodes("fun <A : Array<Any>> f1() {}", code)
        assertCodes("fun <T> f5() where T : IntArray {}", code)
        clean("fun <T : List<Any>> f() {}\nfun <T> g(a: Array<T>) = a")
    }

    /** `T & Any` takes a nullable type parameter on the left and `Any` on the right. */
    @Test
    fun definitelyNotNullComponentsAreChecked() {
        val code = KotlinDiagnosticCodes.DEFINITELY_NOT_NULL_COMPONENT
        assertCodes("fun <T : Any> foo(x: T & Any) {}", code)
        assertCodes("fun <F> bar(x: F & String) {}", code)
        clean("fun <T> f(x: T & Any) {}\nfun <K : Comparable<K>?> g(x: K & Any) {}\nfun <R : T & Any, T> h() {}")
    }

    /** Bounds that reach back to their own parameter through projections never finish expanding. */
    @Test
    fun finiteBoundViolationsAreReported() {
        val code = KotlinDiagnosticCodes.FINITE_BOUNDS_VIOLATION
        assertCodes("interface A1<T : A1<*>>", code)
        assertCodes("interface B0<T : B1<*>>\ninterface B1<T : B0<*>>", code, code)
        clean("interface A<T : A<T>>\nclass E<T : Comparable<*>>\nabstract class S<T : S<T>> : Comparable<S<*>>")
    }

    /** A `const val` holds a primitive, an unsigned value or a String, never a range or an enum. */
    @Test
    fun constValTypesAreChecked() {
        val code = KotlinDiagnosticCodes.CONST_MISUSE
        assertCodes("const val r = 1..2", code)
        assertCodes("enum class E { A }\nconst val e: E = E.A", code)
        clean("const val a = 1\nconst val b: Long = 2\nconst val s = \"x\" + a\nconst val c = a + 3")
    }

    /** `super<X>` names one of the class's own supertypes. */
    @Test
    fun superQualifiersMustBeOwnSupertypes() {
        val code = KotlinDiagnosticCodes.NOT_A_SUPERTYPE
        assertCodes("open class A { open fun f() {} }\nclass B : A() { fun g() { super<String>.hashCode() } }", code)
        assertCodes("interface G<T> { fun foo() {} }\nclass CG : G<Int> { fun t() { super<G<String>>.foo() } }", code)
        clean("interface G<T> { fun foo() {} }\nopen class C { open fun bar() {} }\ntypealias CC = C\n" +
            "class CG : CC(), G<Int> { fun t() { super<G>.foo(); super<G<Int>>.foo(); super<C>.bar(); super<CC>.bar() } }")
    }

    /** A type parameter's name is not a value. */
    @Test
    fun typeParametersAreNotExpressions() {
        val code = KotlinDiagnosticCodes.TYPE_PARAMETER_IS_NOT_AN_EXPRESSION
        assertCodes("fun <T> f() { val x = T }", code)
        clean("fun <T> f(x: T): T { val y: T = x; return y }\ninline fun <reified T> g() = T::class")
    }

    /** An override cannot make its type parameters reified. */
    @Test
    fun reifiedTypeParametersOfOverridesAreReported() {
        val code = KotlinDiagnosticCodes.REIFIED_TYPE_PARAMETER_IN_OVERRIDE
        // The parameter is reified outside an inline function too, which is its own error.
        assertCodes("interface I { fun <T> q(x: T) }\nclass C : I { override fun <reified T> q(x: T) {} }", code, KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
        clean("inline fun <reified T> q(x: T) = x")
    }

    /** A JVM name is a method name the JVM accepts. */
    @Test
    fun illegalJvmNamesAreReported() {
        assertCodes("@JvmName(\"with.dot\") fun foo1() {}", KotlinDiagnosticCodes.ILLEGAL_JVM_NAME)
        clean("@JvmName(\"fine_name\") fun foo2() {}")
    }

    /** A primary constructor with modifiers needs its `constructor` keyword. */
    @Test
    fun primaryConstructorModifiersNeedTheKeyword() {
        assertCodes("class A private (val x: Int)", KotlinDiagnosticCodes.MISSING_CONSTRUCTOR_KEYWORD)
        clean("class A private constructor(val x: Int)\nclass B(val y: Int)")
    }

    /** `@file:` annotations belong before the package directive. */
    @Test
    fun fileTargetsOnDeclarationsAreReported() {
        assertCodes("annotation class Foo\n@file:Foo\nfun f() {}", KotlinDiagnosticCodes.INAPPLICABLE_FILE_TARGET)
    }

    /** `x++` assigns what `inc()` returns, so it cannot be Unit. */
    @Test
    fun incrementsThroughUnitReturningIncAreReported() {
        val code = KotlinDiagnosticCodes.INC_DEC_SHOULD_NOT_RETURN_UNIT
        assertCodes("class U { operator fun inc(): Unit {} }\nfun t() { var x = U(); x++ }", KotlinDiagnosticCodes.INAPPLICABLE_OPERATOR, code)
        clean("class V { operator fun inc(): V = this }\nfun t() { var x = V(); x++; ++x }")
    }

    /** A member extension cannot be referenced with `::`. */
    @Test
    fun referencesToMemberExtensionsAreReported() {
        assertCodes("class A { fun Int.extInt() = 42\n fun main() { Int::extInt } }", KotlinDiagnosticCodes.EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED)
        clean("fun Int.ext() = 1\nclass A { fun main() = Int::ext }")
    }

    /** A getter shares its property's visibility; a setter is at most as visible. */
    @Test
    fun accessorVisibilitiesAreChecked() {
        val code = KotlinDiagnosticCodes.ACCESSOR_VISIBILITY
        assertCodes("class My { private var y: Int = 1\n public set }", code)
        assertCodes("abstract class My { abstract val y: Int\n protected get }", code)
        clean("class My { var x: Int = 0\n private set\n protected var z: Int = 2\n private set\n val w: Int\n public get() = 1 }")
    }

    /** A local variable has no type parameters, and a local function is not inline. */
    @Test
    fun localDeclarationShapesAreChecked() {
        val code = KotlinDiagnosticCodes.LOCAL_DECLARATION_SHAPE
        assertCodes("fun t() { val <T> a0 = 0 }", code)
        assertCodes("fun t() { inline fun a() {} }", code)
    }

    /** A `when` subject declares one `val` with an initializer. */
    @Test
    fun illegalWhenSubjectDeclarationsAreReported() {
        val code = KotlinDiagnosticCodes.ILLEGAL_DECLARATION_IN_WHEN_SUBJECT
        assertCodes("fun foo(): Any = 1\nfun t() { when (var y = foo()) { else -> {} } }", code)
        clean("fun foo(): Any = 1\nfun t() { when (val y = foo()) { is String -> y.length; else -> {} } }")
    }

    /** Only an enum's entries construct it. */
    @Test
    fun enumConstructorCallsAreReported() {
        assertCodes("enum class A(val c: Int) { ONE(1); fun make(): A = A(10) }", KotlinDiagnosticCodes.ENUM_CLASS_CONSTRUCTOR_CALL)
        clean("enum class A(val c: Int) { ONE(1) }\nclass B(val a: Int)\nval b = B(1)")
    }

    /** An inline function cannot call itself. */
    @Test
    fun recursiveInlineFunctionsAreReported() {
        assertCodes("inline fun f(s: (Int) -> Unit) { f(s) }", KotlinDiagnosticCodes.RECURSION_IN_INLINE)
        clean("fun g(n: Int): Int = if (n > 0) g(n - 1) else 0")
    }

    /** A value class cannot contain itself. */
    @Test
    fun recursiveValueClassesAreReported() {
        val code = KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_BE_RECURSIVE
        assertCodes("@JvmInline value class A(val x: B)\n@JvmInline value class B(val x: A)", code, code)
        clean("@JvmInline value class C(val x: List<C>)\n@JvmInline value class D(val x: Int)")
    }

    /** An annotation parameter's default is a constant. */
    @Test
    fun annotationParameterDefaultsMustBeConstant() {
        val code = KotlinDiagnosticCodes.ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT
        assertCodes("val iVal = 42\nannotation class Test4(val x: Int = iVal)", code)
        clean("const val iConst = 42\nannotation class Test2(val x: Int = iConst + 1)\nannotation class T3(val s: Array<String> = arrayOf(\"a\"))")
    }

    /** An anonymous function cannot be `suspend`. */
    @Test
    fun anonymousSuspendFunctionsAreReported() {
        assertCodes("fun t() { suspend fun() {} }", KotlinDiagnosticCodes.ANONYMOUS_SUSPEND_FUNCTION)
    }

    /** `noinline`/`crossinline` belong to function-typed parameters of inline functions. */
    @Test
    fun inlineParameterModifiersAreChecked() {
        val code = KotlinDiagnosticCodes.ILLEGAL_INLINE_PARAMETER_MODIFIER
        assertCodes("inline fun foo(noinline x: Int) {}", code)
        assertCodes("fun gav(noinline x: (Int) -> Unit) {}", code)
        clean("inline fun correct(noinline x: (Int) -> Unit, crossinline y: (String) -> Int, noinline z: (() -> Unit)?) {}")
    }

    /** Property use-site targets apply to properties, `@set:` to mutable ones, `@delegate:` to delegated ones. */
    @Test
    fun propertyUseSiteTargetsAreChecked() {
        val code = KotlinDiagnosticCodes.PROPERTY_USE_SITE_TARGET
        assertCodes("annotation class Ann\nclass C { @set:Ann val p: String = \"\" }", code)
        assertCodes("annotation class Ann\nclass C { @delegate:Ann val p: String = \"\" }", code)
        assertCodes("annotation class Ann\nclass C { val p: Int\n @get:Ann get() = 5 }", code)
        clean("annotation class Ann\nclass C(@set:Ann var a: Int) { @set:Ann var p: String = \"\"; @get:Ann val q = 1; @delegate:Ann val r by lazy { 1 } }")
    }

    /** `c += x` through a `plusAssign` that returns a value. */
    @Test
    fun valueReturningAssignmentOperatorsAreReported() {
        assertCodes(
            "class B { operator fun plusAssign(o: B): String = \"s\" }\nfun t() { val c = B(); c += B() }",
            KotlinDiagnosticCodes.INAPPLICABLE_OPERATOR, KotlinDiagnosticCodes.ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT,
        )
        clean("class B { operator fun plusAssign(o: B) {} }\nfun t() { val c = B(); c += B() }")
    }

    /** A setter's parameter has its property's type. */
    @Test
    fun setterParameterTypesMatchTheProperty() {
        assertCodes("class C { var x: Int = 1\n set(value: Long) {} }", KotlinDiagnosticCodes.WRONG_SETTER_PARAMETER_TYPE)
        clean("class C { var x: Int = 1\n set(value: Int) { field = value }\n var y: String = \"\"\n set(v) { field = v } }")
    }

    /** Every enum entry implements its enum's abstract members. */
    @Test
    fun enumEntriesImplementAbstractMembers() {
        val code = KotlinDiagnosticCodes.ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY
        assertCodes("enum class E { A { override fun f() = 1 }, B; abstract fun f(): Int }", code)
        clean("enum class E { A { override fun f() = 1 }; abstract fun f(): Int }\ninterface I { fun g() }\nenum class F : I { X; override fun g() {} }")
    }

    /** Declaration forms K2 rejects on sight: `::class` alone, an alias to a type parameter or with bounds, a
     *  bodiless anonymous function, accessors on a delegated property, `T<Int>`, `_` as a label or parameter name,
     *  a misplaced `@ExtensionFunctionType`. */
    @Test
    fun smallDeclarationFormsAreReported() {
        assertCodes("fun t() { ::class }", KotlinDiagnosticCodes.UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS)
        assertCodes("typealias ToTypeParam1<T> = T", KotlinDiagnosticCodes.TYPEALIAS_SHOULD_EXPAND_TO_CLASS)
        assertCodes("typealias WithBounds1<T : Number> = List<T>", KotlinDiagnosticCodes.BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED)
        assertCodes("val bas = fun ()", KotlinDiagnosticCodes.NON_MEMBER_FUNCTION_NO_BODY)
        assertCodes("val a: Int by lazy { 1 }\n get() = 1", KotlinDiagnosticCodes.ACCESSOR_FOR_DELEGATED_PROPERTY)
        assertCodes("fun <T> foo(t: T<String>) {}", KotlinDiagnosticCodes.TYPE_ARGUMENTS_NOT_ALLOWED)
        assertCodes("interface I { fun x(a: Int): String }\nclass A : I { override fun x(_: Int) = \"\" }", KotlinDiagnosticCodes.UNDERSCORE_IS_RESERVED)
        assertCodes("fun f6(x: @ExtensionFunctionType () -> Int) {}", KotlinDiagnosticCodes.WRONG_EXTENSION_FUNCTION_TYPE)
        clean("typealias L<T> = List<T>\nval f = fun() {}\nval b: Int by lazy { 1 }\nfun <T> g(t: List<T>) = t\n" +
            "fun h(x: @ExtensionFunctionType (String) -> Int) {}\nfun k() = listOf(1).map { _ -> 1 }")
    }

    /** An inline function's function-typed parameter that may be null cannot be inlined. */
    @Test
    fun nullableInlineParametersAreReported() {
        assertCodes("inline fun test(s: () -> Unit, p: (() -> Unit)?) { s(); p?.invoke() }", KotlinDiagnosticCodes.NULLABLE_INLINE_PARAMETER)
        clean("inline fun test(s: () -> Unit, noinline p: (() -> Unit)?) { s(); p?.invoke() }")
    }

    /** A `var` stays a `var` in its overrides. */
    @Test
    fun varsOverriddenByValsAreReported() {
        assertCodes("open class Var { open var v: Int = 1 }\nclass Val : Var() { override val v: Int = 1 }", KotlinDiagnosticCodes.VAR_OVERRIDDEN_BY_VAL)
        clean("open class A { open val v: Int = 1 }\nclass B : A() { override var v: Int = 2 }")
    }

    /** A Kotlin version string is `major.minor` or `major.minor.patch`. */
    @Test
    fun kotlinVersionStringsAreChecked() {
        assertCodes("@SinceKotlin(\"1\") fun f() {}", KotlinDiagnosticCodes.ILLEGAL_KOTLIN_VERSION_STRING_VALUE)
        clean("@SinceKotlin(\"1.3\") fun g() {}\n@SinceKotlin(\"1.9.20\") fun h() {}")
    }

    /** A function type is an interface: a supertype list cannot call it. */
    @Test
    fun functionTypeSupertypesHaveNoConstructor() {
        assertCodes("abstract class C : Function0<Int>()", KotlinDiagnosticCodes.NO_CONSTRUCTOR)
        clean("abstract class D : () -> Int")
    }

    /** A delegate needs `getValue`, and `setValue` for a `var`. */
    @Test
    fun delegatesWithoutOperatorsAreReported() {
        assertCodes("class A\nval a: Int by A()", KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_MISSING)
        clean("import kotlin.reflect.KProperty\nclass A { operator fun getValue(t: Any?, p: KProperty<*>): Int = 1 }\nval a: Int by A()")
    }

    /** A captured `val` cannot be initialized where the code may run any number of times. */
    @Test
    fun capturedValInitializationIsReported() {
        assertCodes("fun exec(f: () -> Unit) = f()\nfun bar() { val x: Int\n exec { x = 13 } }", KotlinDiagnosticCodes.CAPTURED_VAL_INITIALIZATION)
        clean("fun bar(): Int { val x: Int\n run { x = 13 }\n return x }\nfun baz() { var y: Int\n listOf(1).forEach { y = it } }")
    }

    /** `v += x` on a `var` whose class has both `plus` and `plusAssign` cannot choose. */
    @Test
    fun ambiguousAssignmentOperatorsAreReported() {
        val decls = "class C\noperator fun C.plus(a: Any): C = this\noperator fun C.plusAssign(a: Any) {}\n"
        assertCodes(decls + "fun t() { var c1 = C(); c1 += \"\" }", KotlinDiagnosticCodes.ASSIGN_OPERATOR_AMBIGUITY)
        clean(decls + "fun t() { val c = C(); c += \"\" }")
    }

    /** A protected constructor is reachable from a subclass only through a super call. */
    @Test
    fun protectedConstructorsOutsideSuperCallsAreReported() {
        val decls = "open class A protected constructor(x: Int) { public constructor(x: Double) : this(3) }\n"
        assertCodes(decls + "class B : A(1) { fun foo() { A(1) } }", KotlinDiagnosticCodes.PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL)
        clean(decls + "class B : A(1) { fun foo() { A(5.0); object : A(1) {} } }")
    }

    /** A delegation cycle is followed through parameters passed on, not only literals. */
    @Test
    fun constructorDelegationCyclesThroughParametersAreReported() {
        val code = KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE
        assertCodes("class X<T>(val t: T) { constructor(t: String) : this(t) }", code)
        clean("class Y(val t: Int) { constructor(t: String) : this(t.length) }")
    }
    /** A nullable local or parameter handed to a non-null parameter is a mismatch wherever the null flow finds no
     *  guard, not only on its first use; an explicit non-null type argument (`check<Int>(x)`) takes no null either. */
    @Test
    fun aNullableValueWithNoGuardIsAnArgumentMismatch() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("fun bar(x: Int): Int = x\nfun foo(x: Int?) { if (x != null) bar(x)\n bar(x) }", m)
        assertCodes("fun bar(x: Int): Int = x\nfun foo(y: Int?) { while (y != null) { bar(y) }\n bar(y) }", m)
        clean("fun bar(x: Int): Int = x\nfun foo(x: Int?) { if (x == null) return\n bar(x) }")
        assertCodes("fun <T> chk(t: T) = t\nfun f(x: Int?) { chk(x); chk<Int>(x) }", m)
    }

    /** `id(unresolved)` cannot infer `K` from an error type, also through `!!` or `if` branches whose every value
     *  says nothing; a sibling branch with a real value, or an expected type, does constrain it. */
    @Test
    fun anUnresolvedArgumentLeavesTheVariableUninferred() {
        val infer = KotlinDiagnosticCodes.CANNOT_INFER_TYPE
        assertEquals(2, codes("fun <K> id(arg: K): K = arg\nfun t(b: Boolean) { if (b) id(nope) else id(nope) }").count { it == infer })
        assertEquals(1, codes("fun <K> id(arg: K): K = arg\nfun t() { id(nope)!! }").count { it == infer })
        assertEquals(0, codes("fun <K> id(arg: K): K = arg\nfun t(b: Boolean) { if (b) id(nope) else 5 }").count { it == infer })
        assertEquals(0, codes("fun <K> id(arg: K): K = arg\nfun t(): String = id(nope)").count { it == infer })
    }

    /** RETURN/PROPERTY/VAR_TYPE_MISMATCH_ON_INHERITANCE: a member reaching a class from two supertypes with types
     *  no one of them can stand for, or whose single implementation does not fit the other's type. */
    @Test
    fun inheritedMembersWithClashingTypes() {
        val clash = KotlinDiagnosticCodes.INHERITANCE_TYPE_MISMATCH
        assertCodes("open class A { open fun foo(): Boolean = true }\ninterface IA { fun foo(): String }\nclass B1 : A(), IA", clash)
        clean("open class A { open fun foo(): Boolean = true }\ninterface IG<T> { fun foo(): T }\nclass B : A(), IG<Any>")
        assertCodes("open class A { open var foo: Boolean = true }\ninterface IG<T> { var foo: T }\nclass B : A(), IG<Any>", clash)
        assertCodes(
            "interface IFooAny { val foo: Any }\ninterface IFooStr : IFooAny { override val foo: String }\n" +
                "abstract class BaseAny(override val foo: Any) : IFooAny\nabstract class BaseStr : BaseAny(42), IFooStr",
            clash,
        )
        clean(
            "interface IBase { fun copy(): IBase }\ninterface ILeft : IBase { override fun copy(): ILeft }\n" +
                "interface IRight : IBase { override fun copy(): IRight }\ninterface IDerived : ILeft, IRight { override fun copy(): IDerived }\n" +
                "abstract class C : ILeft, IRight, IDerived",
        )
    }

    /** INCONSISTENT_TYPE_PARAMETER_VALUES: one generic supertype reached with two different arguments. */
    @Test
    fun aSupertypeReachedWithTwoArgumentsIsInconsistent() {
        val code = KotlinDiagnosticCodes.INCONSISTENT_TYPE_PARAMETER_VALUES
        assertCodes("interface X<T>\ninterface A : X<String>\ninterface B : A, X<Int>", code)
        clean("interface X<T>\ninterface A : X<String>\ninterface B : A, X<String>")
    }

    /** A constructor delegation call's arguments against the constructors it can reach: `this(...)` keeps the class's
     *  own type parameters, `super(...)` takes the supertype entry's arguments. */
    @Test
    fun constructorDelegationArgumentsAreTyped() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("open class B<R1, R2>(x: R1, y: R2)\nclass A1<T1, T2> : B<T1, T2> {\n constructor(x: T1, y: Int) : super(x, y)\n}", m)
        clean("open class B<R1, R2>(x: R1, y: R2)\nclass A1<T1, T2> : B<T1, T2> {\n constructor(x: T1, y: T2) : super(x, y)\n}")
        assertCodes("open class A(x: Int)\nclass B : A(\"\")", m)
        clean("open class A(x: Long)\nclass B : A(1)")
    }

    /** A type argument or an argument whose type parameter's bound rules it out, the bounds of the type parameters
     *  in scope taken into account. */
    @Test
    fun typeParameterArgumentsOutsideTheirBound() {
        assertCodes("interface A<T : CharSequence>\nfun <S : CharSequence?> foo1(a: A<S>) {}", KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED)
        clean("interface A<T : CharSequence>\nfun <S : CharSequence> foo1(a: A<S>) {}")
        val c = codes("fun <E : CharSequence> foo1(x: E) {}\nfun <F : String?> bar(x: F) { foo1(x) }")
        assertTrue(KotlinDiagnosticCodes.CANNOT_INFER_TYPE in c && KotlinDiagnosticCodes.TYPE_MISMATCH in c, "got $c")
        clean("fun <E : CharSequence> foo1(x: E) {}\nfun <F : String> bar(x: F) { foo1(x) }")
    }

    /** A value whose written type differs from the expected one in a type argument the variance does not admit. */
    @Test
    fun typeArgumentsFollowVariance() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("abstract class Consumer<in T>\nfun foo(c: Consumer<Int>) { val c1: Consumer<Any> = c }", m)
        clean("abstract class Consumer<in T>\nfun foo(c: Consumer<Any>) { val c1: Consumer<Int> = c }")
        assertCodes("abstract class Usual<T>\nfun foo(u: Usual<Int>) { val u1: Usual<Any> = u }", m)
        clean("abstract class Producer<out T>\nfun foo(p: Producer<Int>) { val p1: Producer<Any> = p }")
    }

    /** A lambda or anonymous function given a written function type: what it returns (no `Unit` coercion for an
     *  anonymous function's expression body), `return@label` with no value, an assignment as its last statement. */
    @Test
    fun functionLiteralsAgainstTheirFunctionType() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("var f1: () -> Int = { 1 }\nfun t() { f1 = { \"\" } }", m)
        assertCodes("val a: () -> Unit = (fun() = \"1\")", m)
        clean("val a: () -> Unit = fun() {}")
        assertCodes("val a: () -> Int = fun() {}", m)
        assertCodes("val bag: (Int) -> Unit = fun (param: String) {}", m)
        clean("val bas: (String) -> Unit = fun (param: String) {}")
        assertCodes("val e: () -> Any = l@ {\n if (\"0\".hashCode() == 42) return@l\n \"\"\n}", m)
        clean("val e: () -> Any = l@ {\n if (\"0\".hashCode() == 42) return@l Unit\n \"\"\n}")
        assertCodes("fun t() { var bar = 4\n val b: () -> Int = { bar = 4 } }", m)
        clean("fun t() { var bar = 4\n val a: () -> Unit = { bar += 4 } }")
    }

    /** A lambda argument against the plain function type its parameter declares: no implicit `it` for two or more
     *  parameters, no empty body where a value is due, and the value its last statement gives. */
    @Test
    fun lambdaArgumentsAgainstDeclaredFunctionTypes() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("fun bar(f: (Int, Int) -> Int) {}\nfun t() { bar { 1 } }", m)
        clean("fun bar(f: (Int, Int) -> Int) {}\nfun t() { bar { a, b -> a + b } }")
        assertCodes("fun bar2(f: () -> Int) {}\nfun t() { bar2 {} }", m)
        assertCodes("fun bar2(f: () -> Int) {}\nfun t() { bar2 { \"\" } }", m)
        clean("fun bar2(f: () -> Int) {}\nfun t() { bar2 { 1 } }")
        clean("fun run2(f: () -> Unit) {}\nfun t() { run2 { \"\" } }")
    }

    /** In named form a vararg takes an array: a single element is a mismatch. */
    @Test
    fun aSingleElementForANamedVarargIsAMismatch() {
        assertCodes("fun foo(vararg ints: Int) {}\nfun t() { foo(ints = 1) }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean("fun foo(vararg ints: Int) {}\nfun t() { foo(ints = intArrayOf(1)); foo(1, 2) }")
        assertCodes("fun join(x: Int, vararg a: String) {}\nfun t(b: Array<Int>) { join(x = 1, a = b) }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean("fun foo2(p1: Int, vararg p2: String, p3: Double) {}\nfun t() { foo2(1, p3 = 3.0); foo2(1, p2 = arrayOf(\"2\"), p3 = 4.0) }")
        assertCodes("val f = fun(a: Int): String {\n if (a == 2) return 2\n return \"\"\n}", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** Both operands of `==` are resolved with no expected type, and a variable the result type does not mention
     *  is never inferred from one. */
    @Test
    fun equalityOperandsAndResultSilentVariablesAreNotInferred() {
        val infer = KotlinDiagnosticCodes.CANNOT_INFER_TYPE
        assertEquals(1, codes("interface A<T>\nfun <T> newA(): A<T> = TODO()\nfun t(x: Any) { x == newA() }").count { it == infer })
        assertEquals(2, codes("fun <T> toBeOrNot(): Boolean = TODO()\nfun t() { if (toBeOrNot() && toBeOrNot()) {} }").count { it == infer })
        clean("fun <T> toBeOrNot(): Boolean = TODO()\nfun t() { if (toBeOrNot<Int>()) {} }")
    }

    /** `::ext` names an extension, which a callable reference can only reach through its receiver type. */
    @Test
    fun aReferenceToAnExtensionNeedsItsReceiver() {
        assertCodes("fun <K> K.bar3(): K = this\nfun t() { val inv = ::bar3 }", KotlinDiagnosticCodes.UNRESOLVED)
        clean("fun String.bar3(): String = this\nfun t() { val inv = String::bar3 }")
        clean("fun bar2(): Int = 1\nfun t() { val inv = ::bar2 }")
    }

    /** A `return` is judged against what it leaves: a secondary constructor or setter returns `Unit`, a getter its
     *  property's type, and a non-local return through a lambda the enclosing function's written type. */
    @Test
    fun returnsAreJudgedAgainstWhatTheyLeave() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("class A {\n constructor() { if (1 == 1) return 1 }\n}", m)
        assertCodes("class A { val z: Int\n get() { return \"s\" } }", m)
        assertCodes("class A { val x: Int get() = \"s\" }", m)
        clean("class A { val x: Int get() = 1\n val y: Int\n get() { return 2 } }")
        assertCodes("fun test(): Int = run { return \"\" }", m)
        clean("fun test(): Int = run { return 1 }")
    }

    /** A class may not inherit both a plain and a suspend function type, directly or through a supertype of this
     *  file; one kind alone is fine. */
    @Test
    fun suspendAndNonSuspendFunctionSupertypesDoNotMix() {
        val m = KotlinDiagnosticCodes.MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES
        assertCodes("abstract class C : suspend () -> Unit, () -> Unit", m)
        assertCodes("interface ISuper : () -> Unit\ninterface I : suspend () -> Unit, ISuper", m)
        assertCodes("import kotlin.coroutines.*\ninterface I : SuspendFunction0<Unit>, () -> Unit", m)
        clean("interface I : suspend () -> Unit\ninterface J : () -> Unit, (Int) -> Unit")
        clean("interface SuspendFunction0<T>\ninterface I : SuspendFunction0<Unit>, () -> Unit")
    }

    /** UNSUPPORTED shapes: a function type's parameter with a default, a modifier or (when named) an annotation;
     *  a named parameter of a function type written as a supertype; `dynamic`; a local type alias; delegation in a
     *  class without a primary constructor; a callable reference to a local variable. */
    @Test
    fun unsupportedConstructsAreReported() {
        val u = KotlinDiagnosticCodes.UNSUPPORTED
        assertCodes("val f: (x: Int = 0) -> Unit = {}", u)
        assertCodes("fun g(fn: (vararg x: Int) -> Unit) {}", u)
        assertCodes("annotation class Ann\nfun g(fn: (@Ann x: Int) -> Unit) {}", u)
        assertCodes("abstract class C : (x: Int) -> Unit", u)
        clean("abstract class C : (Int) -> Unit\nfun g(fn: (x: Int) -> Unit, h: ((k: String) -> Unit) -> Unit) {}")
        assertCodes("val foo: dynamic = 1", u)
        assertCodes("interface A\nclass AImpl : A\nclass B : A by AImpl() {\n constructor()\n}", u)
        clean("interface A\nclass AImpl : A\nclass B : A by AImpl()")
        assertCodes("fun eat(a: Any) {}\nfun test(param: String) {\n eat(::param)\n}", u)
        clean("fun eat(a: Any) {}\nfun top() {}\nfun test() {\n eat(::top)\n}")
    }

    /** An annotation's constructor parameters without a default need an argument, positional or named; a vararg
     *  takes every positional argument from it on. */
    @Test
    fun annotationArgumentsFillTheirParameters() {
        val n = KotlinDiagnosticCodes.NO_VALUE_FOR_PARAMETER
        assertCodes("annotation class My1(val i: Int)\n@My1 fun foo() {}", n)
        clean("annotation class My1(val i: Int)\nannotation class My2(val i: Int = 0)\n@My1(2) fun a() {}\n@My2 fun b() {}\n@My1(i = 1) fun c() {}")
        assertCodes("annotation class AnnVarargs(val x: Int, vararg val y: String, val z: Int)\n@AnnVarargs(1, \"a\", \"b\") class B", n)
        clean("annotation class AnnVarargs(val x: Int, vararg val y: String, val z: Int)\n@AnnVarargs(1, \"a\", z = 2) class B")
        assertCodes("enum class E(val i: Int) {\n A(12),\n B\n}", n)
        clean("enum class E(val x: String = \"OK\") {\n TEST1,\n TEST2(),\n TEST3(\"Hello\")\n}")
    }

    /** A reference inside an implicitly typed body to a declaration whose implicit type needs that body again is a
     *  recursive problem; a written type anywhere on the cycle breaks it. */
    @Test
    fun implicitTypesThatDependOnThemselvesAreRecursive() {
        val r = KotlinDiagnosticCodes.RECURSIVE_IMPLICIT_TYPE
        assertCodes("fun foo() = bar()\nfun bar() = foo()", r, r)
        assertTrue(r in codes("val z = z"))
        assertCodes("fun fact(n: Int) = if (n < 2) 1 else n * fact(n - 1)", r)
        assertCodes("class A {\n val a get() = b\n val b get() = a\n}", r, r)
        clean("fun foo(): Int = bar()\nfun bar() = foo()")
        clean("fun fact(n: Int): Int = if (n < 2) 1 else n * fact(n - 1)")
        clean("fun foo(x: Int) = x\nfun bar() = foo(1)")
        clean("fun bar(s: String) = s.length\nfun t() = with(\"x\") { bar(this) }")
        assertCodes(
            "class Scope\nfun <T> simpleAsync0(block: Scope.() -> T) {}\nfun insideJob0() = doTheJob0()\nfun doTheJob0() = simpleAsync0 { insideJob0() }",
            r, r,
        )
    }

    /** A callable reference's or class literal's left side gives a class exactly its own type parameters. */
    @Test
    fun typeArgumentsLeftOfDoubleColonMatchTheClass() {
        val n = KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT
        assertCodes("open class X<A> {\n inner class Y<B> { fun foo() {} }\n val ref = Y<Int, Int>::foo\n}", n)
        assertCodes("class L\nval k = L<Int>::class", n)
        clean("class G<T> { fun foo() {} }\nval r = G<Int>::foo\nval k = G::class")
    }

    /** Overloads compare by their types as K2 sees them: a type parameter renamed, bounds reordered, an alias
     *  expanded or an annotation added is the same signature; a data class's generated members count too. */
    @Test
    fun overloadSignaturesCompareModuloRenamingAndAliases() {
        val c = KotlinDiagnosticCodes.CONFLICTING_DECLARATION
        assertCodes("fun <T1> test1(x: List<T1>) = x\nfun <T2> test1(x: List<T2>) = x", c, c)
        assertCodes("interface I1\ninterface I2\nfun <T> foo(t: T) where T : I1, T : I2 {}\nfun <T> foo(t: T) where T : I2, T : I1 {}", c, c)
        assertCodes("class K\ntypealias SameK = K\nfun f(a: K) {}\nfun f(a: SameK) {}", c, c)
        clean("fun <T, R> m(a: Map<T, R>) {}\nfun <T, R> m(a: Map<R, T>) {}")
        clean("interface I1\ninterface I2\nfun <T : I1> g(a: List<T>) {}\nfun <T : I2> g(a: List<T>) {}")
        assertCodes("data class A(val x: Int, val y: String) {\n fun component1() = 1\n}", c, c)
        assertCodes("class C(val x: Int)\ntypealias CC = C\nfun CC(x: Int) = x", c, c)
        assertCodes("class D(val x: List<String>)\nfun D(x: List<String>) = x", c, c)
        assertCodes("interface T1 {\n fun <T> foo(t: T) where T : I1, T : I2\n @Deprecated(\"foo\", level = DeprecationLevel.HIDDEN)\n fun <T> foo(t: T) where T : I2, T : I1\n}\ninterface I1\ninterface I2", c, c)
        clean("data class A(val x: Int, val y: String) {\n fun component3() = 1\n fun copy(x: Int) = x\n}")
    }

    /** An extension function's receiver is an implicit receiver with its class's DSL markers: a call through it
     *  from inside a lambda whose receiver carries the same marker violates the DSL scope. */
    @Test
    fun anExtensionReceiverTakesPartInDslScopes() {
        val prelude = "@DslMarker annotation class Ann\n@Ann class A { fun a() = 1 }\n@Ann class B { fun b() = 2 }\nfun bar(x: B.() -> Unit) {}\n"
        assertCodes(prelude + "fun A.test() { bar { a() } }", KotlinDiagnosticCodes.DSL_SCOPE_VIOLATION)
        clean(prelude + "fun A.test() { bar { this@test.a(); b() } }")
    }

    /** An inferred type is exposed like a written one: a public function returning a constructor call of a private
     *  class exposes that class. */
    @Test
    fun inferredTypesCanExposeLessVisibleClasses() {
        val e = KotlinDiagnosticCodes.EXPOSED_VISIBILITY
        assertCodes("private class A\nfun makeA() = A()", e)
        assertCodes("internal class My\nclass C { val x = My() }", e)
        clean("private class A\nprivate fun makeA() = A()\ninternal class My\ninternal val x = My()")
        clean("private class A\nfun makeA(): Any = A()")
    }

    /** `B() += 1` needs a variable when `B` has `plus` but no `plusAssign`; an object is not a variable for `++`. */
    @Test
    fun compoundAssignmentsAndIncrementsNeedVariables() {
        val v = KotlinDiagnosticCodes.VARIABLE_EXPECTED
        assertCodes("class B { operator fun plus(x: Int): B = B() }\nfun t() { B() += 1 }", v)
        clean("class A { operator fun plusAssign(x: Int) {} }\nfun t() { A() += 1 }")
        assertCodes("object AAA { operator fun inc(): AAA = this }\nfun t() { AAA++ }", v)
    }

    /** `Enum`'s `name`, `ordinal`, `equals`, `hashCode`, `compareTo` are final, and so is an enum's own member that
     *  is not `open`: an entry cannot override it. */
    @Test
    fun enumFinalMembersCannotBeOverridden() {
        val f = KotlinDiagnosticCodes.OVERRIDING_FINAL
        assertCodes("enum class E {\n ENTRY;\n override fun hashCode() = -1\n}", f)
        assertCodes("enum class E {\n E2 { override fun f() = 3 };\n fun f() = 2\n}", f)
        clean("enum class E {\n E1 { override fun foo() = 1 };\n open fun foo() = 1\n override fun toString() = \"e\"\n}")
    }

    /** A non-private property delegated to `lazy { object : A, B {} }` has the object's ambiguous type. */
    @Test
    fun lazyObjectsWithTwoSupertypesAreAmbiguous() {
        val prelude = "interface A\ninterface B\n"
        assertCodes(prelude + "val p by lazy { object : A, B {} }", KotlinDiagnosticCodes.FUNCTION_SHAPE)
        clean(prelude + "private val p by lazy { object : A, B {} }\nval q by lazy { object : A {} }")
    }

    /** A file's own non-inline `run` takes a bare `run { }` before the stdlib's inline one, so a `return` in its
     *  lambda cannot leave the enclosing function. */
    @Test
    fun aSamePackageNonInlineFunctionShadowsTheStdlibInlineOne() {
        assertCodes("fun test() {\n run { return }\n}\nfun <T> run(f: () -> T): T { return f() }", KotlinDiagnosticCodes.RETURN_NOT_ALLOWED)
        clean("fun test() {\n run { return }\n}")
    }

    /** A `when` used as a value over a subject written as `Any` or `CharSequence` is exhaustive only through an
     *  `is` of that type (or a supertype), nullable included when the subject is. */
    @Test
    fun whenOverAnOpenTypeNeedsAnElse() {
        val w = KotlinDiagnosticCodes.WHEN_EXHAUSTIVE
        assertCodes("fun t(x: CharSequence): Int = when (x) { is String -> 1 }", w)
        assertCodes("fun t(x: CharSequence?): Int = when (x) { is Any -> 1 }", w)
        clean("fun t(x: CharSequence): Int = when (x) { is CharSequence -> 1 }")
        clean("fun t(x: CharSequence?): Int = when (x) { is Any? -> 1 }")
        clean("fun t(x: Any) { when (x) { is String -> println() } }")
    }

    /** A value class is final, extends no class, stores exactly one final `val`, and keeps no other state. */
    @Test
    fun valueClassesHaveTheirShape() {
        clean("@JvmInline value class A0(val x: Int)\ninterface I\n@JvmInline value class A1(val x: I) : I by x\n@JvmInline value class A2(val x: Int) { val y get() = x }")
        assertCodes("@JvmInline value class A3(var x: Int)", KotlinDiagnosticCodes.VALUE_CLASS_CONSTRUCTOR_NOT_FINAL_READ_ONLY_PARAMETER)
        assertCodes("@JvmInline value class A5(val x: Int, val y: Int)", KotlinDiagnosticCodes.INLINE_CLASS_CONSTRUCTOR_WRONG_PARAMETERS_SIZE)
        assertCodes("@JvmInline value class A6(val x: Int) { val a1 = 0 }", KotlinDiagnosticCodes.PROPERTY_WITH_BACKING_FIELD_INSIDE_VALUE_CLASS)
        assertCodes("open class Base\n@JvmInline value class A7(val x: Int) : Base()", KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_EXTEND_CLASSES)
        assertCodes("@JvmInline value class A8(val u: Unit)", KotlinDiagnosticCodes.VALUE_CLASS_HAS_INAPPLICABLE_PARAMETER_TYPE)
    }

    /** A local `val` read before it is assigned is reported in an accessor's or an `init` block's body too. */
    @Test
    fun uninitializedLocalsInAccessorsAndInitializers() {
        val u = KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE
        assertCodes("val a: Int\n get() {\n val b: Int\n return b\n }", u)
        assertCodes("class A {\n init {\n val b: Int\n println(b)\n }\n}", u)
        clean("val a: Int\n get() {\n val b: Int\n b = 1\n return b\n }")
    }

    /** A function whose implicit return type is `Nothing` through a `run { ... }` that never completes. */
    @Test
    fun runThatNeverCompletesMakesAnImplicitNothing() {
        val n = KotlinDiagnosticCodes.FUNCTION_SHAPE
        assertTrue(n in codes("fun test1() = run {\n return \"OK\"\n}"))
        assertTrue(n !in codes("fun test1() = run { if (true) return@run 1; throw Exception() }"))
    }

    /** A reference to a generic callable that nothing gives an expected type cannot infer its type arguments, and a
     *  local holding a join of only failed references has an error type that cannot be invoked. */
    @Test
    fun genericCallableReferencesNeedAnExpectedType() {
        val c = KotlinDiagnosticCodes.CANNOT_INFER_TYPE
        val decls = "fun <K> bar4(): K = null!!\nfun <K> foo4(): K = null!!\nfun bar2(): Int = 1\nclass Foo7<T>\nfun foo7(): Foo7<Int> = Foo7()\n"
        assertCodes(decls + "fun t() { val inv = ::bar4; inv }", c)
        assertCodes(decls + "fun t() { val inv = ::Foo7; inv }", c)
        assertCodes(decls + "fun t(f: Boolean) { val inv = if (f) { foo7() } else { ::Foo7 }; inv }", c)
        assertCodes(decls + "fun t(f: Boolean) { val inv = if (f) { ::bar4 } else { ::foo4 }; inv() }", c, c, KotlinDiagnosticCodes.UNRESOLVED)
        clean(decls + "fun t(f: Boolean) { val inv = if (f) { ::bar2 } else { ::foo4 }; inv() }")
        clean(decls + "fun t() { val inv: () -> Int = ::bar4; val f: () -> Foo7<Int> = ::Foo7; inv(); f() }")
        clean(decls + "fun take(f: () -> Int) {}\nfun t() { take(::bar4) }")
    }

    /** A lambda whose input type is a bare type variable nothing constrains (no other argument, no explicit type, no
     *  other lambda's result) cannot be analyzed: K2 reports the callee and the lambda or its untyped parameter. */
    @Test
    fun lambdaInputsNeedAProperConstraint() {
        val c = KotlinDiagnosticCodes.CANNOT_INFER_TYPE
        val decls = "fun <T, R> bar(f: (T) -> R): R = null!!\nfun <T, R> inter(x: (T) -> R, y: (R) -> T) {}\n"
        assertCodes(decls + "fun t() { bar { true } }", c, c)
        assertCodes(decls + "fun t() { bar { x -> x + 1 } }", c, c)
        assertCodes(decls + "fun t() { inter({ 10 }, { x -> x }) }", c, c, c)
        clean(decls + "fun t() { bar { x: Int -> x + 1 } }")
        clean(decls + "fun take(i: Int) {}\nfun t() { bar { x -> take(x) } }".replace("bar { x -> take(x) }", "bar<Int, Unit> { x -> take(x) }"))
        clean("fun <T> dep(x: (T) -> Int, y: (Int) -> T) {}\nfun t() { dep({ it }, { it }) }")
    }

    /** Only an array can be spread into a vararg, and its elements must fit; in named form a single element never
     *  fills a vararg, whatever its element type. */
    @Test
    fun spreadArgumentsMustBeFittingArrays() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        val decls = "fun join(x: Int, vararg a: String): String = \"\"\nfun <T> joinG(x: Int, vararg a: T): String = \"\"\n"
        assertCodes(decls + "fun t() { join(1, *\"2\") }", m)
        assertCodes(decls + "fun t(b: Array<Int>) { join(1, *b) }", m)
        assertCodes(decls + "fun t() { joinG<String>(x = 1, a = \"2\") }", m)
        clean(decls + "fun t(a: Array<String>) { join(1, *a, \"3\"); joinG(1, *a); join(x = 1, a = a) }")
    }

    /** A value of an open declared type that no check narrows at the use is what it is declared: `checkSubtype<Int>(x)`
     *  with `x: Number` after a `when` whose `is Int` branch does not reach past it. A `when` condition that casts
     *  narrows for good, since the first condition always runs. */
    @Test
    fun anOpenDeclaredTypeIsReadOffTheTreeWhenNothingNarrowsIt() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        val helper = "fun <T> checkSubtype(t: T) = t\n"
        assertCodes(helper + "fun f(x: Number) { when (x) { is Int -> checkSubtype<Int>(x); else -> {} }\n checkSubtype<Int>(x) }", m)
        clean(helper + "fun f(x: Number) { when (x) { x as Int -> checkSubtype<Int>(x); else -> {} }\n checkSubtype<Int>(x) }")
        clean(helper + "fun f(x: Number) { if (x !is Int) return\n checkSubtype<Int>(x) }")
    }

    /** An anonymous object escaping through a non-private declaration, or a private `inline` one, is seen as its
     *  supertype, also nested in a type argument; a private non-inline declaration keeps it. */
    @Test
    fun escapingAnonymousObjectsAreApproximated() {
        val u = KotlinDiagnosticCodes.UNRESOLVED
        val decls = "class Inv<T>(val value: T)\nopen class MyClass\n"
        assertCodes(decls + "private inline fun foo(crossinline f: () -> Int) = object { fun bar(): Int = f() }\nfun t() { foo { 1 }.bar() }", u)
        assertCodes(decls + "fun foo() = Inv(object { fun bar() = 1 })\nfun t() { foo().value.bar() }", u)
        assertCodes(decls + "class Foo { val p = object : MyClass() { fun only() {} }\n init { p.only() } }", u)
        clean(decls + "private fun foo() = object { fun bar() = 1 }\nfun t() { foo().bar() }")
        clean(decls + "class Foo { private val p = object : MyClass() { fun only() {} }\n init { p.only() } }")
    }

    /** A lambda with a bare `return@label` and no expected result type returns `Unit`, so returning a value from it
     *  elsewhere is a mismatch; a function type that fixes the result keeps its own rule. */
    @Test
    fun aBareLabeledReturnMakesALambdaUnit() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("fun test(a: Int) {\n run f@{\n if (a > 0) return@f\n else return@f 1\n }\n}", m)
        assertCodes("val flag = true\nval a = b@ {\n if (flag) return@b 4\n return@b\n}", m)
        clean("fun test(a: Int) {\n run f@{\n if (a > 0) return@f\n 5\n }\n}")
        clean("fun test(a: Int): Int = run f@{\n if (a > 0) return@f 2\n 5\n}")
    }

    /** `::f` naming two overloads with nothing to choose between them is ambiguous; an expected function type picks. */
    @Test
    fun anOverloadedReferenceNeedsAnExpectedType() {
        val decls = "fun foo(x: Int, y: Any) = x\nfun foo(x: Any, y: Int) = y\n"
        assertCodes(decls + "fun main() {\n ::foo\n}", KotlinDiagnosticCodes.OVERLOAD_AMBIGUITY)
        clean(decls + "fun main() {\n val f: (Int, Any) -> Int = ::foo\n f(1, 2)\n}")
    }

    /** A function literal whose expected type is a written non-function type argument gives its untyped parameters no
     *  type: every one of an anonymous function, the last of a lambda. */
    @Test
    fun untypedParametersNeedAFunctionTypeExpected() {
        val c = KotlinDiagnosticCodes.CANNOT_INFER_TYPE
        val decls = "fun <T> select(vararg x: T) = x[0]\nfun <K> id(x: K) = x\n"
        assertCodes(decls + "fun t() { id<Any?>(fun (x) {}) }", c)
        assertCodes(decls + "fun t() { select<Any?>(id { x, y -> }, fun (x: Int, y) {}) }", c, c)
        clean(decls + "fun t() { id<(Int) -> Unit>(fun (x) {}); select<Any?>(fun (x: Int) {}) }")
    }

    /** A class whose supertype is a NESTED class (`class C : O.B()`) still has its body checked: the qualified
     *  supertype resolves in the scope around the class, and only superclasses' own nested types are in scope. */
    @Test
    fun aNestedSupertypeKeepsTheBodyChecked() {
        val u = KotlinDiagnosticCodes.UNRESOLVED
        val decls = "class O { open class A { class FromA; companion object { class FromCompanionA } }; open class B : A() }\n"
        assertCodes(decls + "class C : O.B() { val c = FromA(); val a = A(); val e = FromCompanionA(); val z = Zzz() }", u, u, u)
        clean(decls + "class D : O.B() { val c = FromA() }")
    }

    /** One inapplicable candidate reports all of its problems: a missing parameter and each mismatched argument,
     *  vararg elements included (but not a positional argument placed after a named vararg). */
    @Test
    fun anInapplicableCallReportsEveryArgument() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        val n = KotlinDiagnosticCodes.ARGUMENT_COUNT
        val bar = "fun bar(first: Int, second: Double = 2.71, third: Boolean, fourth: String = \"\") {}\n"
        assertEquals(listOf(n, m).sorted(), codes(bar + "fun t() { bar(1, true) }").map { it.orEmpty() }.sorted())
        assertCodes("fun fv(x: Int, vararg y: String) {}\nfun t() { fv(1, 2); fv(1, \"a\", \"b\") }", m)
        val afterNamed = codes("fun foo2(p1: Int, vararg p2: String, p3: Double) {}\nfun t() { foo2(1, p2 = arrayOf(\"2\", \"3\"), 4.0) }")
        assertTrue(m !in afterNamed, "a positional argument after a named vararg is not a vararg element: $afterNamed")
    }

    /** A labeled lambda's `return@l value` is not coerced to `Unit`, and an `if` with no `else` ending a lambda is a
     *  `Unit` statement, whatever its branch holds. */
    @Test
    fun lambdaResultsAgainstTheirFunctionType() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        val decls = "val flag = true\n"
        assertCodes(decls + "val b: () -> Unit = l@ { if (flag) return@l 4 }", m)
        assertCodes(decls + "val e: () -> Int = l@ { if (flag) 4 }", m)
        clean(decls + "val c: () -> Any = l@ { if (flag) return@l 4 }\nval d: () -> Int = l@ { if (flag) return@l 4; 5 }\nval u: () -> Unit = l@ { if (flag) return@l }")
    }

    /** A convention operator applied to a nullable receiver is an unsafe call unless an extension takes the
     *  nullable receiver or a null check covers it. */
    @Test
    fun operatorsOnNullableReceivers() {
        val u = KotlinDiagnosticCodes.UNSAFE_NULLABLE
        val decls = "class A { operator infix fun plus(i: Int) {}; operator fun unaryMinus() {}; operator fun contains(a: Any?): Boolean = true }\n" +
            "operator infix fun A?.times(i: Int) {}\n"
        assertCodes(decls + "fun t(a: A?, b: Boolean?) { a + 1; a plus 1; 1 in a; -a; !b }", u, u, u, u, u)
        clean(decls + "fun t(a: A?, b: Boolean?) { a * 1; if (a != null) { a + 1; -a }; if (b != null) !b }")
    }

    /** A narrowing made before a loop does not hold inside a loop that reassigns the variable, nor after a later
     *  reassignment of another type; a branch that assigns a value of the narrowed type keeps it. */
    @Test
    fun reassignmentsEndANarrowing() {
        val u = KotlinDiagnosticCodes.UNRESOLVED
        assertCodes("fun t() { var x: Any? = \"\"; require(x is String); while (true) { x.length; x = 10 } }", u)
        assertCodes("fun t() { var x: Any? = \"\"; require(x is String); x = 10; x.length }", u)
        clean("fun foo(x: String) = x\nfun t() { var c: Any? = \"X\"; if (c !is String) return; val n: String? = \"Y\"; if (n != null) { c = n }; foo(c) }")
        clean("fun t(b: Boolean) { var x: Any = \"\"; x as String; while (b) { x.length; var x: Any = \"h\"; x = 1 }; x.length }")
    }

    /** An annotation's arguments are checked against its parameters like a constructor call's. */
    @Test
    fun annotationArgumentsAreTyped() {
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertCodes("annotation class Bad(val s: String)\n@Bad(1) object O", m)
        assertCodes("enum class MyLevel { ERROR }\n@Deprecated(\"\", level = MyLevel.ERROR) fun f() {}", m)
        clean("annotation class Arr(val xs: Array<String>, vararg val ys: Int)\n@Arr([\"a\"], 1, 2) class A\n@Arr(arrayOf(\"a\"), 1) class B\n" +
            "@Suppress(\"x\", \"y\") class C\n@Deprecated(\"\", level = DeprecationLevel.ERROR) fun g() {}")
    }

    /** The type arguments on the left of `::` are held against their bounds; a value that is a function-typed
     *  parameter with a receiver is still an unsafe implicit invoke on a nullable receiver. */
    @Test
    fun callableReferenceLhsBoundsAndNullableInvokes() {
        val b = KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED
        val decls = "class Foo<T : Number> { val value: String = \"OK\" }\nclass Bar<T> { val v = 1 }\n"
        assertCodes(decls + "val a = Foo<String>::value", b)
        clean(decls + "val a = Foo<Int>::value\nval c = Bar<String?>::v")
        assertCodes("fun bar(doIt: Int.() -> Int) { val i: Int? = 1; i.doIt() }", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
    }

    /** A getter of a `val` may read its backing field but not assign it, also from a local function inside. */
    @Test
    fun aValGetterCannotAssignItsBackingField() {
        val c = KotlinDiagnosticCodes.VAL_REASSIGNMENT_VIA_BACKING_FIELD
        assertCodes("val my: Int = 1\n get() {\n  field++\n  return field\n }", c)
        assertCodes("class A {\n val j: Int = 4\n  get() {\n   fun local() { field = 2 }\n   local()\n   return field\n  }\n}", c)
        clean("class A {\n var i: Int = 1\n  get() {\n   field++\n   return field\n  }\n}")
    }

    /** `@Foo` must name an annotation class; `@T` with a type parameter names no class at all. */
    @Test
    fun annotationsMustNameAnnotationClasses() {
        assertCodes("class Foo\n@Foo class Bar", KotlinDiagnosticCodes.NOT_AN_ANNOTATION_CLASS)
        assertCodes("class Foo<T> {\n @T\n fun foo() = 0\n}", KotlinDiagnosticCodes.NOT_A_CLASS)
        clean("annotation class Ann\n@Ann class Bar")
    }

    /** An `else` branch is the last one of a `when`; `else if` is a guard, not that branch. */
    @Test
    fun elseIsTheLastBranchOfAWhen() {
        assertCodes("fun f(x: Int) = when (x) {\n else -> 1\n 2 -> 2\n}", KotlinDiagnosticCodes.ELSE_MISPLACED_IN_WHEN)
        clean("fun f(x: Int) = when (x) {\n 2 -> 2\n else -> 1\n}")
    }

    /** A `data object` generates `equals` and `hashCode` and may not override them. */
    @Test
    fun aDataObjectKeepsItsEqualsAndHashCode() {
        val c = KotlinDiagnosticCodes.DATA_OBJECT_CUSTOM_EQUALS_OR_HASH_CODE
        assertCodes("data object O {\n override fun equals(other: Any?): Boolean = true\n override fun hashCode(): Int = 1\n}", c, c)
        clean("data object O {\n fun equals(other: Any?, tag: Int): Boolean = true\n override fun toString() = \"O\"\n}")
    }

    /** Accessor shapes: no private bodiless interface function, no setter on a `val`, a setter returns `Unit`, a
     *  getter returns the property's type, and type parameters go before a function's name. */
    @Test
    fun accessorAndFunctionShapes() {
        assertCodes("interface I {\n private fun foo(): Int\n}", KotlinDiagnosticCodes.PRIVATE_FUNCTION_WITH_NO_BODY)
        assertCodes("val x: Int = 1\n set", KotlinDiagnosticCodes.VAL_WITH_SETTER)
        assertCodes("var v: String = \"\"\n set(value): String { field = value }", KotlinDiagnosticCodes.WRONG_SETTER_RETURN_TYPE)
        assertCodes("val y: Int\n get(): String = \"s\"", KotlinDiagnosticCodes.WRONG_GETTER_RETURN_TYPE)
        assertCodes("fun foo<T>() {}", KotlinDiagnosticCodes.DEPRECATED_TYPE_PARAMETER_SYNTAX)
        clean("var u: String = \"\"\n set(value): Unit { field = value }\nval z: Int\n get(): Int = 1")
    }

    /** A type parameter is no class: not a supertype, not the left side of `::` or of a dot, not a catch type. */
    @Test
    fun typeParametersAreNotClasses() {
        assertCodes("class A<T> : T", KotlinDiagnosticCodes.SUPERTYPE_NOT_A_CLASS_OR_INTERFACE)
        assertCodes("fun <T> foo() = T::toString", KotlinDiagnosticCodes.CALLABLE_REFERENCE_LHS_NOT_A_CLASS)
        assertCodes("class A<T> {\n fun f() { T.toString() }\n}", KotlinDiagnosticCodes.TYPE_PARAMETER_ON_LHS_OF_DOT)
        assertCodes("fun <T : Throwable> f() {\n try { } catch (e: T) { }\n}", KotlinDiagnosticCodes.TYPE_PARAMETER_IN_CATCH_CLAUSE)
        // A nested class of that name is what the name means inside the class.
        clean("class Outer<P> {\n class P { fun foo() {} }\n val l = P::foo\n}")
    }

    /** A catch parameter has a `Throwable` type and no default value. */
    @Test
    fun catchParameterShapes() {
        assertCodes("fun f() {\n try { } catch (x: Nothing) { }\n}", KotlinDiagnosticCodes.THROWABLE_TYPE_MISMATCH)
        assertCodes("class MyEx : Throwable()\nfun f() {\n try { } catch (e: MyEx = MyEx()) { }\n}", KotlinDiagnosticCodes.CATCH_PARAMETER_WITH_DEFAULT_VALUE)
        clean("class MyEx : Throwable()\nfun f() {\n try { } catch (e: MyEx) { }\n}")
    }

    /** Modifier shapes K2 rejects outright. */
    @Test
    fun rejectedModifierShapes() {
        assertCodes("sealed fun interface A {\n fun f()\n}", KotlinDiagnosticCodes.UNSUPPORTED_SEALED_FUN_INTERFACE)
        assertCodes("class A(abstract val i: Int)", KotlinDiagnosticCodes.ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS)
        assertCodes("open class A { open fun f() {} }\nclass B : A() {\n override fun f() { super?.f() }\n}", KotlinDiagnosticCodes.UNEXPECTED_SAFE_CALL)
        assertCodes("fun <F> bar4(x: (F & Any)?) {}", KotlinDiagnosticCodes.NULLABLE_ON_DEFINITELY_NOT_NULLABLE)
    }

    /** Annotation arguments: no `if`/`when`, no class literal of a type parameter. */
    @Test
    fun annotationArgumentsAreFoldedConstants() {
        assertCodes("annotation class Ann(val a: String)\n@Ann(if (true) \"A\" else \"B\") fun test1() {}", KotlinDiagnosticCodes.ANNOTATION_ARGUMENT_WITH_CONTROL_FLOW_NOT_SUPPORTED)
        assertCodes(
            "import kotlin.reflect.KClass\nannotation class Ann(val k: KClass<*>)\ninline fun <reified T> t() = @Ann(T::class) object {}",
            KotlinDiagnosticCodes.ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR,
        )
    }

    /** A reified type argument is no array of a type parameter and no `T & Any`. */
    @Test
    fun reifiedArgumentsAreRuntimeClasses() {
        val decls = "inline fun <reified T> foo() {}\n"
        assertCodes(decls + "fun <T> bar() { foo<Array<T>>() }", KotlinDiagnosticCodes.TYPE_PARAMETER_AS_REIFIED_ARRAY_ERROR)
        assertCodes(decls + "inline fun <reified F> baz() { foo<F & Any>() }", KotlinDiagnosticCodes.DEFINITELY_NON_NULLABLE_AS_REIFIED)
        clean(decls + "fun bar() { foo<Array<Int>>() }")
    }

    /** An expression annotation is SOURCE-retained, and `::Ann` constructs no annotation (nor `::Runnable` a Java
     *  SAM interface, which the sweep covers: this analyzer has no JDK). */
    @Test
    fun annotationClassRestrictions() {
        assertCodes(
            "@Target(AnnotationTarget.EXPRESSION)\n@Retention(AnnotationRetention.BINARY)\nannotation class A",
            KotlinDiagnosticCodes.RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR,
        )
        clean("@Target(AnnotationTarget.EXPRESSION)\n@Retention(AnnotationRetention.SOURCE)\nannotation class A")
        assertCodes("annotation class Ann(val prop: String)\nval r = ::Ann", KotlinDiagnosticCodes.CALLABLE_REFERENCE_TO_ANNOTATION_CONSTRUCTOR)
    }

    /** A type alias of `Nothing` hides that a declaration never returns. */
    @Test
    fun nothingIsWrittenAsNothing() {
        assertCodes("typealias N = Nothing\nfun testFun(): N = null!!", KotlinDiagnosticCodes.ABBREVIATED_NOTHING_RETURN_TYPE)
        assertCodes("typealias N = Nothing\nval testVal: N = null!!", KotlinDiagnosticCodes.ABBREVIATED_NOTHING_PROPERTY_TYPE)
        clean("fun testFun(): Nothing = null!!")
    }

    /** Delegation cannot override a final member, nor a `var` with a `val`. */
    @Test
    fun delegationMeetsSupertypeMembers() {
        val decls = "open class Final { fun foo() {}; var qux: Int = 0 }\ninterface IFoo { fun foo() }\nclass CFoo : IFoo { override fun foo() {} }\n" +
            "interface IQux { val qux: Int }\nclass CQux : IQux { override val qux: Int get() = 0 }\n"
        assertCodes(decls + "class Test1 : Final(), IFoo by CFoo()", KotlinDiagnosticCodes.OVERRIDING_FINAL_MEMBER_BY_DELEGATION)
        assertCodes(
            decls + "class Test3 : Final(), IQux by CQux()",
            KotlinDiagnosticCodes.OVERRIDING_FINAL_MEMBER_BY_DELEGATION, KotlinDiagnosticCodes.VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION,
        )
        clean(decls + "class Test2 : IFoo by CFoo()")
    }

    /** A parameter cannot inherit default values from two supertypes. */
    @Test
    fun oneDefaultPerInheritedParameter() {
        val decls = "interface X { fun foo(a: Int = 1) }\ninterface Y { fun foo(a: Int = 1) }\n"
        assertCodes(decls + "class Z : X, Y {\n override fun foo(a: Int) {}\n}", KotlinDiagnosticCodes.MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES)
        clean("interface X { fun foo(a: Int = 1) }\ninterface Y { fun foo(a: Int) }\nclass Z : X, Y {\n override fun foo(a: Int) {}\n}")
    }

    /** `x?::foo` is reserved syntax, and a nullable `x` cannot be the receiver of `Int.foo`. */
    @Test
    fun safeCallableReferencesAreReserved() {
        assertCodes(
            "fun nullableFun(): Int? = null\nfun Int.foo() {}\nval test1 = nullableFun()?::foo",
            KotlinDiagnosticCodes.SAFE_CALLABLE_REFERENCE_CALL, KotlinDiagnosticCodes.UNSAFE_CALLABLE_REFERENCE,
        )
        clean("fun String?.ext() = 1\nval r = String?::ext")
    }

    /** A sealed class's inheritors live in its package; a package may not share a class's name. */
    @Test
    fun sealedInheritorsAndPackagesStayApart() {
        val c = KotlinDiagnosticCodes.SEALED_INHERITOR_IN_DIFFERENT_PACKAGE
        assertEquals(listOf(c), errorsIn(mapOf("a.kt" to "package foo\nsealed class Base", "b.kt" to "package bar\nimport foo.Base\nclass E : Base()"), "b.kt").map { it.code })
        val p = KotlinDiagnosticCodes.PACKAGE_CONFLICTS_WITH_CLASSIFIER
        assertEquals(listOf(p), errorsIn(mapOf("a.kt" to "package a\nclass b", "b.kt" to "package a.b\nfun f() {}"), "b.kt").map { it.code })
    }

    /** Inline functional parameters: a default value is a lambda or a reference, and never names another inlined
     *  parameter. */
    @Test
    fun inlineParameterDefaults() {
        assertCodes("fun justCall(): () -> Unit = {}\ninline fun f(block: () -> Unit = justCall()) { block() }", KotlinDiagnosticCodes.INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE)
        clean("fun justCall(): () -> Unit = {}\ninline fun f(noinline block: () -> Unit = justCall()) { block() }")
        assertCodes(
            "inline fun f(lambda: () -> String, dlambda: () -> String = { lambda() }) { lambda() + dlambda() }",
            KotlinDiagnosticCodes.NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE,
        )
    }

    /** A nested type alias of a generic class cannot expand to its inner class, which needs the outer arguments. */
    @Test
    fun nestedAliasesCannotCaptureOuterTypeParameters() {
        assertCodes("class C<T> {\n inner class Inner\n typealias TA = Inner\n}", KotlinDiagnosticCodes.TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS)
        clean("class C<T> {\n class Nested\n typealias TA = Nested\n}")
    }

    /** One delegate instance serves every receiver of an extension property, so it cannot use its type parameters. */
    @Test
    fun extensionDelegatesCannotUseTheirTypeParameters() {
        assertCodes(
            "class Wrapper<T>(val v: T? = null)\noperator fun <T> Wrapper<T>.getValue(thisRef: Any?, kp: Any?): T = v!!\nval <T : Any> List<T>.bar by Wrapper<T>()",
            KotlinDiagnosticCodes.DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR,
        )
    }

    /** Labels, super calls from interfaces, fun-interface member names and member imports. */
    @Test
    fun labelsSupersAndImports() {
        assertCodes("class Dup {\n fun String.Dup(): Unit {\n  this@Dup\n }\n}", KotlinDiagnosticCodes.AMBIGUOUS_LABEL)
        assertCodes("open class A { open fun foo() {} }\ninterface I {\n fun foo() { super<A>.foo() }\n}", KotlinDiagnosticCodes.SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE)
        assertCodes("fun interface F1 {\n val functionDelegate: Function<*>? get() = null\n fun invoke()\n}", KotlinDiagnosticCodes.FUNCTION_DELEGATE_MEMBER_NAME_CLASH)
        val c = KotlinDiagnosticCodes.CANNOT_BE_IMPORTED
        assertEquals(listOf(c), errorsIn(mapOf("a.kt" to "package a\nclass B { fun bar() {} }", "c.kt" to "import a.B.bar\nfun f() {}"), "c.kt").map { it.code })
    }

    /** Bounds that name each other in a loop, and supertypes that lead back to the class. */
    @Test
    fun cyclicBoundsAndHierarchies() {
        val b = KotlinDiagnosticCodes.CYCLIC_GENERIC_UPPER_BOUND
        assertCodes("fun <T : F?, F : T?> foo1() {}", b, b)
        assertCodes("class A1<T : F?, F : T?>", b, b)
        clean("fun <T : Comparable<T>> foo() {}")
        val h = KotlinDiagnosticCodes.CYCLIC_INHERITANCE_HIERARCHY
        assertCodes("open class Your : His()\nopen class His : Your()", h, h)
        assertCodes("open class E : E.EE() {\n open class EE\n}", h)
        clean("sealed class S {\n class A : S()\n}")
    }

    /** What a `const val` initializer may not be: an elvis, a division by zero, an extension call. */
    @Test
    fun constInitializersTheEvaluatorRejects() {
        val c = KotlinDiagnosticCodes.CONST_MISUSE
        assertCodes("const val a = 1 / 0", c)
        assertCodes("const val b = \"a\" ?: \"b\"", c)
        assertCodes("const val m = 5.mod(2)", c)
        clean("const val d = 1.0 / 0\nconst val e = 7 / 2")
    }

    /** Declaration-site variance also binds the bounds of type parameters and extension receivers of properties. */
    @Test
    fun varianceCoversBoundsAndReceivers() {
        val v = KotlinDiagnosticCodes.VARIANCE_CONFLICT
        assertCodes("interface TypeBounds1<in I, X : I>", v)
        assertCodes("abstract class C<out O> {\n fun <X : O> typeParameter2() {}\n}", v)
        assertCodes("abstract class C<out O> {\n abstract val O.receiver2: Int\n}", v)
        clean("abstract class C<in I, out O> {\n fun <X : I> ok() {}\n abstract val I.r: Int\n}")
    }

    /** A member extension uses two implicit receivers, and each must be the closest one carrying its DSL marker. */
    @Test
    fun dslMarkersJudgeBothReceiversOfAMemberExtension() {
        val decls = "@DslMarker @Target(AnnotationTarget.TYPE) annotation class Ann1\n@DslMarker @Target(AnnotationTarget.TYPE) annotation class Ann2\n" +
            "class A { fun D.extA() {} }\nclass B { fun D.extB() {} }\nclass D\n" +
            "fun foo(x: (@Ann1 A).() -> Unit) {}\nfun bar(x: (@Ann2 B).() -> Unit) {}\nfun baz(x: (@Ann1 D).() -> Unit) {}\n"
        val v = KotlinDiagnosticCodes.DSL_SCOPE_VIOLATION
        assertCodes(decls + "fun t() { foo { baz { extA() } } }", v)
        assertCodes(decls + "fun t() { foo { baz { D().extA() } } }", v)
        assertCodes(decls + "fun t() { baz { foo { extA() } } }", v)
        clean(decls + "fun t() { foo { bar { baz { extB(); D().extB() } } }; baz { foo { D().extA() } } }")
    }

    /** An inherited `internal`/`protected` implementation of a public interface member would narrow it; a `var`
     *  implementing a `var` narrows its setter too. */
    @Test
    fun inheritedImplementationsCannotWeakenAccess() {
        val w = KotlinDiagnosticCodes.CANNOT_WEAKEN_ACCESS
        assertCodes("interface T { fun foo() }\nopen class C { protected fun foo() {} }\nclass E : C(), T", w)
        assertCodes("abstract class W { internal var y: Int = 42 }\ninterface IVar { var y: Int }\nabstract class C2 : W(), IVar", w, w)
        clean("abstract class W { internal abstract var x: Int }\ninterface IVal { val x: Int }\nabstract class C1 : W(), IVal")
    }

    /** The `for` loop conventions over a project class: a non-null range, `hasNext(): Boolean` and `next()`. */
    @Test
    fun forLoopConventionsOverProjectClasses() {
        assertCodes(
            "class It { operator fun next() = 1; operator fun hasNext() = false }\nclass Coll { operator fun iterator(): It = It() }\nfun t(c: Coll?) { for (x in c) {} }",
            KotlinDiagnosticCodes.ITERATOR_ON_NULLABLE,
        )
        clean("class It { operator fun next() = 1; operator fun hasNext() = false }\nclass Coll { operator fun iterator(): It = It() }\nfun t(c: Coll?) { if (c != null) for (x in c) {} }")
        assertCodes(
            "abstract class I1 { abstract operator fun hasNext(): Boolean }\nabstract class R { abstract operator fun iterator(): I1 }\nfun t(r: R) { for (i in r); }",
            KotlinDiagnosticCodes.NEXT_MISSING,
        )
        assertCodes(
            "abstract class R { abstract operator fun iterator(): Unit }\nfun t(r: R) { for (i in r); }",
            KotlinDiagnosticCodes.HAS_NEXT_MISSING, KotlinDiagnosticCodes.NEXT_MISSING,
        )
    }

    /** `a is B?` with unrelated classes and a nullable `a` holds for `null` only. */
    @Test
    fun anImpossibleNullableIsCheckReliesOnNull() {
        val decls = "open class A\nopen class B\n"
        assertCodes(decls + "fun test2(a: A?) = a is B?", KotlinDiagnosticCodes.IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL)
        assertCodes(decls + "fun test3(a: A) = a is B?", KotlinDiagnosticCodes.IMPOSSIBLE_IS_CHECK)
    }

    /** Variance is checked on the expansion of a type alias, and on an implicit type given by `f<A>()`. */
    @Test
    fun varianceThroughAliasesAndExplicitTypeArguments() {
        assertCodes("interface I1<S>\ntypealias A1<S> = I1<S>\nclass Test1<out S> : A1<S>", KotlinDiagnosticCodes.TYPE_VARIANCE_CONFLICT_IN_EXPANDED_TYPE)
        clean("interface Out<out T>\ntypealias AOut<T> = Out<T>\nclass Test3<out S> : AOut<S>")
        assertCodes("fun <T> getT(): T = null!!\nabstract class C<in I> {\n fun implicit() = getT<I>()\n}", KotlinDiagnosticCodes.VARIANCE_CONFLICT)
    }

    /** An annotation argument is no delegated property, no integer division by zero, no class-literal equality. */
    @Test
    fun annotationArgumentsTheEvaluatorRejects() {
        val c = KotlinDiagnosticCodes.NOT_CONSTANT
        assertCodes("annotation class Ann(val i: Int)\n@Ann(1 / 0) val b2 = 1", c)
        assertCodes(
            "import kotlin.reflect.KProperty\nannotation class Anno(val s: Int)\nclass D { operator fun getValue(t: Any?, p: KProperty<*>) = 42 }\n@get:Anno(foo)\nval foo: Int by D()",
            c,
        )
        clean("annotation class Ann(val i: Int)\n@Ann(1 / 2) val b2 = 1")
    }

    /** `external` and `expect` declaration shapes, and an expect/actual pair seen in one module. */
    @Test
    fun externalAndExpectShapes() {
        assertCodes("abstract class A {\n abstract val x: Int\n  external get\n}", KotlinDiagnosticCodes.EXTERNAL_DECLARATION_CANNOT_BE_ABSTRACT)
        assertCodes("interface B {\n val x: Int\n  external get\n}", KotlinDiagnosticCodes.EXTERNAL_DECLARATION_IN_INTERFACE)
        clean("external fun nativeCall(): Int")
    }

    /** `Box<_>::property` leaves `_` with nothing to infer it from. */
    @Test
    fun placeholdersAreNotQualifiers() {
        assertCodes("interface Box<V> { val property: V }\nfun main() { Box<_>::property }", KotlinDiagnosticCodes.PLACEHOLDER_PROJECTION_IN_QUALIFIER)
        clean("interface Box<V> { val property: V }\nfun main() { Box<Int>::property }")
    }

    /** `@DeprecatedSinceKotlin` belongs to the standard library's own packages. */
    @Test
    fun deprecatedSinceKotlinOnlyInKotlin() {
        assertCodes("@Deprecated(\"\")\n@DeprecatedSinceKotlin(\"1.3\")\nfun test() {}", KotlinDiagnosticCodes.DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE)
    }

    /** The errors of [target], one of several [files] of one project (each taken as written, no package added). */
    private fun errorsIn(files: Map<String, String>, target: String): List<Diagnostic> = runBlocking {
        val src = tempProject(files)
        val a = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath())))
        val doc = SnippetDoc(files.getValue(target), DiskFile(src.resolve(target)))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    private fun codes(code: String): List<String?> = errors(code).map { it.code }

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

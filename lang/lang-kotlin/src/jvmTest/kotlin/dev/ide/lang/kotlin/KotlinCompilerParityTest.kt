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

    /** `_` names no value; a HIDDEN-deprecated declaration is invisible to a bare reference; a constructor argument
     *  outside its class's bound is a mismatch too; an identity typealias constructs its class. */
    @Test
    fun underscoresHiddenDeclarationsAndAliasConstructors() {
        val u = KotlinDiagnosticCodes.UNRESOLVED
        val m = KotlinDiagnosticCodes.TYPE_MISMATCH
        assertTrue(u in codes("fun und(f: (Int, String) -> Unit) {}\nfun t() { und { _, b -> _.hashCode() } }"))
        assertCodes(
            "@Deprecated(\"h\", level = DeprecationLevel.HIDDEN)\nfun hiddenFun() {}\n" +
                "open class HA { @Deprecated(\"h\", level = DeprecationLevel.HIDDEN) open fun memberFun() {}\n fun use() { hiddenFun(); memberFun() } }",
            u, u,
        )
        clean("open class HA { @Deprecated(\"h\", level = DeprecationLevel.HIDDEN) open fun memberFun() {} }\nclass HB : HA() { override fun memberFun() {}\n fun use() { memberFun() } }")
        assertEquals(
            listOf(KotlinDiagnosticCodes.CANNOT_INFER_TYPE, m).sorted(),
            codes("class Num<Tn : Number>(val x: Tn)\nval tn = Num(\"1\")").map { it.orEmpty() }.sorted(),
        )
        assertCodes("class Cons<T>(val head: T, val tail: Cons<T>?)\ntypealias CA<T> = Cons<T>\nval a = CA(1, 2)", m)
        clean("class Cons<T>(val head: T, val tail: Cons<T>?)\ntypealias CA<T> = Cons<T>\nval a = CA(1, null)")
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

    /** A jump in a do-while's condition targets that loop; a lambda-result scope call ending in `null` or a jump
     *  is `Nothing?`, so an elvis with a jumping right side never completes. */
    @Test
    fun doWhileConditionJumpsAndNothingTypedElvisLeftSides() {
        clean("fun t(): Int { var i = 0\n do { ++i } while (when (i) { 1 -> break; 2 -> continue; else -> true })\n l@ do {} while (continue@l)\n return i }")
        clean("fun test1(): String { run { null } ?: return \"\" }")
        clean("fun test4(): String { run { run { null } ?: return \"\" } }")
        clean("fun test3(a: Int?): Any? { a?.let { return a } ?: return null }")
        assertCodes("fun t(a: String?): String { a ?: return \"\" }", KotlinDiagnosticCodes.MISSING_RETURN)
        assertCodes("fun t() { break }", KotlinDiagnosticCodes.JUMP_OUTSIDE_LOOP)
    }

    /** Members an abstract superclass implements by delegation are implemented in its subclasses, and a class
     *  redeclared in one file is its first declaration. */
    @Test
    fun superclassDelegationAndRedeclaredClassifiers() {
        clean(
            "abstract class Nav\nobject V : P { override val e: String = \"\" }\ninterface P { val e: String }\n" +
                "abstract class Base<K> : P by V\nclass F : Base<Nav>()",
        )
        assertCodes("interface P { val e: String }\nabstract class Base : P\nclass F : Base()", KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED)
        val codes = codes("sealed interface SE\nsealed class SEB : SE\nsealed class SB : SEB(), SE\nabstract class SE : SEB()")
        assertTrue(KotlinDiagnosticCodes.SUPERTYPE_NOT_INITIALIZED !in codes, "$codes")
    }

    /** A star import skips a class private to another file or nested privately, and goes on to the next package. */
    @Test
    fun starImportsSkipInvisibleClasses() {
        assertEquals(
            emptyList(),
            errorsIn(
                mapOf(
                    "File1.kt" to "package pack1\nprivate class SomeClass",
                    "File2.kt" to "package pack2\npublic open class SomeClass",
                    "Main.kt" to "package a\nimport pack1.*\nimport pack2.*\nclass X : SomeClass()",
                ),
                "Main.kt",
            ).map { it.code },
        )
        assertEquals(
            emptyList(),
            errorsIn(
                mapOf(
                    "File1.kt" to "package pack1\npublic class SomeClass { private class N }",
                    "File2.kt" to "package pack2\npublic open class N",
                    "Main.kt" to "package a\nimport pack1.SomeClass.*\nimport pack2.*\nclass X : N()",
                ),
                "Main.kt",
            ).map { it.code },
        )
    }

    /** An inner class's supertype written with the outer's arguments (`Super<T1>.Inner`) is that inner class. */
    @Test
    fun innerSupertypeWithOuterArguments() {
        clean(
            "open class Super<T> { inner open class Inner { open fun getOuter(): Super<T> = TODO() } }\n" +
                "class Sub<T1>() : Super<T1>() { inner class SubInner : Super<T1>.Inner() { override fun getOuter(): Sub<T1> = TODO() } }",
        )
    }

    /** `lit += x` calls an in-scope `plusAssign` before it needs a variable. */
    @Test
    fun compoundAssignmentOnLiteralUsesAssignOperator() {
        clean("class R<T>\nfun <T> f(): R<T> = R<T>()\noperator fun Int.plusAssign(y: R<Int>) {}\nfun box() { 1 += f() }")
        assertCodes("fun box() { 1 += 2 }", KotlinDiagnosticCodes.VARIABLE_EXPECTED)
    }

    /** A lambda for an extension function type may name the receiver as its first parameter (KT-81115). */
    @Test
    fun lambdaMayDeclareTheReceiverAsParameter() {
        clean("val l2: String.() -> String = { x: String -> \"\" }\nval l4: String.() -> String = { x -> x }\nval l6: String.(Int) -> String = { x: String, y -> \"\" }")
        // Past a function type's arity a parameter has nothing to type it either (K2's CANNOT_INFER_VALUE_PARAMETER_TYPE,
        // `functionTypeInitializerTypeMismatch.kt`).
        assertCodes("val l: () -> String = { x -> \"\" }", KotlinDiagnosticCodes.LAMBDA_PARAMETERS, KotlinDiagnosticCodes.CANNOT_INFER_TYPE)
    }

    /** A companion's member function imported beside a nested class of the same name does not conflict with it. */
    @Test
    fun importedMemberFunctionDoesNotConflictWithClass() {
        val outer = "package abc\nclass Outer {\n inner class Inner()\n companion object { fun Inner(x: String) {} }\n}"
        val codes = errorsIn(mapOf("Outer.kt" to outer, "imported.kt" to "import abc.Outer.Inner\nimport abc.Outer.Companion.Inner\nfun bar() { Inner(\"\") }"), "imported.kt").map { it.code }
        assertTrue(KotlinDiagnosticCodes.CONFLICTING_IMPORT !in codes, "$codes")
    }

    /** A declaration without `override` hides only a visible supertype member of the same substituted signature,
     *  and a Java member of a JVM-mapped built-in needs no `override`. */
    @Test
    fun hiddenSupertypeMembersFollowK2() {
        clean("open class B { fun foo(l: List<String>) {} }\nclass C : B() { fun foo(l: List<Int>) {} }")
        clean("interface Foo<T> { fun foo(l: List<T>) {} }\ninterface Bar<T> { fun foo(l: List<T>) {} }\nclass Baz() : Foo<String>, Bar<Int> { fun foo(l: List<Long>) {} }")
        clean("open class A { private fun foo(): Int = 1 }\nclass B : A() { fun foo(): String = \"\" }")
        clean("class ControlFlowException : Throwable() { fun fillInStackTrace() = this }")
        assertCodes("open class B { open fun foo(l: List<String>) {} }\nclass C : B() { fun foo(l: List<String>) {} }", KotlinDiagnosticCodes.OVERRIDE_REQUIRED)
    }

    /** An anonymous function passed to a call is labeled by it; an aliased import names a function too. */
    @Test
    fun anonymousFunctionLabelsAndAliasedFunctionImports() {
        clean("fun notInline(block: (Boolean) -> Unit): String = \"\"\nfun test(): String { return notInline(fun(b: Boolean) { return@notInline }) }")
        assertEquals(
            emptyList(),
            errorsIn(mapOf("B.kt" to "package b\nfun I() {}\ninterface I {}", "A.kt" to "package a\nimport b.I as I2\nfun bar() { I2() }"), "A.kt").map { it.code },
        )
    }

    /** Defaults combine across the functions an override implements (KT-40327); a second vararg may be empty. */
    @Test
    fun overrideDefaultsAndVarargsAreOptional() {
        clean(
            "interface Foo { fun some(x: Int = 1, y: Int) }\ninterface Bar { fun some(x: Int, y: Int = 2) }\n" +
                "class Impl : Foo, Bar { override fun some(x: Int, y: Int) {} }\nfun main() { Impl().some() }",
        )
        clean("@Suppress(\"MULTIPLE_VARARG_PARAMETERS\")\nfun <T> f2_(vararg x: T, vararg y: T) = Unit\nfun t() { f2_(1, 2) }")
        assertCodes("interface Foo { fun some(x: Int = 1, y: Int) }\nclass Impl : Foo { override fun some(x: Int, y: Int) {} }\nfun main() { Impl().some() }", KotlinDiagnosticCodes.ARGUMENT_COUNT)
    }

    /** A nested object read from its companion is a value; a class implementing a function type inherits `invoke`. */
    @Test
    fun nestedObjectsAndFunctionTypeSupertypes() {
        clean("sealed class SourceLocation {\n object NoLocation : SourceLocation()\n companion object { fun NoLocation(description: String): SourceLocation = NoLocation }\n}")
        clean("fun interface FunInterface : () -> Unit\nclass C2 : FunInterface { override fun invoke() {} }\nabstract class Test : () -> String\nopen class Child : Test() { override fun invoke() = \"OK\" }")
    }

    /** Const errors sit where K2 puts them, so an `@Suppress` there covers them; every code maps to its K2 name. */
    @Test
    fun constErrorsAndProjectionsAreSuppressible() {
        clean("const val x = \"123\"\n    @Suppress(\"CONST_VAL_WITH_GETTER\")\n    get() = field")
        clean("@Suppress(\"CONFLICTING_PROJECTION\")\nval <T> List<Map<Int, Map<String, in T>>>.h: Int get() = 7")
        assertCodes("val <T> List<Map<Int, Map<String, in T>>>.h: Int get() = 7", KotlinDiagnosticCodes.CONFLICTING_PROJECTION)
    }

    /** The file's own class that cannot take the arguments gives way to the default-imported one that can. */
    @Test
    fun shadowedDefaultImportConstructorStillResolves() {
        clean("class Pair(x: Int) {\n val k = Pair(1, 2)\n}")
    }

    /** A private member whose signature breaks its class's variance is private to `this`: another instance of
     *  the class cannot reach it, even inside the class body (FirPrivateToThisAccessChecker). */
    @Test
    fun privateToThisMembersNeedThis() {
        val inv = KotlinDiagnosticCodes.INVISIBLE_REFERENCE
        val cls = "fun <T> getT(): T = null!!\nclass Test<in I> {\n private fun foo(): I = getT()\n private var i: I = getT()\n fun apply(i: I) {}\n"
        assertCodes(cls + " fun <I> test(t: Test<I>) { t.apply(t.foo()) }\n}", inv)
        assertCodes(cls + " companion object { fun <I> test(t: Test<I>) { t.i = getT() } }\n}", inv)
        clean(cls + " fun test() { apply(foo()); apply(this.foo()); this.i = getT(); with(Test<I>()) { apply(this@Test.foo()) } }\n}")
        clean("class A<in T>(t: T) {\n private val i: B = B()\n inner class B { fun baz(a: A<*>) { a.i } }\n}")
    }

    /** An import through a class private to another file, a private companion, or to a private member of an
     *  object of another file is invisible on that segment (FirImportsChecker). */
    @Test
    fun importsThroughPrivateDeclarationsAreInvisible() {
        val inv = KotlinDiagnosticCodes.INVISIBLE_REFERENCE
        val a = "package a\nclass A { companion object { fun foo() {} } }\nprivate class B { companion object { fun bar() {} } }\n" +
            "class C { private companion object { fun baz() {} } }\nobject O { private fun p() {}; fun q() {} }"
        fun errs(imports: String) = errorsIn(mapOf("a.kt" to a, "b.kt" to "package b\n$imports\nfun t() {}"), "b.kt").map { it.code }
        assertEquals(emptyList(), errs("import a.A.Companion.foo\nimport a.O.q"))
        assertEquals(listOf(inv), errs("import a.B.Companion.bar"))
        assertEquals(listOf(inv), errs("import a.C.Companion.baz"))
        assertEquals(listOf(inv), errs("import a.O.p"))
    }

    /** A function-typed value is invoked with every parameter given: its `invoke` has no defaults. */
    @Test
    fun functionValueNeedsEveryArgument() {
        val c = KotlinDiagnosticCodes.ARGUMENT_COUNT
        assertCodes("fun foo(p: (Int, () -> Int) -> Unit) { p { 1 } }", c)
        assertCodes("fun foo(p: (Int, () -> Int) -> Unit) { p() { 2 } }", c)
        clean("fun foo(p: (Int, () -> Int) -> Unit) { p(3) { 4 } }")
        clean("fun foo(p: Int.() -> Unit) { 1.p(); with(2) { p() } }")
    }

    /** A use-site projection written in source is a projection: a value read through it is the captured type's
     *  bound (`out T` reads its bound, `in T` reads `Any?`), and an `in` position binds the variable itself. */
    @Test
    fun projectedTypeArgumentsCaptureAndApproximate() {
        clean("interface Inv<T> { fun get(): T }\nfun f(x: Inv<out CharSequence>) { x.get().length }")
        clean("interface Inv<T>\nfun <Y> bar(l: Inv<Y>): Y = TODO()\nfun <X : Inv<out String>> foo(x: X) { bar(x).length }")
        clean(
            "class Key<T>\nclass Box<T>\nfun <T : Any> get(key: Key<in T>): T? = null\nfun <T> acceptBox(box: Box<T>) {}\n" +
                "fun <V> test(key: Key<in Box<V>>) { get(key)?.let { acceptBox(it) } }",
        )
        assertCodes("fun f(x: MutableList<in Int>) { val s: Int = x[0] }", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** `a == b` smart casts each side to the other's type when the left's `equals` is primitive (`String`, an enum,
     *  a final class), and `===` always does. */
    @Test
    fun equalitySmartCastsThroughPrimitiveEquals() {
        clean("fun foo(vararg ts: String, a: Any?) { if (ts[0] == a) { a.length } }")
        clean("interface A\nclass B : A\nval X = B()\nfun foo(b: B) {}\nfun main(a: A) { if (a === X) { foo(a) } }")
    }

    /** A cast to a nullable type proves the type; a `!!` on the same value then makes it non-null. */
    @Test
    fun nullableCastThenNotNullAssertion() {
        clean("fun t(a: Any?) {\n (a as String?)!!\n a.length\n}")
    }

    /** A generic extension PROPERTY (`val <R> R.boxed`) applies to any receiver, as a generic extension function does. */
    @Test
    fun genericExtensionPropertiesResolveOnAnyReceiver() {
        clean("class Box<V>(val value: V)\nval <R> R.boxed: Box<R> get() = TODO()\ninterface A\nfun t(a: A) { a.boxed.value }")
        clean("val <T> T.foo: T? get() = null\nfun test(): Int? = 1.foo")
    }

    /** A class's supertypes are resolved outside it: its own nested classifiers are not visible there. */
    @Test
    fun classHeaderSeesTheOuterScope() {
        clean("interface Base { fun foo() {} }\nclass MyClass(val prop: Base) : Base by prop { interface Base }\nfun t(d: MyClass) { d.foo() }")
        assertCodes("class A : Nested() { open class Nested }", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** At one receiver level the receiver's extensions (a member extension of an enclosing class) come before the
     *  members of the receivers outside it. */
    @Test
    fun memberExtensionOfInnerReceiverBeatsOuterMember() {
        clean("class Test(val x: Int) {\n val String.x: String get() = this\n fun test(s: String) { with(s) { x.length } }\n}")
    }

    /** A project function's contract speaks for an infix call too, including `implies` on a Boolean parameter. */
    @Test
    fun infixCallContracts() {
        clean(
            "import kotlin.contracts.*\n@OptIn(ExperimentalContracts::class)\ninfix fun Boolean.takeRight(cond: Boolean): Boolean {\n" +
                " contract { returns(true) implies cond }\n return cond\n}\nfun t(b: Boolean, x: Any) { if (b takeRight (x is String)) { x.length } }",
        )
    }

    /** `when (val x = this)` narrows `this` along with `x`; `this@foo` naming the innermost receiver is `this`. */
    @Test
    fun receiverAliasesNarrowTogether() {
        clean("fun Any.t(): Int = when (val x = this) { is String -> { x.length; length }\n else -> 0 }")
        clean("fun Any?.foo(): Int { if (this@foo is Int) return this; return 1 }")
    }

    /** A type with a function type among its supertypes is invocable. */
    @Test
    fun functionSupertypeMakesAValueInvocable() {
        clean("interface T\ninterface A : T, (String) -> Unit\nfun t(f: T) { when (f) { is A -> f(\"Hello\") } }")
    }

    /** A star import of a class's members outranks the default imports in value position. */
    @Test
    fun starImportedEnumEntryOutranksDefaultImport() {
        clean("import demo.TestEnum.*\nenum class TestEnum { Annotation, Collection }\nfun t() { val x1 = Annotation; val x2 = Collection }")
    }

    /** Smaller scope fixes: a labeled local declaration, a property beside a nested class in value position, a
     *  HIDDEN nested class not inherited, a local class extending another, explicit type arguments in a class. */
    @Test
    fun scopeResolutionCorners() {
        clean("fun t() {\n L@ val fn = {}\n fn()\n}")
        clean("object D { class E }\nval D.E get() = \"\"\nval de = D.E")
        clean(
            "open class A {\n @Deprecated(\"\", level = DeprecationLevel.HIDDEN)\n class B\n}\nclass B { fun foo() {} }\n" +
                "class C : A() { fun test(b: B) { b.foo() } }",
        )
        clean("open class Bar { fun foo() {} }\nfun t() {\n open class BaseLocal : Bar() { fun baz() {} }\n class DerivedLocal : BaseLocal()\n DerivedLocal().baz()\n DerivedLocal().foo()\n}")
        clean(
            "fun <T> materialize(): T = null!!\nclass Result\nclass Foo {\n fun t() { val r = materialize<Result>(); save(r) }\n" +
                " private fun save(r: Result) {}\n class Result\n}",
        )
    }

    /** A member access path checked with `is` is smart cast where it is read again. */
    @Test
    fun memberPathSmartCasts() {
        clean("object Obj { val y: Any? = 2 }\nfun t(): Int { var b = 0\n if (Obj.y is Int) { b = Obj.y }\n return b }")
        clean("open class A(open val x: Any)\nclass B(x: Any) : A(x)\nfun t(b: B) { if (b.x is String) { b.x.length } }")
    }

    /** An explicit import outranks a star import, and the file's own package outranks a star import. */
    @Test
    fun importLevelsRankTopLevels() {
        assertCodes("var X: Int = 1\nfun t() { val s: String = X }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean("fun testFun() = \"239\"\nval t: String = testFun()")
    }

    /** On a nullable receiver an extension declared on the nullable type is the call, not the member. */
    @Test
    fun nullableReceiverExtensionOverMember() {
        clean("class Foo { fun foo() {} }\nfun Any?.foo() {}\nfun test(f: Foo?) { f.foo() }")
    }

    /** An extension property has no backing field: `field` in its accessor is an ordinary name. */
    @Test
    fun extensionPropertyHasNoBackingField() {
        clean("interface B { fun foo(): Int }\nclass A {\n val String.x: Int get() { return field.foo() }\n val String.field: B get() = TODO()\n}")
    }

    /** Calling an interface is an error (INTERFACE_AS_FUNCTION) when the only `Path` in scope is the imported
     *  `java.nio.file.Path`: the unimported top-level `kotlin.io.path.Path(...)` function is not a candidate. With
     *  that function imported the call is fine. The bound reference `REGEX_TEST::find` in `?.let` stays clean. */
    @Test
    fun calledInterfaceWithUnimportedSameNamedFunction() {
        val body = "\nval REGEX_TEST = Regex(\"\")\nfun readOrNull(path: Path): String? = runCatching { path.readText() }.getOrNull()\n" +
            "fun main(): String? {\n    return readOrNull(Path(\"Hello.kt\"))\n        ?.let(REGEX_TEST::find)\n" +
            "        ?.groupValues\n        ?.firstOrNull()\n}\n"
        val missing = jdkErrors("import java.nio.file.Path\nimport kotlin.io.path.readText\nimport kotlin.io.readText\n$body")
        assertEquals(listOf(KotlinDiagnosticCodes.ABSTRACT_INSTANTIATION), missing.map { it.code }, "for the unimported factory: $missing")
        assertTrue(missing.single().message.contains("does not have constructors"), missing.single().message)
        val imported = jdkErrors("import java.nio.file.Path\nimport kotlin.io.path.Path\nimport kotlin.io.path.readText\n$body")
        assertTrue(imported.isEmpty(), "with `import kotlin.io.path.Path`: $imported")
        val star = jdkErrors("import java.nio.file.Path\nimport kotlin.io.path.*\n$body")
        assertTrue(star.isEmpty(), "with `import kotlin.io.path.*`: $star")
    }

    /** [errors] with the JDK on the classpath, for code that names `java.*` types. */
    private fun jdkErrors(code: String): List<Diagnostic> = runBlocking {
        val text = "package demo\n$code\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val jdk = dev.ide.testkit.TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdk)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    /**
     * A library function's `contract { }` is read from its @Metadata: kotlin.test's `assertNotNull(x)` makes `x`
     * non-null after it, `assertTrue(x is T)` and `assertIs<T>(x)` make `x` a `T`, while `assertNull(x)` and a
     * call without a contract prove nothing.
     */
    @Test
    fun libraryContractsNarrowTheirArguments() {
        val clean = listOf(
            "fun f(s: String?) { kotlin.test.assertNotNull(s); s.length }",
            "fun f(s: String?) { kotlin.test.assertNotNull(s, \"msg\"); s.length }",
            "fun f(a: Any?) { kotlin.test.assertTrue(a is String); a.length }",
            "fun f(a: Any?) { kotlin.test.assertTrue(a is String, \"msg\"); a.length }",
            "fun f(a: Any?) { kotlin.test.assertFalse(a !is String); a.length }",
            "fun f(a: Any?) { kotlin.test.assertIs<String>(a); a.length }",
            "fun f(s: String?) { if (!s.isNullOrEmpty()) s.length }",
        )
        for (code in clean) assertEquals(emptyList(), kotlinTestErrors(code).map { it.code }, "for `$code`")
        val dirty = listOf(
            "fun f(s: String?) { kotlin.test.assertNull(s); s.length }",
            "fun f(s: String?) { println(s); s.length }",
            "fun f(a: Any?) { kotlin.test.assertFalse(a is String); a.length }",
        )
        for (code in dirty) assertTrue(kotlinTestErrors(code).isNotEmpty(), "expected an error for `$code`")
    }

    /**
     * False errors from the real-world sweep: a Java constructor overload is chosen by its arguments and a Kotlin
     * `Map` fills a `java.util.Map` parameter (`HashMap(m)` is a `HashMap<String, Int>`), a private data class's
     * `copy` is as visible as its constructor, a cast in the right side of a true `&&` narrows, `String? + x` is
     * the stdlib's `String?.plus`, and a candidate that leaves a required parameter unfilled is not applicable.
     */
    @Test
    fun realWorldSweepRound2() {
        val jdkClean = listOf(
            "fun f(m: Map<String, Int>, k: String) { val h = HashMap(m); val v: Int? = h[k] }",
            "fun f(m: Map<String, Int>, k: String) { val h = java.util.TreeMap(m); val v: Int? = h[k] }",
            "fun f(m: List<String>) { val a = ArrayList(m); val s: String = a[0] }",
        )
        for (code in jdkClean) assertEquals(emptyList(), jdkErrors(code).map { it.code }, "for `$code`")
        val clean = listOf(
            "class R { private data class S(val a: Int, val b: String); fun f(s: Any) { (s as S).copy(b = \"x\") } }",
            "open class P; class Q : P() { val e = 1 }\nfun f(ready: Boolean, p: P) { if (ready && (p as Q).e == 1) p.e }",
            "fun f(s: String?) = s + \"x\"",
            "fun g(message: String? = null, block: () -> Boolean) {}\nfun g(actual: Boolean, message: String? = null) {}\n" +
                "fun f(a: Any?) { g(a is String) }",
        )
        for (code in clean) assertEquals(emptyList(), errors(code).map { it.code }, "for `$code`")
        assertTrue(errors("fun f(s: String?) = s.length").isNotEmpty(), "a member on a nullable receiver still errors")
    }

    /** `Char + String` is the stdlib's `Char.plus(String): String`, and `String? + x` its `String?.plus`: both are
     *  `String`, so a `when` joining one with `String`/`null` is a `String?`. */
    @Test
    fun charAndNullableStringPlusAreStrings() {
        val clean = listOf(
            "fun f(name: String): String? {\n fun decap(r: String) = r.takeIf { it.isNotEmpty() }?.let { it[0].lowercaseChar() + it.substring(1) }\n" +
                " return when { name.startsWith(\"get\") -> decap(name.substring(3)); name.length > 2 -> name; else -> null }\n}",
            "fun f(s: String?) { val x: String = s + 1 }",
        )
        for (code in clean) assertEquals(emptyList(), errors(code).map { it.code }, "for `$code`")
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors("fun f() { val x: Int = 'a' + \"b\" }").map { it.code })
    }

    /** A class and a same-named factory function in one package: the call binds whichever the arguments fit,
     *  bare or package-qualified (`fs.Box("x")` constructs when the factory takes an `Int`); and a Java method's
     *  `java.lang.String` result is a `kotlin.String` argument. */
    @Test
    fun constructorAndFactoryCompeteByArguments() {
        val files = mapOf(
            "a.kt" to "package fs\nclass Box(root: String) { fun get(): Int = 1 }\nfun Box(root: Int): Box = Box(root.toString())\n",
            "b.kt" to "package use\nimport fs.Box\nfun f() { val a: Int = fs.Box(\"x\").get(); val b: Int = Box(\"x\").get(); val c: Int = Box(1).get() }\n",
        )
        assertEquals(emptyList(), errorsIn(files, "b.kt").map { it.code })
        val jdk = "fun g(s: String) = 1\nfun g(i: Int) = \"\"\nfun f(p: java.nio.file.Path) { val x: Int = g(p.toString()) }"
        assertEquals(emptyList(), jdkErrors(jdk).map { it.code }, "for `$jdk`")
    }

    /** `subs.indexOf(sup)`/`contains`/`lastIndexOf`: the member takes only the element type, so K2 binds the stdlib
     *  extension with `T` widened to the argument (a `List<Sub>` is a `List<Super>`). Results that depend on `T`
     *  keep the receiver's argument (`l + 1` is a `List<Int>`). */
    @Test
    fun covariantReceiverWidensForInputOnlyExtensions() {
        val clean = "interface VA\nclass KVA : VA\nfun f(l: List<KVA>, v: VA) { val x: Int = l.indexOf(v); val y: Boolean = l.contains(v); val z: Int = l.lastIndexOf(v) }"
        assertEquals(emptyList(), errors(clean).map { it.code }, "for `$clean`")
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors("fun f(l: List<Int>) { val y: String = l.first() }").map { it.code })
        assertEquals(emptyList(), errors("fun f(l: List<Int>) { val x: List<Int> = l + 1 }").map { it.code })
    }

    /** STRAY round: a class-body destructuring parses (it is NON_LOCAL_DESTRUCTURING_DECLARATION, not a syntax error)
     *  and its names are members of a local class too; each file sees its own private typealias; and an abstract
     *  CLASS member left unimplemented is K2's ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED, apart from an interface's. */
    @Test
    fun strayRoundNonLocalDestructuringPrivateAliasAbstractKinds() {
        val destructuring = "data class X(val a: Int, val b: String)\nfun f() { class L { val (a, b) = X(1, \"\") }\n L().b.length }"
        assertEquals(listOf(KotlinDiagnosticCodes.NON_LOCAL_DESTRUCTURING_DECLARATION), errors(destructuring).map { it.code })
        val files = mapOf(
            "file1.kt" to "private class C { companion object }\nprivate typealias TA = C\nprivate val t: TA = TA()\n",
            "file2.kt" to "private typealias TA = Int\n",
        )
        assertEquals(emptyList(), errorsIn(files, "file1.kt").map { it.code })
        assertEquals(listOf(KotlinDiagnosticCodes.ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED),
            errors("abstract class A { abstract fun f() }\nclass B : A()").map { it.code })
        assertEquals(listOf(KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED),
            errors("interface I { fun f() }\nclass B : I").map { it.code })
    }

    /** STRAY round 2: a function-typed value whose receiver is an error type (a recursive alias, an unresolved
     *  class) takes any receiver; a variable bounded only from contravariant positions is their most specific
     *  bound; and a Java class reaches the default methods of the JDK interfaces it implements
     *  (`ArrayList.toArray(IntFunction)`), which a Kotlin `List` does not. */
    @Test
    fun strayRoundErrorReceiverContravariantBoundsJdkDefaults() {
        assertEquals(listOf(KotlinDiagnosticCodes.UNRESOLVED),
            errors("class Bar\nfun zbar(body: Unknown.() -> Unit) = Bar().body()").map { it.code })
        assertEquals(listOf(KotlinDiagnosticCodes.UNRESOLVED),
            errors("class Bar\nclass Baz\nfun zbar(body: Baz.() -> Unit) = Bar().body()").map { it.code })
        val bounds = "interface In<in T>\ninterface A\ninterface B\ninterface C : A, B\nfun <T> foo(a: In<T>, b: In<T>): T = TODO()\n" +
            "fun t(inA: In<A>, inB: In<B>, inC: In<C>) { val r: C = foo(inA, inC); val c: C = foo(inA, inB) }"
        assertEquals(emptyList(), errors(bounds).map { it.code }, "for `$bounds`")
        val jdk = "import java.util.function.IntFunction\nclass A<T> : ArrayList<T>() {\n" +
            " override fun <R> toArray(generator: IntFunction<Array<R>>): Array<R> = super.toArray(generator)\n}"
        assertEquals(emptyList(), jdkErrors(jdk).map { it.code }, "for `$jdk`")
        // JDK 21's `List.getFirst()`: a Java `ArrayList` has it, a Kotlin `ArrayDeque` (which lists `MutableList`
        // itself) keeps Kotlin's view, where it is not.
        if (java.util.List::class.java.methods.any { it.name == "getFirst" }) {
            assertEquals(emptyList(), jdkErrors("fun f(al: java.util.ArrayList<String>) { al.getFirst() }").map { it.code })
            assertEquals(listOf(KotlinDiagnosticCodes.UNRESOLVED), jdkErrors("fun f(ad: ArrayDeque<String>) { ad.getFirst() }").map { it.code })
        }
    }

    /** The last two strays: a generic operator's own type parameters bind from its operand, a delegated property is
     *  typed by its delegate's `provideDelegate`/`getValue` (a member extension declared in the class included),
     *  and a builder's type variable is fixed on demand when its value is needed mid-lambda. */
    @Test
    fun genericOperatorsDelegateConventionAndFixationOnDemand() {
        val parsers = "abstract class Parser<out T>\nclass TP<T, R> : Parser<R>()\nclass IP : Parser<Unit>()\n" +
            "operator fun <T> IP.plus(other: Parser<T>): TP<Pair<Unit, T>, T> = TODO()\n"
        val op = parsers + "fun f(a: IP, b: Parser<String>) { val x: TP<Pair<Unit, String>, String> = a + b; val y: Int = a + b }"
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors(op).map { it.code }, "for `$op`")
        val delegate = "import kotlin.reflect.KProperty\n" + parsers + "class M {\n val b: Parser<String> = TODO()\n val top by IP() + b\n" +
            " fun use() { val x: Int = top }\n" +
            " operator fun <T, R> TP<T, R>.getValue(thisRef: M, property: KProperty<*>): TP<T, R> = TODO()\n}"
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors(delegate).map { it.code }, "for `$delegate`")
        val pcla = "class Controller<T> { fun yield(t: T): Boolean = true; fun get(): T = TODO() }\n" +
            "fun <S> generate(g: suspend Controller<S>.() -> Unit): S = TODO()\ninterface A { val a: Any }\ninterface B : A { val b: Any }\n" +
            "fun <R> myWith(r: R, b: R.() -> Unit) {}\n" +
            "fun main(aI: A, bI: B) {\n val x = generate { yield(bI); myWith(get()) { this.b }; yield(aI) }\n x.b\n}"
        val pclaErrors = errors(pcla)
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), pclaErrors.map { it.code }, "for `$pcla`: $pclaErrors")
    }

    /** INFIX_MODIFIER_REQUIRED: `a name b` calling a function that is not `infix` (a member, an extension, a numeric
     *  operator, a function value's `invoke`), and never a call to an `infix` one (stdlib, member or local). */
    @Test
    fun infixModifierRequired() {
        val code = "class E { fun nonInfix(o: E) = 1; infix fun inf(o: E) = 2 }\nfun E.ext(o: E) = 3\n" +
            "fun f(a: E, b: E, g: (Int) -> Unit) { a nonInfix b; a ext b; 1 plus 1; g invoke 1; a inf b }"
        assertEquals(List(4) { KotlinDiagnosticCodes.INFIX_MODIFIER_REQUIRED }, errors(code).map { it.code }, "for `$code`")
        val clean = "class A { infix fun String.m(o: Int) = 1; fun u() { \"a\" m 1 } }\n" +
            "fun f() { infix fun Int.l(o: Int) = o; 1 l 2; val a = 1 shl 2; val b = true and false; val c = 1 to 2; val d = 10 downTo 1 step 2 }"
        assertEquals(emptyList(), errors(clean).map { it.code }, "for `$clean`")
    }

    /** Annotation targets: a `@Target` entry naming no constant adds nothing (every use is then wrong); no use-site
     *  target applies on a type but `@receiver:` on an extension receiver; `@receiver:` needs a declared receiver;
     *  and the target a use-site names must be one the annotation class allows. Valid use-site targets stay clean. */
    @Test
    fun annotationTargetsAndUseSites() {
        assertEquals(listOf(KotlinDiagnosticCodes.WRONG_ANNOTATION_TARGET),
            errors("@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS) annotation class A\n@A val x = 1").map { it.code })
        val wrong = "annotation class A\n@Target(AnnotationTarget.FIELD) annotation class F\n" +
            "fun t(i: @param:A Int) {}\n@receiver:A fun g() {}\nfun Int.p(@receiver:A a: Int) {}\nclass C(@param:F val b: Int)"
        assertEquals(List(4) { KotlinDiagnosticCodes.WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET }, errors(wrong).map { it.code }, "for `$wrong`")
        val clean = "annotation class A\nclass C(@field:A val x: Int, @param:A val y: Int, @property:A val z: Int, @get:A val w: Int) {\n" +
            " @get:A @set:A var v: Int = 0\n @setparam:A var u: Int = 0\n @delegate:A val d by lazy { 1 }\n fun @receiver:A String.ext() = 1\n}\n" +
            "@receiver:A fun String.top() = 1"
        assertEquals(emptyList(), errors(clean).map { it.code }, "for `$clean`")
    }

    /** A member read through a star projection is its parameter's upper bound (`Proxy<*>.t` with `T : I` is an `I`,
     *  an unbounded one `Any?`), so a `when` on it is judged for exhaustiveness and a mismatch is reported. */
    @Test
    fun starProjectedReadsTakeTheBound() {
        val code = "sealed interface I\nclass A : I\nclass B : I\ndata class Proxy<T : I>(val t: T)\nclass Box<T>(val t: T)\n" +
            "fun f(x: Proxy<*>, b: Box<*>) { val y: Int = x.t; val z: Int = b.t\n when (x.t) { is A -> 1 }\n when (x.t) { is A -> 1; is B -> 2 } }"
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH, KotlinDiagnosticCodes.TYPE_MISMATCH, KotlinDiagnosticCodes.WHEN_EXHAUSTIVE),
            errors(code).map { it.code }, "for `$code`")
    }

    /** NO_ELSE_IN_WHEN: a `null` branch covers only the `?`; a used `when` over a string is never exhaustive whatever
     *  its labels; an empty sealed type needs `is` itself; `Nothing?` needs `null`; an unrelated `is` covers no
     *  enum entry. Exhaustive ones stay clean. */
    @Test
    fun whenExhaustivenessGaps() {
        val code = "enum class E { A, B }\nsealed class Empty\nsealed class S { class X : S(); class Y : S() }\n" +
            "fun t(e: E?, s: S?, str: String, em: Empty, n: Nothing?) {\n" +
            " when (e) { E.A -> 1; null -> 2 }\n when (s) { is S.X -> 1; null -> 2 }\n" +
            " val a = when (str) { \"a\" -> 1 }\n val b: Int = when (em) { }\n val c = when (n) { }\n" +
            " val d = when (e) { E.A -> 1; E.B -> 2; is String -> 3 }\n}"
        assertEquals(List(6) { KotlinDiagnosticCodes.WHEN_EXHAUSTIVE }, errors(code).map { it.code }.filter { it == KotlinDiagnosticCodes.WHEN_EXHAUSTIVE }, "for `$code`")
        val clean = "enum class E { A, B }\nsealed class S { class X : S(); class Y : S() }\n" +
            "fun t(e: E?, s: S?, n: Nothing) {\n when (e) { E.A -> 1; E.B -> 2; null -> 3 }\n when (s) { is S.X -> 1; is S.Y -> 2; null -> 3 }\n" +
            " val c = when (n) { }\n val d = when (e) { E.A -> 1; is E -> 2; null -> 3 }\n}"
        assertEquals(emptyList(), errors(clean).map { it.code }, "for `$clean`")
    }

    /** IMPOSSIBLE_IS_CHECK_ERROR between two LIBRARY classes neither of which extends the other (`Exception` and `Error`):
     *  their class files give the whole hierarchy once the classpath is indexed. A subclass or an interface is fine. */
    @Test
    fun impossibleIsCheckBetweenLibraryClasses() {
        val code = "fun t(e: Exception) { if (e is Error) {}; if (e is AssertionError) {}; if (e is RuntimeException) {}; if (e is Runnable) {} }"
        assertEquals(List(2) { KotlinDiagnosticCodes.IMPOSSIBLE_IS_CHECK }, jdkErrors(code).map { it.code }, "for `$code`")
        // Every class is an `Any`, whether or not it says so: `x is Foo` on an `Any?` is a real question.
        val any = "class Foo\nopen class Bar\nfun t(x: Any?, y: Any) { if (x is Foo) {}; if (y is Bar) {}; if (x is Exception) {} }"
        assertEquals(emptyList(), jdkErrors(any).map { it.code }, "for `$any`")
    }

    /** An annotation call takes no more arguments than its constructor has parameters: TOO_MANY_ARGUMENTS. */
    @Test
    fun annotationWithTooManyArguments() {
        val bad = "annotation class A1\nannotation class A2(val some: Int = 12)\n@A1(12) class C\n@A2(1, 2) class D"
        assertEquals(List(2) { KotlinDiagnosticCodes.ARGUMENT_COUNT }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "annotation class A1\nannotation class A2(val some: Int = 12)\nannotation class V(vararg val xs: Int)\n@A1 @A2(3) @V(1, 2, 3) class C"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** An annotation not allowed on a type parameter is WRONG_ANNOTATION_TARGET each time it is written there, not
     *  a repeated one. */
    @Test
    fun misplacedAnnotationIsNotARepeat() {
        val code = "annotation class A1\nclass C<@A1 @A1() T>"
        assertEquals(List(2) { KotlinDiagnosticCodes.WRONG_ANNOTATION_TARGET }, errors(code).map { it.code }, "for `$code`")
    }

    /** A default value may read only EARLIER parameters: itself or a later one is in scope but uninitialized
     *  (UNINITIALIZED_PARAMETER), also from a lambda or a local function inside the default value. */
    @Test
    fun defaultValueReadingALaterParameter() {
        val bad = "fun foo(x: Int = y, y: Int = x): Int = x + y\nfun bar(x: () -> Int = { y }, y: Int) {}\nclass A(a: Int = a)"
        assertEquals(List(3) { KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "val z = 3\nfun foo(x: Int = 1, y: Int = x, i: Int = z): Int = x + y + i\nfun bar(x: (Int) -> Int = { y -> y }, y: Int) {}\n" +
            "object S { fun nanoTime(): Long = 0 }\nclass E(private val nanoTime: () -> Long = S::nanoTime)\nfun g(a: Int) = a\nfun h(b: Int = g(a = 1), a: Int = 2) = b"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** An enum entry written as a type is ENUM_ENTRY_AS_TYPE, and IS_ENUM_ENTRY in an `is` check, not unresolved. */
    @Test
    fun enumEntryUsedAsType() {
        val code = "enum class Color { RED, GREEN }\nfun f(c: Color.RED): Color.GREEN? = null\nfun g(x: Any) = x is Color.RED"
        assertEquals(
            listOf(KotlinDiagnosticCodes.ENUM_ENTRY_AS_TYPE, KotlinDiagnosticCodes.ENUM_ENTRY_AS_TYPE, KotlinDiagnosticCodes.IS_ENUM_ENTRY),
            errors(code).map { it.code }, "for `$code`",
        )
        val ok = "enum class Color { RED; class Nested }\nfun f(c: Color, n: Color.Nested) = c == Color.RED"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** An inner class constructed through its outer class's type, or from that class's companion, has no outer
     *  instance: INNER_CLASS_CONSTRUCTOR_NO_RECEIVER. `Outer().Inner()` and a call inside `Outer` have one. */
    @Test
    fun innerClassConstructorWithoutReceiver() {
        val decls = "class Outer { inner class Inner { inner class InnerInner }\n companion object { fun c() { Inner() } }\n fun ok() = Inner() }\n"
        val bad = decls + "fun f() = Outer.Inner()\nfun g() = Outer.Inner.InnerInner()"
        assertEquals(List(3) { KotlinDiagnosticCodes.INNER_CLASS_CONSTRUCTOR_NO_RECEIVER }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "class Outer { inner class Inner { inner class InnerInner }\n fun ok() = Inner()\n class Nested }\n" +
            "fun f() = Outer().Inner()\nfun g() = Outer().Inner().InnerInner()\nfun h() = Outer.Nested()"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** `x is C<args>` where `x`'s own type already fixes `C`'s arguments to something else cannot be checked at run
     *  time: `MutableList<String> is MutableList<CharSequence>`. One it implies is a plain check. */
    @Test
    fun erasedIsCheckAgainstTheSubjectsOwnArguments() {
        val bad = "fun f(a: MutableList<String>) = a is MutableList<CharSequence>\nfun g(a: List<Any>) = a is List<Number>"
        assertEquals(List(2) { KotlinDiagnosticCodes.CAST_ERASED }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "fun f(a: MutableList<String>) = a is MutableList<String>\nfun g(a: List<Int>) = a is List<Number>\nfun h(a: List<Any>) = a is List<*>"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** An `override` overrides nothing when the supertype members of its name all have another parameter list. */
    @Test
    fun overrideWithNoMatchingSignature() {
        val decls = "interface I { fun foo(s: String)\n fun bar(a: String)\n fun bar(a: Boolean)\n fun baz(a: Int = 1)\n fun qux(vararg a: String) }\n"
        val bad = decls + "abstract class A : I { override fun foo() {}\n override fun bar(a: Int) {}\n override fun baz(a: String) {}\n override fun qux(a: String) {} }"
        assertEquals(List(4) { KotlinDiagnosticCodes.NOTHING_TO_OVERRIDE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = decls + "interface G<T> { fun g(t: T) }\nabstract class A : I, G<Int> { override fun foo(s: String) {}\n override fun bar(a: Boolean) {}\n" +
            " override fun baz(a: Int) {}\n override fun qux(vararg a: String) {}\n override fun g(t: Int) {} }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A non-repeatable annotation is judged per element it lands on: `@get:Ann` on a property and `@Ann` on its getter
     *  are one getter's two, and an unprefixed `@Ann` on a constructor property is its parameter's. */
    @Test
    fun repeatedAnnotationAcrossUseSiteTargets() {
        val bad = "annotation class Ann(val x: Int = 0)\n@get:Ann(10)\nval a: String\n    @Ann(20) get() = \"\"\n" +
            "class A(@param:Ann @Ann val x: Int) { @Ann @field:Ann @property:Ann val b: Int = 0 }"
        assertEquals(List(3) { KotlinDiagnosticCodes.DECLARATION_SHAPE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "annotation class Ann(val x: Int = 0)\n@get:Ann(10)\nval a: String get() = \"\"\n" +
            "class A(@param:Ann @property:Ann val x: Int) { @Ann @field:Ann val b: Int = 0 }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A bound that names another type parameter (`T : Element<K>`) is judged with the written argument put in:
     *  `Container<Alpha, BetaKey>` where `Alpha : Element<AlphaKey>`; one naming a type parameter is not. */
    @Test
    fun upperBoundNamingAnotherTypeParameter() {
        val decls = "sealed interface Key\nclass AlphaKey : Key\nclass BetaKey : Key\nsealed interface Element<K : Key>\n" +
            "class Alpha : Element<AlphaKey>\nclass Container<T : Element<K>, K : Key>\nclass C<X, Y : X>\n"
        val bad = decls + "fun f() { Container<Alpha, BetaKey>() }\nval c: Container<Alpha, BetaKey>? = null"
        assertEquals(List(2) { KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED }, errors(bad).map { it.code }, "for `$bad`")
        val ok = decls + "fun f() { Container<Alpha, AlphaKey>() }\ntypealias TA<T> = C<Int, List<T>>"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** Explicit API mode: a public API declaration states its visibility, and a callable its inferred type. */
    @Test
    fun explicitApiMode() {
        val code = "class Foo { fun bar() = 1\n public val x = 2\n private fun p() = 3\n override fun toString(): String = \"\" }\n" +
            "internal class I { fun i() = 1 }\npublic data class D(val a: Int)\npublic fun ok(): Int { val local = 1; return local }"
        val strict = analyzerErrors(code) { it.explicitApiMode = { KotlinExplicitApiMode.STRICT } }
        assertEquals(
            listOf(
                KotlinDiagnosticCodes.NO_EXPLICIT_VISIBILITY_IN_API_MODE, // class Foo
                KotlinDiagnosticCodes.NO_EXPLICIT_VISIBILITY_IN_API_MODE, // fun bar
                KotlinDiagnosticCodes.NO_EXPLICIT_RETURN_TYPE_IN_API_MODE, // bar's type
                KotlinDiagnosticCodes.NO_EXPLICIT_RETURN_TYPE_IN_API_MODE, // x's type
            ),
            strict.map { it.code }.sortedBy { it != KotlinDiagnosticCodes.NO_EXPLICIT_VISIBILITY_IN_API_MODE }, "for `$code`",
        )
        assertEquals(emptyList(), errors(code).map { it.code }, "explicit API mode off: `$code`")
    }

    /** A block body that can fall off the end owes a value of a type-parameter return type too: `T` is no `Unit`. */
    @Test
    fun missingReturnForTypeParameterReturnType() {
        val bad = "fun <T> g(f: () -> List<T>): T {}\nopen class G<T> { open fun f(): T {} }"
        assertEquals(List(2) { KotlinDiagnosticCodes.MISSING_RETURN }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "fun <T> g(f: () -> List<T>): T { return f().first() }\nopen class G<T>(val t: T) { open fun f(): T { TODO() } }\n" +
            "fun <T> h(t: T): T { if (t == null) return t; return t }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** Each segment of a qualified inner-class type takes only its own class's type arguments, and a nested or
     *  inner class named bare inside its outer class is checked as that class. */
    @Test
    fun innerClassSegmentTypeArgumentCounts() {
        val bad = "abstract class A<T> { inner class B { inner class C\n fun f(y: Any) { if (y is B<*>.C) {} } }\n" +
            " fun g(x: Any) { if (x is B<*>) {} } }\nclass Outer<X> { inner class Inner<Y> { inner class Inner2 } }\n" +
            "fun e(): Outer<Int>.Inner<String, Int>.Inner2 = null!!"
        assertEquals(List(3) { KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "abstract class A<T> { inner class B { inner class C\n fun f(y: Any) { if (y is A<*>.B.C) {} } } }\n" +
            "class Outer<X> { inner class Inner<Y> { inner class Inner2 } }\nfun o(): Outer<Int>.Inner<String>.Inner2 = null!!"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A parameter's annotation reaches the functions around its function, and a setter parameter's the class's
     *  members: a call or a plain `val` there is no compile-time constant. */
    @Test
    fun annotationArgumentOnParameterMustBeConst() {
        val bad = "annotation class Anno(val x: Int)\nfun function(@Anno(function(24)) param: Int) = 1\n" +
            "class C { val prop = 1\n var p: Int = 0\n set(@Anno(prop) value) {} }"
        assertEquals(List(2) { KotlinDiagnosticCodes.NOT_CONSTANT }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "annotation class Anno(val x: Int)\nconst val K = 1\nfun function(@Anno(K) param: Int) = 1\n" +
            "class C { var p: Int = 0\n set(@Anno(K) value) {} }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A delegate whose `getValue`/`setValue` none can take the property's call: a `thisRef` its owner is not (a
     *  top-level property passes `null`), or a `setValue` value parameter the property's type does not fit. */
    @Test
    fun delegateSpecialFunctionNoneApplicable() {
        val decls = "import kotlin.reflect.KProperty\nclass A\nclass Get { operator fun getValue(t: A, p: KProperty<*>): Int = 1 }\n" +
            "class Set { operator fun getValue(t: Any?, p: KProperty<*>): Int = 1\n operator fun setValue(t: Any?, p: KProperty<*>, v: String) {} }\n" +
            "class Ok { operator fun getValue(t: Any?, p: KProperty<*>): Int = 1\n operator fun setValue(t: Any?, p: KProperty<*>, v: Int) {} }\n"
        val bad = decls + "class B { val b: Int by Get() }\nval top: Int by Get()\nvar s: Int by Set()"
        assertEquals(List(3) { KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = decls + "var o: Int by Ok()\nclass A2 { var x: Int by Ok() }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A lambda called in place and an object expression's initializers run during the class's initialization: a
     *  `val` they read before its own initializer has run is UNINITIALIZED_VARIABLE (a `var` read there compiles). */
    @Test
    fun uninitializedValReadInPlaceOrInObjectExpression() {
        val bad = "fun consume(x: Any?) {}\nclass A {\n val b = run { consume(x) }\n val c = object { init { consume(x) } }\n val x = 10\n}"
        assertEquals(List(2) { KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "fun consume(x: Any?) {}\nfun capture(f: () -> Unit) {}\nclass A {\n val a = capture { consume(x) }\n" +
            " val c = object { fun f() = consume(x) }\n val d = run { consume(s) }\n val e = 1.let { x -> consume(x) }\n" +
            " val x = 10\n var s = true\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A nested class or an object has no instance of the classes around it: an outer instance member, an `inner`
     *  class of the outer class, or `this@Outer` from inside one is INACCESSIBLE_OUTER_CLASS_RECEIVER. */
    @Test
    fun inaccessibleOuterClassReceiver() {
        val bad = "class Outer { val p = 1\n fun f() {}\n open inner class Inner\n" +
            " class Nested { fun g() { f(); val x = p; Inner(); val o = this@Outer } }\n object O : Inner() }"
        assertEquals(List(5) { KotlinDiagnosticCodes.INACCESSIBLE_OUTER_CLASS_RECEIVER }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "class Outer { val p = 1\n fun f() {}\n inner class Inner { fun g() { f(); val x = p; Inner() } }\n" +
            " class Nested { fun f() {}\n fun g() { f(); val o = object { fun h() = f() } } }\n" +
            " companion object { fun s() {} }\n class N2 { fun g() = s() } }\n" +
            "enum class E { A, B; object Q { fun v() = values(); val a = A } }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** Every argument a projected receiver's member cannot take is reported, vararg elements included; a spread
     *  array of the projection's bound is no `Array<out CapturedType(out B)>`. */
    @Test
    fun projectedOutEveryArgumentAndVarargs() {
        val code = "class A<T> { fun foo(x: T, y: T) {}\n fun bar(vararg x: T) {} }\n" +
            "fun t(a: A<out CharSequence>, y: Array<out CharSequence>) { a.foo(\"\", \"\"); a.bar(\"\", \"\"); a.bar(*y) }"
        assertEquals(
            List(4) { KotlinDiagnosticCodes.MEMBER_PROJECTED_OUT } + KotlinDiagnosticCodes.TYPE_MISMATCH,
            errors(code).map { it.code }.sortedBy { it != KotlinDiagnosticCodes.MEMBER_PROJECTED_OUT }, "for `$code`",
        )
        val ok = "class A<T> { fun bar(vararg x: T) {} }\nfun t(a: A<in CharSequence>, y: Array<String>) { a.bar(\"\", \"x\"); a.bar(*y) }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A builder variable is fixed by `in` in a `when` branch and by an `is`/`as` against a generic class written bare
     *  (bare type inference reads the value's type): an argument for it afterwards must fit what it was fixed to. */
    @Test
    fun builderVariableFixedByWhenInAndBareTypeChecks() {
        val decls = "class TypeVariableOwner<T> { fun constrain(subtypeValue: T) {}; fun provide(): T = null!! }\n" +
            "fun <OT> pcla(lambda: (TypeVariableOwner<OT>) -> Unit): OT = null!!\ninterface BaseType\n" +
            "class ScopeOwner : BaseType { operator fun contains(x: Any): Boolean = true }\nobject Value\nobject Interloper : BaseType\n" +
            "open class G<A> : BaseType\nopen class GS<B> : G<B>()\ninterface Arg\n"
        val fixed = decls + "fun t() {\n" +
            "    pcla { o -> o.constrain(ScopeOwner()); when (Value) { in o.provide() -> Unit }; o.constrain(Interloper) }\n" +
            "    pcla { o -> o.constrain(G<Arg>()); if (o.provide() is GS) Unit; o.constrain(Interloper) }\n}"
        assertEquals(List(2) { KotlinDiagnosticCodes.TYPE_MISMATCH }, errors(fixed).map { it.code }, "for `$fixed`")
        val open = decls + "fun t() {\n" +
            "    pcla { o -> o.constrain(G<Arg>()); if (o.provide() is GS<*>) Unit; o.constrain(Interloper) }\n}"
        assertEquals(emptyList(), errors(open).map { it.code }, "for `$open`")
    }

    /** A property's setter can be less visible than its getter: writing it (`=`, a compound assignment that writes back,
     *  `++`/`--`) where the setter cannot be seen is INVISIBLE_SETTER, reading it is fine. */
    @Test
    fun invisibleSetter() {
        val decls = "class P { var x: Int = 0\n private set\n val l = mutableListOf(1)\n var y = 0\n" +
            " fun inside(other: P) { x = 1; other.x = 2; x++ } }\nopen class X { var n = \"\"\n private set }\n"
        val bad = decls + "fun f(p: P) { p.x = 1; p.x += 1; p.x++ }\nclass Z : X() { fun g() { n = \"z\" } }"
        assertEquals(List(4) { KotlinDiagnosticCodes.INVISIBLE_REFERENCE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = decls + "fun f(p: P) { val r = p.x + 1; p.y = r; p.l += 2 }\nclass Z : X() { fun g(): String = n }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** In a `when` with a subject, an unparenthesized `in`/`is`/comparison/equality/`&&` condition reads as a test of
     *  the subject but is a value compared with it; an assignment there is no value at all. */
    @Test
    fun confusingWhenBranchConditions() {
        val bad = "fun t(x: Int, b: Boolean, r: IntRange) { var y = 0\n when (x) { x in r -> {}; x < 1 -> {}; else -> {} }\n" +
            " when (b) { b && b -> {}; else -> {} }\n when (x) { in x == 1 -> {}; else -> {} }\n when (x) { y = 1 -> {}; else -> {} } }"
        assertEquals(
            List(4) { KotlinDiagnosticCodes.CONFUSING_BRANCH_CONDITION } + KotlinDiagnosticCodes.ASSIGNMENT_IN_EXPRESSION,
            // (`x in r` against an `Int` subject is also an incomparable equality, as K2 says too.)
            errors(bad).map { it.code }.filter { it != KotlinDiagnosticCodes.INCOMPARABLE_EQUALITY }
                .sortedBy { it != KotlinDiagnosticCodes.CONFUSING_BRANCH_CONDITION }, "for `$bad`",
        )
        val ok = "fun t(x: Int, b: Boolean, r: IntRange) {\n when (b) { (x in r) -> {}; (x < 1) -> {}; else -> {} }\n" +
            " when (x) { x + 1 -> {}; in r -> {}; else -> {} }\n" +
            " when { x in r -> {}; x < 1 && b -> {}; else -> {} } }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A member inherited from two supertypes that name a parameter differently has no name for it of its own. */
    @Test
    fun nameForAmbiguousParameter() {
        val decls = "interface A { fun foo(a1: Int, a2: Double) }\ninterface B { fun foo(b1: Int, a2: Double) }\ninterface C : A, B\n"
        val bad = decls + "fun t(c: C) { c.foo(a1 = 1, a2 = 1.0) }"
        assertEquals(listOf(KotlinDiagnosticCodes.NAME_FOR_AMBIGUOUS_PARAMETER), errors(bad).map { it.code }, "for `$bad`")
        val generic = "interface G<T> { fun foo(a: T) }\ninterface H { fun foo(b: Int) }\ninterface I : G<Int>, H\nfun t(i: I) { i.foo(b = 1) }"
        assertEquals(listOf(KotlinDiagnosticCodes.NAME_FOR_AMBIGUOUS_PARAMETER), errors(generic).map { it.code }, "for `$generic`")
        // Its own override names them; and different parameter types are overloads, each with its own names.
        val ok = decls + "interface D : C { override fun foo(d1: Int, d2: Double) }\n" +
            "interface E { fun bar(x: Int) }\ninterface F { fun bar(y: String) }\ninterface EF : E, F\n" +
            "fun t(d: D, ef: EF) { d.foo(d1 = 1, d2 = 1.0); ef.bar(x = 1); ef.bar(y = \"\") }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A component of a destructured lambda parameter is a `val` like any parameter: a null check smart casts it. */
    @Test
    fun destructuredLambdaComponentSmartCasts() {
        val code = "class K { fun w(): K = this }\nfun f(m: Map<String, Pair<K, K?>>, s: String): K? {\n" +
            "    m[s]?.let { (_, upper) -> return if (upper == null) null else upper.w() }\n    return null\n}"
        assertEquals(emptyList(), errors(code).map { it.code }, "for `$code`")
    }

    /** A projected receiver's type argument inside a member's parameter is a captured type: on `MC<out Open>`,
     *  `addAll(c: C<T>)` takes a `C<CapturedType(out Open)>`, which only a `C<Nothing>` is. */
    @Test
    fun argumentIntoAProjectedReceiversParameter() {
        val bad = "interface C<out T>\ninterface MC<T> : C<T> { fun addAll(x: C<T>): Boolean }\ninterface Open\n" +
            "fun t(x: MC<out Open>) { x.addAll(x) }"
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors(bad).map { it.code }, "for `$bad`")
        val ok = "interface Box<T> { fun put(x: List<T>); fun take(f: Box<in T>) }\n" +
            "fun t(a: Box<out CharSequence>, b: Box<in String>, l: List<Nothing>, s: List<String>, bx: Box<Any?>) { a.put(l); b.put(s); b.take(bx); a.put(emptyList()) }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A builder lambda's receiver takes the call's explicit type arguments: inside `buildMap<String, MutableList<C>> { }`
     *  `this` is a `MutableMap<String, MutableList<C>>`, so `getOrPut(k) { ArrayList() }` is a `MutableList<C>`. */
    @Test
    fun explicitTypeArgumentsReachABuilderReceiver() {
        val code = "class C\nval m = buildMap<String, MutableList<C>> { getOrPut(\"k\") { ArrayList() }.add(C()) }"
        assertEquals(emptyList(), errors(code).map { it.code }, "for `$code`")
        val wrong = "class C\nval m = buildMap<String, MutableList<C>> { val x: Int = getOrPut(\"k\") { ArrayList() } }"
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors(wrong).map { it.code }, "for `$wrong`")
    }

    /** [errors] with kotlin-test on the classpath too. */
    private fun kotlinTestErrors(code: String): List<Diagnostic> = runBlocking {
        val text = "package demo\n$code\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val kotlinTest = java.nio.file.Path.of(kotlin.test.Asserter::class.java.protectionDomain.codeSource.location.toURI())
        val a = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath(), kotlinTest)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    /** The errors of [target], one of several [files] of one project (each taken as written, no package added). */
    private fun errorsIn(files: Map<String, String>, target: String): List<Diagnostic> = runBlocking {
        val src = tempProject(files)
        val a = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath())))
        val doc = SnippetDoc(files.getValue(target), DiskFile(src.resolve(target)))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    /** `super` in an enum entry's body is its enum class; an abstract member reached that way is ABSTRACT_SUPER_CALL. */
    @Test
    fun superInAnEnumEntryIsTheEnumClass() {
        clean("enum class E {\n E1 { override fun foo() = outerFun() + super.outerFun() };\n abstract fun foo(): Int\n fun outerFun() = 42\n}")
        assertCodes("interface I { fun foo() }\nenum class E2 : I {\n A { override fun foo() = super.foo() };\n}", KotlinDiagnosticCodes.SUPER_MISUSE)
    }

    /** An extension on a function type is found on a lambda, also as an infix call whose receiver is a lambda. */
    @Test
    fun functionTypeReceiverExtensionsResolveOnLambdas() {
        clean("class A { fun bar() {} }\ninfix fun (() -> Unit).foo(x: A.() -> Unit) {}\nfun main() {\n {} foo { bar() }\n}")
        clean("class A { fun bar() {} }\nfun (() -> Unit).foo(x: A.() -> Unit) {}\nfun main() { val f: () -> Unit = {}; f.foo { bar() } }")
    }

    /** `5.b()` invokes the extension property `Int.b: () -> String` before a variable `b: Int.() -> Int` (KT-64891). */
    @Test
    fun anExtensionPropertyIsInvokedBeforeAnExtensionFunctionTypedVariable() {
        clean("val b: Int.() -> Int = { 10 }\nval Int.b: () -> String get() = { \"B\" }\nfun main() { 5.b().length }")
    }

    /** The most specific receiver's extension property wins before its value is invoked. */
    @Test
    fun theMostSpecificExtensionPropertyIsInvoked() {
        clean("open class A { operator fun invoke(f: () -> Unit): Int = 1 }\nclass B { operator fun invoke(f: () -> Unit): CharSequence = \"\" }\n" +
            "open class C\nval C.attr: A get() = TODO()\nopen class D: C()\nval D.attr: B get() = TODO()\nfun box(d: D) { (d.attr {}).length }")
    }

    /** A member extension a class of the edited file inherits, and one with a nested generic receiver. */
    @Test
    fun inheritedAndNestedGenericMemberExtensionsResolve() {
        clean("interface Context { val Variable.value: Int }\nclass Variable\nabstract class A : Context {\n abstract val intVar: Variable\n fun foo() { val x: Int = intVar.value }\n}")
        clean("interface Context { var <V> Variable<V>.value: V }\nclass Variable<T>\nabstract class A : Context {\n abstract val intVar: Variable<Int>\n fun foo() { intVar.value++ }\n}")
        clean("interface Loading { val <X> Property<LoadingValue<X>>.liveLoaded: X get(): X = TODO() }\ninterface LoadingValue<T>\ninterface Property<T>\n" +
            "interface AsyncModule { fun bar() {} }\noperator fun <T : AsyncModule, R> T.invoke(handler: T.() -> R): R = TODO()\n" +
            "fun Loading.foo(fooBar: Property<LoadingValue<AsyncModule>>) { fooBar.liveLoaded { bar() } }")
    }

    /** A member extension declared on a class's own type parameter is found on a value of that type parameter. */
    @Test
    fun aMemberExtensionOnATypeParameterIsFound() {
        clean("abstract class A<T : Any> {\n abstract protected fun T.foo()\n fun bar(x: T?) { if (x != null) { x.foo() } }\n}")
    }

    /** An infix call prefers an extension of the file's own package over a default-imported one of that name. */
    @Test
    fun anInfixCallPrefersTheFilesOwnPackage() {
        clean("interface A\ninterface B\nclass Pair<out A, out B>(val first: A, val second: B)\ninfix fun <A, B> A.to(that: B) = Pair(this, that)\n" +
            "fun test(a: A?, b: B, list: MutableList<Pair<A, B>>) { if (a != null) { list.add(a to b) } }")
    }

    /** A local `var` takes the type of what is assigned to it, also through `++x` (KT-57178), but not into a
     *  closure that may run after a later write, nor for a value an expected type would type differently. */
    @Test
    fun aLocalVarTakesTheTypeOfItsAssignedValue() {
        clean("open class I { operator fun inc(): ST = ST() }\nclass ST : I()\nfun main() {\n var local = I()\n val x: ST = ++local\n val y: ST = local\n}")
        clean("fun f() { var x: Any = 1; x = \"s\"; x.length }")
        assertCodes("fun main() {\n var p: String?\n var block: () -> Int = { 1 }\n p = \"2\"\n run { block = { p.length } }\n p = null\n block()\n}", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
    }

    /** A reference to a member extension, function or property, with or without type arguments on the receiver. */
    @Test
    fun memberExtensionPropertiesCannotBeReferencedThroughTheClass() {
        val c = KotlinDiagnosticCodes.EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED
        assertCodes("class T {\n class A\n class B\n val B.extensionVal: A get() = A()\n fun test() { val r = B::extensionVal }\n}", c)
        assertCodes("class T {\n class A<X>\n class B<X>\n fun <X> B<X>.extensionFun(): A<X> = A()\n fun test() { val r = B<*>::extensionFun }\n}", c)
    }

    /** A value whose `invoke` cannot take the arguments gives way to the next value of that name further out. */
    @Test
    fun anInapplicableValueInvokeFallsBackToAnOuterValue() {
        clean("operator fun String.invoke() = this\nval some = \"\"\nfun sss() {\n val some = 10\n some()\n}")
        clean("fun foo() {\n val x = fun(s: String) {}\n fun nested() {\n  val x = fun(i: Int) {}\n  x(\"hello\")\n }\n}")
        assertCodes("fun main() { val f = 5; f() }", KotlinDiagnosticCodes.NOT_CALLABLE)
    }

    /** A null check on a safe cast's chain proves the cast: `(q as? Complex)?.superClass == null` false. */
    @Test
    fun aFailedNullCheckOnASafeCastChainNarrows() {
        clean("interface State\ninterface Complex { val superClass: Complex? }\ninterface ExceptionState : State\n" +
            "fun test(q: State?) {\n if (q == null || q is ExceptionState || (q as? Complex)?.superClass == null) return\n q.superClass\n}")
    }

    /** A member `val` given its value in an `init` block takes that value's type there. */
    @Test
    fun aMemberValAssignedInInitIsSmartCast() {
        clean("interface I\ninterface S : I { fun foo() }\nfun s(): S = TODO()\nclass Main {\n private val x: I\n init {\n  x = s()\n  x.foo()\n }\n}")
    }

    /** `this@label is T` narrows that labeled lambda's receiver, also when another receiver is innermost. */
    @Test
    fun aCheckOnALabeledThisNarrowsThatReceiver() {
        clean("interface IA\ninterface IB\nobject A : IA { fun B.foo() {} }\nobject B : IB\nfun test(a: IA, b: IB) {\n" +
            " with(a) lambda1@{\n  with(b) lambda2@{\n   if (this@lambda1 is A && this@lambda2 is B) { foo() }\n  }\n }\n}")
    }

    /** A checked companion or object `val` stays narrowed however its owner is spelled (`AClass.y`, `demo.Obj.y`). */
    @Test
    fun objectAndCompanionValsAreStableUnderAnySpelling() {
        clean("object Obj { val y: Any? = 2 }\nclass AClass { companion object { val y: Any? = 2 } }\n" +
            "fun t1(): Int { if (demo.Obj.y is Int) return Obj.y; return 0 }\n" +
            "fun t2(): Int { if (AClass.y is Int) return AClass.y; return 0 }\n" +
            "fun t3(): Int { if (demo.AClass.y is Int) return AClass.y; return 0 }\n" +
            "fun t4(): Int { if (Obj.y is Int) return demo.Obj.y; return 0 }")
    }

    /** `i == j` where `j` is proven null makes `i` the null value. */
    @Test
    fun equalityWithAProvenNullNarrowsToNothing() {
        clean("fun f(i: Int?) {\n val j: Int? = null\n if (i == j) { val n: Nothing? = i }\n}")
    }

    /** A failed `this !is T` branch leaves `this` a `T` in the later branches of a subjectless `when`. */
    @Test
    fun aFailedNegativeWhenBranchNarrowsTheLaterOnes() {
        clean("interface R<out T : R<T>> { fun can(a: @UnsafeVariance T): Boolean }\n" +
            "fun Any.m(o: Any): Boolean = when { this !is R<*> -> true; else -> this.can(o as R<*>) }\n" +
            "fun Any.n(o: Any): Boolean = when { this !is R<*> -> true; else -> can(o as R<*>) }")
    }

    /** After a `when`/`if` chain whose falling-through branches each narrow `x`, `x` is their common supertype. */
    @Test
    fun branchNarrowingsJoinIntoTheirCommonSupertype() {
        clean("interface C { fun baz() }\ninterface A : C\ninterface B : C\n" +
            "fun t1(x: Any) { when (x) { is A -> 1; is B -> 2; else -> return }; x.baz() }\n" +
            "fun Any.t2() { when (this) { is A -> 1; is B -> 2; else -> return }; baz() }\n" +
            "fun t3(x: Any) { if (x is A) {} else if (x is B) {} else return; x.baz() }")
        assertCodes("interface C { fun baz() }\ninterface A : C\ninterface B : C\nfun t(x: Any) { when (x) { is A -> 1; is B -> 2; else -> {} }; x.baz() }", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** An `inner` class is constructed through an implicit receiver, local classes included; the type is the inner class. */
    @Test
    fun anInnerClassIsConstructedThroughAnExtensionReceiver() {
        clean("class GA { inner class GB { inner class GC } }\nfun GA.GB.blah() { val c: GA.GB.GC = GC() }\n" +
            "fun test2() {\n class LocalA { inner class LocalB { inner class LocalC } }\n fun LocalA.LocalB.blah() { val c: LocalA.LocalB.LocalC = LocalC() }\n}")
        assertCodes("class GA { inner class GB { inner class GC } }\nfun GA.GB.blah() { val s: String = GC() }", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** An extension property's initializer has no receiver: the inner class is not reachable there. */
    @Test
    fun anExtensionPropertyInitializerHasNoReceiver() {
        assertTrue(KotlinDiagnosticCodes.UNRESOLVED in codes("class C { inner class D }\nval C.foo: C.D = D()"))
    }

    /** A file's own `Array` that cannot take `(size) { … }` leaves the call to `kotlin.Array`. */
    @Test
    fun anInapplicableOwnClassFallsBackToTheDefaultImportedConstructor() {
        clean("class Array<E>(e: E) {\n val k = Array(1) { 1 as Any; e as Any? }\n}\nfun f() { val a: kotlin.Array<Int> = Array(2) { 1 } }")
    }

    /** A class named as the receiver reads its companion's member, not its own instance member. */
    @Test
    fun aClassReceiverReadsTheCompanionMember() {
        clean("class Q<out T : Any> private constructor(private val r: T?, val inProgress: Boolean) {\n" +
            " companion object { val inProgress = Q(null, true) }\n}\nclass L { var value: Q<Int> = null!! }\nfun main() { L().value = Q.inProgress }")
    }

    /** A function type nested in a type argument keeps its own `->`: the `>` closes no `<`. */
    @Test
    fun aFunctionTypeInsideATypeArgumentParses() {
        clean("fun f(p: Pair<Int, () -> () -> String>?) { val q: Pair<Int, () -> () -> String>? = p; val m: Map<(Int, Int) -> Int, String> = emptyMap() }")
    }

    /** An enum class named `Enum` keeps its companion: `kotlin.Enum`'s constructor is not an inherited member. */
    @Test
    fun anEnumNamedEnumReachesItsCompanion() {
        clean("enum class Enum {\n A;\n companion object { fun foo(): Int = 1 }\n}\nfun t() = Enum.foo()")
    }

    /** A lambda parameter typed narrower than what the expected function type passes is EXPECTED_PARAMETER_TYPE_MISMATCH. */
    @Test
    fun aLambdaParameterTypedNarrowerThanExpectedIsReported() {
        assertTrue(KotlinDiagnosticCodes.EXPECTED_PARAMETER_TYPE_MISMATCH in codes("fun foo(p: ((Int) -> String) -> String) { foo { f: String -> f } }"))
        assertTrue(KotlinDiagnosticCodes.EXPECTED_PARAMETER_TYPE_MISMATCH in codes("fun interface F : ((Int) -> String) -> String\nfun fuz(p: F) { fuz { f: String -> f } }"))
        clean("fun foo(p: (Int) -> String) { foo { i: Int -> i.toString() }; foo { i: Any -> i.toString() } }")
    }

    /** `t!!` on a `t: T` is `T & Any`: a nullable `T` binding loses its `?` there. */
    @Test
    fun aNotNullAssertedTypeParameterStaysNonNullThroughSubstitution() {
        clean("fun <F> test(f: F) = f!!\nfun main() { test<String?>(null).length; val n: Int? = test<Int?>(null) }")
        clean("fun <E> materialize(): E = TODO()\nfun foo() { val x: String?; x = materialize()!!; x.length }")
    }

    /** `null` for a `T & Any` parameter is NULL_FOR_NONNULL_TYPE whatever `T` is; a lambda in a type-variable slot
     *  constrains it instead of mismatching it. */
    @Test
    fun definitelyNonNullParametersRefuseNullAndTypeVariableSlotsTakeAnyLambdaType() {
        assertEquals(3, codes("fun <T> foo(x: T, y: T & Any) = x!!\nfun main() {\n foo<String>(\"\", null)\n foo<String?>(null, null)\n foo(\"\", null)\n foo(null, \"\")\n}")
            .count { it == KotlinDiagnosticCodes.TYPE_MISMATCH })
        assertTrue(KotlinDiagnosticCodes.EXPECTED_PARAMETER_TYPE_MISMATCH !in codes("fun <T> select(vararg x: T) = x[0]\nfun t() { select({ x: Int -> }, { x: String -> }) }"))
    }

    /** A cast of a lambda's implicit `it` narrows the later reads of `it`, as it does a named parameter. */
    @Test
    fun aCastOfImplicitItNarrowsLaterReads() {
        clean("interface A<F> { val a: F }\ninterface B<G> : A<G> { val b: G }\nfun <X> withCallback(x: X, p: (X) -> Unit) {}\n" +
            "fun main(a: A<String>) { withCallback(a) { (it as B).b; it.b; it.a } }")
    }

    /** A member of the extension receiver is nearer than a same-named top-level function (`kotlin.sequences.iterator`). */
    @Test
    fun anImplicitReceiverMemberShadowsATopLevelFunction() {
        clean("fun <T> Iterable<T>.second(): T? { val i = iterator(); if (!i.hasNext()) return null; i.next(); return if (i.hasNext()) i.next() else null }")
        assertCodes("fun Iterable<String>.g() { val i: Int = iterator() }", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** A generic extension property typed by its getter binds a variable through the receiver parameter's bound, and
     *  a value's extension `invoke` gives its lambda argument a receiver, parenthesized or not. */
    @Test
    fun anExtensionInvokeOnAGenericPropertyTypesTheLambdaReceiver() {
        clean("interface Context<C: Context<C>>\ninterface InterfaceA<C: Context<C>>\nclass ABuilder<C: Context<C>, A: InterfaceA<C>, B: InterfaceA<C>>\n" +
            "operator fun <C, A, B> ABuilder<C, A, B>.invoke(block: B.() -> Unit): Unit where C: Context<C>, A: InterfaceA<C>, B: InterfaceA<C> = null!!\n" +
            "object ContextImpl : Context<ContextImpl>\nobject RootA : InterfaceA<ContextImpl>\nclass AImpl<P: Context<P>> : InterfaceA<P> { fun foo(): Int = null!! }\n" +
            "val <C: Context<C>, A: InterfaceA<C>> A.impl get() = ABuilder<C, A, AImpl<C>>()\n" +
            "fun t1() { RootA.apply { (impl) { foo() } } }\nfun t2() { RootA.apply { impl { foo() } } }\nfun t3() { val x = RootA.impl; x { foo() } }")
    }

    /** A builder's type variable is fixed from the expected type, or from the sibling arguments through `R : K`. */
    @Test
    fun aBuilderReceiverIsFixedFromTheExpectedTypeOrSiblingArguments() {
        clean("interface AAA\nfun <K : AAA, R : K> assign(dest: R, vararg src: K?): R = null as R\nfun <K : AAA> assign2(dest: K, src: K?): K = null as K\n" +
            "class DIV(val tabIndex: String): AAA\nfun <T: AAA> jsObject(builder: T.() -> Unit): T = null as T\n" +
            "fun foo(x: DIV) {\n assign(x, jsObject { tabIndex })\n assign2(x, jsObject { tabIndex })\n val d: DIV = jsObject { tabIndex }\n}")
    }

    /** A smart cast to an unrelated type leaves an intersection: the declared type's members and assignability stay. */
    @Test
    fun aSmartCastToAnUnrelatedTypeIsAnIntersection() {
        clean("interface A { fun fa() }\ninterface B { fun fb() }\nfun f(a: A): B? { if (a is B) { a.fa(); a.fb(); val x: A = a; return a }; return null }")
        assertCodes("interface A { fun fa() }\ninterface B { fun fb() }\nfun g(a: A) { if (a is B) { a.fc() } }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("interface A { fun fa(): Int }\ninterface B { fun fb(): String }\nfun f(a: A) { if (a is B) { val n: Int = a.fa(); val s: String = a.fb(); val bad: String = a.fa() } }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean("sealed interface A\nsealed interface B\ndata class X(val s: String): A, B\ndata class Y(val s: String): A, B\n" +
            "fun problem(a: A): B { return when (a) { is X, is Y -> a } }")
    }

    /** An optional annotation class kept only in the stdlib's module file (`kotlin.js.JsName`) resolves. */
    @Test
    fun anOptionalAnnotationClassFromTheModuleFileResolves() {
        clean("import kotlin.js.*\n@JsName(\"\")\npublic fun test() {}")
    }

    /** A property typed only by its getter, and a setter parameter, take the getter's type; `get() = null` is `Nothing?`. */
    @Test
    fun aGetterOnlyPropertyIsTypedByItsGetter() {
        assertCodes("val x get() = 1\nfun f() { val s: String = x }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        assertCodes("var y\n get() = 1\n set(q) { val s: String = q }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean("val n get() = null\nfun f() { val a: Nothing? = n }")
    }

    /** A lambda used as a value is a function type; references are `KFunctionN`; calling a call's result types. */
    @Test
    fun lambdaValuesAndFunctionReferencesAreTyped() {
        assertCodes("fun f() = { 1 }\nfun t() { val a: String = f()() }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        assertCodes("fun foo(): Int = 1\nfun t() { val a: String = ::foo }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean("import kotlin.reflect.*\nfun foo(): Int = 1\nfun t() { val e: KFunction0<Int> = ::foo; val g: () -> Int = ::foo; val l = { s: String -> s.length }; val n: Int = l(\"x\") }")
        clean("class A { companion object { val p: Int = 1 } }\nfun t() { val x = A::p; val n: Int = x.invoke() }")
    }

    /** `if`/`when` branches of different classes join into their common supertype (intersected when several). */
    @Test
    fun branchesOfDifferentClassesJoin() {
        clean("interface A\ninterface B\ninterface C: A, B\ninterface D: A, B\nfun f(c: C?, d: D?) { val x: B? = if (true) c else d; val y: A? = if (true) c else d }")
        assertCodes("interface O<T>\nclass S<T> : O<T>\nclass N<T> : O<T>\nfun <T> f(s: S<T>) { val a: Int = if (true) N<T>() else s }", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** A `val` assigned from one stable value on every path is an alias of it: a check on either narrows both. */
    @Test
    fun boundSmartCastsThroughAliases() {
        clean("class A { val s = \"\" }\nfun f(a: Any, b: Boolean) { val x: Any\n if (b) x = a else x = a\n a as A\n x.s }")
        assertCodes("fun f(a1: Any) { val a2: Any = a1\n if (a2 is String) { a1.length } }", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** Nearer scopes win: a class member over a same-named top-level function, a local operator over a top-level
     *  one, and a local class that cannot take a call leaves it to the file's class. */
    @Test
    fun nearerDeclarationsWin() {
        assertCodes("class O { fun foo(): String = \"\"\n fun act() { val a: Int = foo() } }\nfun foo() {}", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean("class E\nfun E.plus(o: E) = 0\nfun a() { operator fun E.plus(o: E) = \"\"\n val s: String = E() + E() }")
        clean("class X(x: String)\nfun t() { class X(y: Int)\n X(\"\"); X(1) }")
    }

    /** `is Outer<*>.Inner<*>` narrows to the inner class, not the outer one. */
    @Test
    fun aQualifiedGenericIsTargetNarrowsToTheLastSegment() {
        clean("class Outer<E> { inner class Inner<H> { val prop: E = null!! }\n fun f(y: Any?) { if (y is Outer<*>.Inner<*>) { y.prop } } }")
    }

    /** A `where X : A` naming no type parameter of the declaration is reported once; `where T : A` is a constraint. */
    @Test
    fun aConstraintOnANonTypeParameterIsReported() {
        assertCodes("fun <T> f(t: T) where AA : Number, AA : Comparable<AA> {}", KotlinDiagnosticCodes.NAME_IN_CONSTRAINT_IS_NOT_A_TYPE_PARAMETER)
        clean("interface A\nclass B<T> where T : A")
    }

    /** A member extension on its own type parameter (`operator fun <T> T.invoke`) applies to any receiver. */
    @Test
    fun aGenericMemberExtensionAppliesToAnyReceiver() {
        clean("class F<T>\nclass A9 { operator fun <T> T.invoke(h: () -> Unit) {} }\nfun A9.main(z: F<Int>?) { z.invoke({}) }")
    }

    /** Flow and inference found by the real-world sweep: loop and destructured values smart cast, `isNullOrEmpty`
     *  narrows a written `var`, `filterNotNull` drops the `?`, jumps are `Nothing`, enum entries read bare inside
     *  their enum are the enum, and `getOrDefault(emptyList())` keeps the receiver's element type. */
    @Test
    fun realWorldSweepFlowAndInference() {
        clean("fun a(start: String?) { var o: String? = start; while (!o.isNullOrEmpty()) { o = o.substringBeforeLast('.', \"\").ifEmpty { null } } }")
        clean("class Arg(val s: Boolean)\nfun b(bound: Map<Int, Arg?>) { for ((i, arg) in bound) { if (arg != null && arg.s) println(i) } }")
        clean("class TB(val more: List<Int>)\nfun g(p: Pair<String, TB?>?): List<String> { val (first, tb) = p ?: return emptyList(); if (tb == null) return listOf(first); return tb.more.map { it.toString() } }")
        clean("class St(val u: Set<String>)\nfun h(a: St?, b: List<St?>) { val x = (listOf(a) + b).filterNotNull(); x.map { it.u } }")
        clean("fun g(i: Int) {}\nfun f(names: List<String>, n: String?, i: Int): String { val idx = when { n != null -> names.indexOf(n).let { if (it < 0) return \"x\" else it }; else -> i }; g(idx); return \"\" }")
        clean("enum class V { OUT, IN; fun flip() = when (this) { OUT -> IN; IN -> OUT }; fun t(): V = flip() }")
        assertCodes("class Sym(val n: Int)\nfun t(): List<Sym> = emptyList()\nfun f() { val a: Int = runCatching { t() }.getOrDefault(emptyList()) }", KotlinDiagnosticCodes.TYPE_MISMATCH)
    }

    /** An extension property has no backing field, nor has a property typed by its getter (no type, no
     *  initializer): `field` there is unresolved; a typed member property's `field` resolves. */
    @Test
    fun fieldOutsideABackingFieldIsUnresolved() {
        assertCodes("val String.bar: Int get() = field", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("class A { val u get() = field }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("class B {\n    var Any.y: String\n        get() = \"\"\n        set(arg) { field = arg }\n}", KotlinDiagnosticCodes.UNRESOLVED)
        clean("class C {\n    var n: Int = 0\n        set(v) { field = v }\n    val m: Int = 1\n        get() = field + 1\n}")
        clean("class D { val field: Any = 1; val property get() = field }")
    }

    /** A lambda has an implicit `it` only for a function type with one value parameter (a receiver does not count);
     *  an enclosing lambda's `it` is still visible; overloads that disagree leave it alone. */
    @Test
    fun implicitItNeedsOneParameter() {
        assertCodes("fun bar2(f: () -> Int) {}\nfun t() { bar2 { it } }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("fun t() { val x: String.() -> String = { it } }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("fun takeLambda1(f: String.() -> String) {}\nfun t() { takeLambda1 { it } }", KotlinDiagnosticCodes.UNRESOLVED)
        clean("fun t(l: List<Int>) { l.forEach { run { println(it) } } }")
        clean("fun t(x: String) { x.let { it.length } ; x.also { it.length } }")
        assertTrue(KotlinDiagnosticCodes.UNRESOLVED !in codes("fun o(f: () -> Unit) {}\nfun o(f: (Int) -> Unit) {}\nfun t() { o { it } }"))
        clean("fun t() { listOf(1).map { it + 1 } }")
    }

    /** `@Deprecated(level = HIDDEN)` on a getter hides the property from a read, on a setter from an assignment,
     *  and a hidden member or extension is invisible through a receiver too. */
    @Test
    fun hiddenAccessorsAndMembers() {
        assertCodes("val v1: String\n    @Deprecated(\"\", level = DeprecationLevel.HIDDEN) get() = \"\"\nfun t() { v1 }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("var v4: String\n    get() = \"\"\n    @Deprecated(\"\", level = DeprecationLevel.HIDDEN) set(value) {}\nfun t() { v4; v4 = \"\" }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("@Deprecated(\"\", level = DeprecationLevel.HIDDEN) fun String.ext() {}\nfun t() { \"\".ext() }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("interface H { @Deprecated(\"\", level = DeprecationLevel.HIDDEN) var p: Int }\nfun t(h: H) { h.p }", KotlinDiagnosticCodes.UNRESOLVED)
        clean("interface H { @Deprecated(\"\", level = DeprecationLevel.HIDDEN) var p: Int }\nclass S : H { override var p: Int = 1 }\nfun t(s: S) { s.p; s.p = 2 }")
    }

    /** An operator whose convention function a fully known receiver lacks altogether is unresolved; one a
     *  companion, an extension or a supertype supplies resolves. */
    @Test
    fun missingOperatorConventionOnAKnownType() {
        assertCodes("fun t() { var a = \"\"; a++ }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("object JustMod { fun mod(x: Int) {} }\nfun t() { JustMod % 1 }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("class Foo { operator fun plusAssign(x: Foo) {} }\nfun t() { val f = Foo(); f + f }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("class A\nfun t(a: A, b: A) { a < b }", KotlinDiagnosticCodes.UNRESOLVED)
        clean("class X { companion object { operator fun plus(x: Int) = 1 } }\nfun t() { X + 1 }")
        clean("class Q\noperator fun Q.plus(o: Q) = this\noperator fun Q.inc() = this\nfun t() { var q = Q(); q + q; q++; q += q }")
        clean("enum class C { A, B }\nfun t(a: C, b: C) { a < b; \"a\"..\"b\"; 'a'..<'z'; var c = 'a'; c++ }")
        clean("abstract class Base { operator fun times(x: Int) = this }\nclass Sub : Base()\nfun t(s: Sub) { s * 2 }")
    }

    /** A nested classifier is not reached through an enum entry (a value) or through a typealias qualifier (which
     *  gives the companion's scope only). */
    @Test
    fun nestedClassifierThroughEntryOrAlias() {
        assertCodes("enum class C { E1; class A }\nfun t() { C.E1.A() }", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("class C { class Nested; companion object { val ok = 1 } }\ntypealias TA = C\nval a = TA.Nested()", KotlinDiagnosticCodes.UNRESOLVED)
        clean("class C { class Nested; companion object { val ok = 1 } }\ntypealias TA = C\nval a = TA.ok\nval b = C.Nested()\nval c = TA()")
        clean("enum class E(val label: String) { A(\"a\"); fun f() = 1 }\nfun t() { E.A.label; E.A.f(); E.A.name }")
    }

    /** The type parameters written on an object expression are not in scope of its body. */
    @Test
    fun objectExpressionTypeParametersAreNotInScope() {
        assertTrue(KotlinDiagnosticCodes.UNRESOLVED in codes("val x = object<T> { fun foo(t: T) {} }"))
        clean("class Gen<T> { val anon = object { fun g(t: T): T = t } }")
    }

    /** A `return@label value` must fit the lambda's return type where that type is fixed before the lambda is analyzed
     *  (written in the declared context or the callee's parameter, or by the call's type arguments); an inferred `R`
     *  takes the lambda's returns into account, so nothing returned mismatches it. */
    @Test
    fun labeledReturnAgainstTheLambdasFixedReturnType() {
        val bad = "fun t(a: Int) {\n    run<Int> f@{ if (a > 0) return@f \"\"; return@f 1 }\n}\n" +
            "val bar: (Int) -> String = l@{ a -> if (a == 1) return@l \"4\"; if (a == 2) return@l 2; return@l \"\" }"
        assertEquals(List(2) { KotlinDiagnosticCodes.TYPE_MISMATCH }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "fun t(a: Int, l: List<Int>) {\n    val x = run { if (a > 0) return@run \"\"; 1 }\n" +
            "    l.map { if (it > 0) return@map \"s\"; 2 }\n    run<Any> f@{ if (a > 0) return@f \"\"; 1 }\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A non-local `return` out of a function with an expression body and no written type must fit the body's own
     *  type, in which the `return` is `Nothing`; out of a getter through an inline lambda, the property's type. */
    @Test
    fun nonLocalReturnAgainstAnImplicitExpressionBodyType() {
        val bad = "fun t(a: List<String>) = a.map {\n    if (it.length == 4) return \"\"\n    1\n}\n" +
            "val foo: Int\n    get() = run {\n        if (true) return \"\"\n        1\n    }"
        assertEquals(
            listOf(KotlinDiagnosticCodes.TYPE_MISMATCH, KotlinDiagnosticCodes.TYPE_MISMATCH),
            errors(bad).map { it.code }.filter { it != KotlinDiagnosticCodes.RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE },
            "for `$bad`",
        )
        val ok = "fun t(a: List<String>, b: List<Int>): List<Int> = a.map {\n    if (it.length == 4) return b\n    1\n}\n" +
            "val foo: Int\n    get() = run {\n        if (true) return 2\n        1\n    }"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** `a?.b` is null whenever `a` is: its type is nullable, and a label on an expression does not change its type. */
    @Test
    fun safeCallResultIsNullable() {
        val bad = "val String.copy: String get() = this\nfun f(s: String?) {\n    val a = s?.copy\n    a.length\n" +
            "    val b: Int = s?.length\n    val c: String = foo@ true\n}"
        assertEquals(
            listOf(KotlinDiagnosticCodes.UNSAFE_NULLABLE, KotlinDiagnosticCodes.TYPE_MISMATCH, KotlinDiagnosticCodes.TYPE_MISMATCH),
            errors(bad).map { it.code }, "for `$bad`",
        )
        val ok = "fun f(s: String?, t: String): Int {\n    val a = s?.length\n    if (a != null) a.inc()\n" +
            "    val b: Int = s?.length ?: 0\n    val c = s?.length.toString()\n    val d = t?.length\n    return d + b + c.length\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A `x!!` that runs only on some paths (in a safe call's selector, in one branch of an `if`/`when`) does not
     *  smart cast `x` after its statement; one that always runs does. */
    @Test
    fun conditionalNotNullAssertionDoesNotSmartCast() {
        val bad = "class A\nfun f(x: List<String>?, y: Int?): Int {\n    x?.subList(y!! - 1, y)\n    return y\n}\n" +
            "fun g(a: Any): String {\n    val q: String? = null\n    when (a) {\n        is A -> q!!\n    }\n    return q\n}"
        assertEquals(List(2) { KotlinDiagnosticCodes.TYPE_MISMATCH }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "fun f(y: Int?): Int {\n    y!!\n    return y\n}\n" +
            "fun g(y: Int?, b: Boolean): Int {\n    if (b) y!! else y!!\n    return y\n}\n" +
            "fun h(x: String?, y: Int?): Int {\n    x!!.subSequence(y!!, 1)\n    return y\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** Written type arguments fix a lambda argument's return type before the lambda is analyzed, so the value it
     *  ends with must fit it (`run<Int> { "" }`); `Unit` takes anything, and without them the type is inferred. */
    @Test
    fun lambdaAgainstWrittenTypeArguments() {
        val bad = "interface I\nclass Foo : I\nclass Bar\nfun <T : I> myRun(action: () -> T): T = action()\n" +
            "val a = myRun<Foo> { Bar() }\nfun t() { run<Int> { \"\" } }"
        assertEquals(List(2) { KotlinDiagnosticCodes.TYPE_MISMATCH }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "fun t() {\n    val s: String = run<String> { \"x\" }\n    run<Unit> { 42 }\n" +
            "    val l = listOf(1).map<Int, String> { it.toString() }\n    val r = run { \"\" }\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
        // A receiver that binds the callee's type parameter does not outrank the written argument (`I = E`, not `B`).
        val receiverBound = "interface In<in I>\ninterface B : In<B>\ninterface C<I, T>\nfun <I, T> In<I>.foo(f: () -> C<I, T>) {}\n" +
            "class E : B\nfun t(c: C<E, Int>, e: E) { e.foo<E, Int> { c } }"
        assertEquals(emptyList(), errors(receiverBound).map { it.code }, "for `$receiverBound`")
    }

    /** `T?` stays nullable whatever `T` is bound to, so a call declaring a nullable result is nullable at the call site
     *  (`nul(1)` for `fun <T> nul(x: T): T?`, `l.first()` on a `List<String?>`, `m.get(k)`); and `a?.b == x` being
     *  true, with `x` non-null, makes `a` non-null. */
    @Test
    fun nullableResultThroughSubstitution() {
        val bad = "fun <T> nul(x: T): T? = null\nfun t(l: List<String?>, m: Map<String, Int>) {\n" +
            "    val a: Int = nul(1)\n    val e: String = l.first()\n    val g: Int = m.get(\"a\")\n}"
        assertEquals(List(3) { KotlinDiagnosticCodes.TYPE_MISMATCH }, errors(bad).map { it.code }, "for `$bad`")
        val ok = "object Tok { val ID = Any() }\nclass T(val type: Any, val text: String)\n" +
            "fun f(toks: List<T>, i: Int): String {\n    val nameTok = toks.getOrNull(i + 1)\n" +
            "    if (nameTok?.type == Tok.ID) return nameTok.text\n    val a: Int = listOf(1).first()\n    return \"\"\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A generic call whose argument contradicts its own type variable (`T : CharSequence` from a `List<Int>`,
     *  `T?` below `Int`): the argument is a mismatch, and the variable is left uninferred on the callee where nothing
     *  outside the call constrains it. `Int? <: T?` asks only `Int <: T`. */
    @Test
    fun genericCallArgumentContradictsItsTypeVariable() {
        val decls = "interface In<in T>\ninterface A<T>\ninterface Out<out T>\nfun <T : CharSequence> foo(list: List<T>) {}\n" +
            "fun <T : Any> doIn(i: In<T?>) {}\nfun <T> both(a: A<T>, aN: A<T?>): T = null!!\nfun <T> withOut(a: A<T>, o: Out<T?>): T = null!!\n"
        val bad = decls + "fun t(list: List<Int>, i: In<Int>) {\n    foo(list)\n    doIn(i)\n}"
        assertEquals(
            listOf(KotlinDiagnosticCodes.TYPE_MISMATCH, KotlinDiagnosticCodes.CANNOT_INFER_TYPE, KotlinDiagnosticCodes.TYPE_MISMATCH, KotlinDiagnosticCodes.CANNOT_INFER_TYPE),
            errors(bad).map { it.code }, "for `$bad`",
        )
        val ok = decls + "fun t(s: List<String>, a: A<Int>, aN: A<Int?>, o: Out<Int?>) {\n    foo(s)\n    val x = both(a, aN)\n    val y = withOut(a, o)\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
        // Two arguments' bounds disagreeing: the variable is fixed from one, only the other argument is the mismatch.
        val conflict = decls + "interface P\nclass Q : P\nclass R : P\nfun <T> bar(o: Out<T>, i: In<T>) {}\nfun t(o: Out<Q>, i: In<R>) {\n    bar(o, i)\n}"
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors(conflict).map { it.code }, "for `$conflict`")
    }

    /** A generic delegate class's type variables are inferred from what the property passes its special functions: one
     *  they contradict, or leave with nothing but its declared bound, is uninferred (and the functions inapplicable). */
    @Test
    fun genericDelegateNotInferred() {
        val decls = "import kotlin.reflect.KProperty\nopen class A\nclass B\n" +
            "class MyProperty<R : A, T> { operator fun getValue(thisRef: R, desc: KProperty<*>): T = null!! }\n" +
            "class Unused<R> { operator fun getValue(t: Any?, p: KProperty<*>): Int = 1\n operator fun setValue(t: Any?, p: KProperty<*>, x: Int) {} }\n" +
            "class Used<R> { operator fun getValue(t: Any?, p: KProperty<*>): Int = 1\n operator fun setValue(t: Any?, p: KProperty<*>, x: R) {} }\n"
        val bad = decls + "val B.w: Int by MyProperty()\nvar u: Int by Unused()"
        assertEquals(
            List(2) { listOf(KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE, KotlinDiagnosticCodes.CANNOT_INFER_TYPE) }.flatten(),
            errors(bad).map { it.code }, "for `$bad`",
        )
        val ok = decls + "val A.e: Int by MyProperty()\nvar v: Int by Used()\nvar t: Int by Unused<String>()"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A generic class constructed with nothing to infer its type variables from: no arguments, and no type around
     *  the call (a statement, an untyped initializer, or a written type of stars only). */
    @Test
    fun genericConstructorWithNothingToInferFrom() {
        val decls = "class C<T>()\nclass D<T>(val x: T? = null)\ninterface SelfI<S : SelfI<S>>\nclass A<S : SelfI<S>>\n" +
            "fun <T> bar(): C<T> = null!!\nclass G<T> { inner class Inner }\n"
        val bad = decls + "fun t() {\n    C()\n    val z: C<*> = C()\n    val bz: C<*> = bar()\n}"
        assertEquals(List(3) { KotlinDiagnosticCodes.CANNOT_INFER_TYPE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = decls + "fun t() {\n    val a: C<Int> = C()\n    D()\n    val s: A<*> = A()\n    G<Int>().Inner()\n    val l = listOf(C<Int>())\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
        // An integer literal's type is inference's to choose: `exactBound(1, invB)` makes the `1` a Byte.
        val literal = "class Inv<T>\nfun <T> exactBound(t: T, l: Inv<T>): T = null!!\nfun t(invB: Inv<Byte>) { exactBound(1, invB) }"
        assertEquals(emptyList(), errors(literal).map { it.code }, "for `$literal`")
    }

    /** A lambda with untyped parameters handed where no function type is expected (`Any?`, an unconstrained bare
     *  type variable): nothing types its parameters. `Any?` still takes the lambda itself. */
    @Test
    fun lambdaToNonFunctionParameter() {
        val decls = "fun callAny(arg: Any?) {}\nfun <T> callParam(arg: T) {}\nfun takeFn(f: (Int) -> Int) {}\n"
        val bad = decls + "fun t() {\n    callAny { e -> e }\n    callAny(l@{ e -> e })\n    callParam { p -> p }\n}"
        assertEquals(List(4) { KotlinDiagnosticCodes.CANNOT_INFER_TYPE }, errors(bad).map { it.code }, "for `$bad`")
        val ok = decls + "fun t() {\n    callAny { }\n    callAny { e: Int -> e }\n    takeFn { x -> x }\n    callParam(1)\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
    }

    /** A lambda's untyped parameters past the arity of the function type expected of it have nothing to type them. */
    @Test
    fun lambdaParametersPastExpectedArity() {
        val decls = "fun foo0(f: () -> String) = f\nfun foo1(f: (Int) -> String) = f\nfun <T : (Int) -> Unit> sel(a: T, b: T) = a\n"
        val bad = decls + "fun t() {\n    foo1 { x, y -> \"\" }\n    val f: () -> Int = { it -> 1 }\n}"
        assertEquals(
            listOf(KotlinDiagnosticCodes.CANNOT_INFER_TYPE, KotlinDiagnosticCodes.CANNOT_INFER_TYPE),
            errors(bad).map { it.code }.filter { it == KotlinDiagnosticCodes.CANNOT_INFER_TYPE }, "for `$bad`",
        )
        // A slot typed by a type variable takes its shape from inference, which this does not follow.
        val ok = decls + "fun t() {\n    foo1 { x -> \"\" }\n    foo0 { \"\" }\n    sel({ x -> }, { y -> })\n}"
        assertEquals(emptyList(), errors(ok).map { it.code }, "for `$ok`")
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

    private fun errors(code: String): List<Diagnostic> = analyzerErrors(code) {}

    /** [errors] with the analyzer [configure]d first, as the host would (its explicit API mode, say). */
    private fun analyzerErrors(code: String, configure: (KotlinSourceAnalyzer) -> Unit): List<Diagnostic> = runBlocking {
        val text = "package demo\n$code\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath()))).also(configure)
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }
}

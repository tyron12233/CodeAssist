package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItemKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Completion behaviours found missing by sweeping the IntelliJ Kotlin plugin's own completion test data
 * (KotlinCompletionTestDataSweepTest), one test per fixed group, each in the shape of the plugin's case:
 * the scope of nested classes, companions and class headers, do-while conditions, parameter name-and-type
 * suggestions, the named-argument rules, operator names, and the keyword vocabulary (compound modifiers,
 * labeled `return@`/`break@`/`this@`, `this`/`super` only where they mean something).
 */
class KotlinCompletionParityTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }

    private fun keywords(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items
            .filter { it.kind == CompletionItemKind.KEYWORD }.map { it.label }

    // --- scope: nested classes, companions, class headers ---

    @Test
    fun aCompanionSeesTheNestedClassesButNotTheOuterInstance() {
        fun at(prefix: String) = labels(
            "package p1\nclass C {\n  val field = 0\n  class NestedClass\n  inner class InnerClass\n" +
                "  companion object {\n    val classObjectField = 0\n    fun foo() { $prefix| }\n  }\n}\nfun C.extensionForC() {}",
        )
        assertTrue("NestedClass" in at("Nes") && "InnerClass" in at("Inn"), "nested classes are in scope by simple name")
        assertTrue("classObjectField" in at("cla"), "the companion's own members are in scope")
        assertFalse("field" in at("fie"), "the outer class's instance member has no `this` in a companion")
        assertFalse(at("ext").any { it.startsWith("extensionForC") }, "nor does an extension on the outer class")
    }

    @Test
    fun anInnerClassKeepsTheOuterInstance() {
        val ls = labels("package p2\nclass C {\n  val field = 0\n  inner class N {\n    fun foo() { | }\n  }\n}")
        assertTrue("field" in ls, "an inner class reads the outer instance; got $ls")
    }

    @Test
    fun aPrimaryConstructorDefaultSeesOnlyEarlierParametersAndNoMembers() {
        val ls = labels("package p3\nclass Foo(foo: Int, bar: Int = |, baz: Int = 0) {\n  fun memberFun() = 1\n}")
        assertTrue("foo" in ls, "an earlier parameter is in scope; got $ls")
        assertFalse("bar" in ls || "baz" in ls, "the parameter itself and later ones are not; got $ls")
        assertFalse(ls.any { it.startsWith("memberFun") }, "no member exists before the instance does; got $ls")
    }

    @Test
    fun aDoWhileConditionSeesTheBodysLocals() {
        val ls = labels("package p4\nfun f() {\n  do {\n    val shouldContinue = true\n  } while (shouldCont|)\n}")
        assertTrue("shouldContinue" in ls, "the do-while condition sees its body's locals; got $ls")
    }

    // --- parameter name and type ---

    @Test
    fun aParameterNameIsSuggestedFromATypeName() {
        val ls = labels("package p5\nclass FooBar\nclass Boo\nfun f(b|)")
        assertTrue("bar: FooBar" in ls && "boo: Boo" in ls, "a camel tail of a type names the parameter; got $ls")
        assertFalse("fooBar: FooBar" in ls, "a suggestion the prefix does not fit is left out; got $ls")
    }

    @Test
    fun aTypedHeadIsKeptInTheSuggestedName() {
        val ls = labels("package p6\nclass FooFaa\nclass Fuu\nfun f(myFooF|)")
        assertTrue("myFooFaa: FooFaa" in ls && "myFooFuu: Fuu" in ls, "got $ls")
        assertFalse("myFooFooFaa: FooFaa" in ls, "the first split that matches wins; got $ls")
    }

    @Test
    fun aParameterOfThisFileIsSuggestedAsWritten() {
        val ls = labels("package p7\nfun g(strings: List<String>) {}\nfun <T> h(value: T) {}\nfun f(|)")
        assertTrue("strings: List<String>" in ls, "another function's parameter is offered as written; got $ls")
        assertFalse("value: T" in ls, "one typed with a type parameter not in scope is not; got $ls")
    }

    // --- named arguments ---

    @Test
    fun aPositionallyFilledParameterIsNotOfferedByName() {
        val ls = labels("package p8\nfun small(first: Int, second: Int) {}\nfun t() = small(12, |)")
        assertTrue("second =" in ls, "got $ls")
        assertFalse("first =" in ls, "`first` is filled by the positional argument; got $ls")
    }

    @Test
    fun theNextBooleanParameterOffersItsValues() {
        val ls = labels("package p9\nfun foo(p: Int, flag: Boolean) {}\nfun t() { foo(1, |) }")
        assertTrue("flag = true" in ls && "flag = false" in ls, "got $ls")
    }

    @Test
    fun aSameNamedValuePrefillsTheArgument() {
        val ls = labels("package p10\nfun foo(a: Int) {}\nfun t() {\n  val a = 5\n  foo(|)\n}")
        assertTrue("a = a" in ls && "a =" in ls, "got $ls")
    }

    @Test
    fun theValueOfANamedArgumentOffersNoNames() {
        val ls = labels("package p11\nval paramTest = 12\nfun small(paramFirst: Int, paramSecond: Int) {}\nfun t() = small(paramFirst = param|)")
        assertTrue("paramTest" in ls, "got $ls")
        assertFalse(ls.any { it.endsWith(" =") }, "a value position takes no argument name; got $ls")
    }

    @Test
    fun afterANamedArgumentOutOfPositionOnlyNamesFollow() {
        val ls = labels("package p12\nfun foo(first: Int, second: Int, third: String) {}\nfun t(p: Int) = foo(12, third = \"\", |)")
        assertTrue("second =" in ls, "got $ls")
        assertFalse("p" in ls, "a positional argument cannot follow a named one out of position; got $ls")
        assertFalse("first =" in ls || "third =" in ls, "got $ls")
    }

    @Test
    fun aSourceParameterNamedLikeAStrippedJavaOneIsStillOffered() {
        val ls = labels("package p13\nfun foo(p1: Int, p2: Int, p3: Long) {}\nfun t() { foo(p1 = 10, p|) }")
        assertTrue("p2 =" in ls && "p3 =" in ls, "`p2` is a real Kotlin parameter name; got $ls")
        assertFalse("p1 =" in ls, "got $ls")
    }

    // --- operator names ---

    @Test
    fun anOperatorFunNameOffersTheConventions() {
        val ls = labels("package p14\nclass Some {\n  operator fun pl|() {}\n}")
        assertTrue("plus" in ls && "plusAssign" in ls, "got $ls")
        val top = labels("package p15\noperator fun pl|() {}")
        assertFalse("plus" in top, "a top-level operator fun with no receiver overloads nothing; got $top")
    }

    // --- keywords ---

    @Test
    fun aMemberPositionOffersCompoundModifiers() {
        val ks = keywords("package p16\nclass A {\n  |\n}")
        assertTrue("lateinit var" in ks && "suspend fun" in ks && "companion object" in ks, "got $ks")
        assertFalse("lateinit" in ks || "abstract class" in ks || "const val" in ks, "got $ks")
        val inObject = keywords("package p17\nobject O {\n  |\n}")
        assertTrue("const val" in inObject, "an object may declare a const; got $inObject")
        assertFalse("constructor" in inObject || "companion object" in inObject || "protected" in inObject, "got $inObject")
    }

    @Test
    fun noImportOrPackageAfterADeclaration() {
        val ks = keywords("package p18\nclass A\n|")
        assertFalse("import" in ks || "package" in ks, "got $ks")
        assertTrue("fun" in ks, "got $ks")
    }

    @Test
    fun labeledReturnBreakAndThisAreOffered() {
        val ret = keywords("package p19\nfun f() { listOf(1).forEach { ret| } }")
        assertTrue("return@forEach" in ret, "got $ret")
        val brk = keywords("package p20\nfun f() { outer@ for (i in 1..2) { for (j in 1..2) { brea| } } }")
        assertTrue("break@outer" in brk, "got $brk")
        val ths = keywords("package p21\nclass Outer { inner class Inner { fun f() { th| } } }")
        assertTrue("this@Inner" in ths && "this@Outer" in ths, "got $ths")
        val nested = keywords("package p22\nclass Outer { class Nested { fun f() { th| } } }")
        assertFalse("this@Outer" in nested, "a nested class has no outer `this`; got $nested")
    }

    @Test
    fun thisAndSuperOnlyWhereTheyMeanSomething() {
        val ks = keywords("package p23\nfun f() { | }")
        assertFalse("this" in ks || "super" in ks, "a top-level function has neither; got $ks")
        val inClass = keywords("package p24\nclass A { fun f() { | } }")
        assertTrue("this" in inClass && "super" in inClass, "got $inClass")
    }

    // Per test instance, not in a companion: an analyzer parked in a static is heap that every later test
    // class in the fork runs without.
    private val srcDir: Path = tempProject(mapOf("Seed.kt" to "package seed\nclass Seed"))
    private val analyzer by lazy { KotlinSourceAnalyzer(fakeContext(srcDir)) }
}

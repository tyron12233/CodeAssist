package dev.ide.lang.kotlin

import dev.ide.lang.LanguageId
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.CompletionWeigher
import dev.ide.lang.completion.STANDARD_WEIGHERS
import dev.ide.lang.dom.TextRange
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // The innermost receiver is the plain `this`, so its own label is left out (IntelliJ's keywords/This.kt).
        assertTrue("this@Outer" in ths && "this@Inner" !in ths, "got $ths")
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

    // --- ranking, the way the engine orders the items (tier, the standard weighers, sort priority) ---

    /** The labels in the order the completion engine shows them. */
    private fun ranked(code: String): List<String> {
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items
        val caret = code.indexOf('|')
        var start = caret
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) start--
        val doc = SnippetDoc(code.replace("|", ""), DiskFile(srcDir.resolve("Use.kt")))
        val params = CompletionParams(
            doc, caret, code.substring(start, caret), LanguageId("kotlin"), CompletionTrigger.Explicit,
            TextRange(start, caret), null, null,
        )
        val tier = object : CompletionWeigher {
            override val id = "test.tier"
            override val order = 100
            override fun weigh(item: CompletionItem, params: CompletionParams): Double = when {
                item.kind == CompletionItemKind.WORD -> 0.0
                (item.kind == CompletionItemKind.SNIPPET || item.kind == CompletionItemKind.KEYWORD) && item.sortPriority > 0 -> 1.0
                else -> 2.0
            }
        }
        val priority = object : CompletionWeigher {
            override val id = "test.sortPriority"
            override val order = 1000
            override fun weigh(item: CompletionItem, params: CompletionParams): Double = -item.sortPriority.toDouble()
        }
        val weighers = (listOf(tier, priority) + STANDARD_WEIGHERS).sortedBy { it.order }
        val weights = items.map { item -> weighers.map { it.weigh(item, params) } }
        return items.indices.sortedWith { a, b ->
            for (k in weighers.indices) {
                val c = weights[b][k].compareTo(weights[a][k])
                if (c != 0) return@sortedWith c
            }
            0
        }.map { items[it].label.substringBefore('(').trim() }
    }

    @Test
    fun aReturnWhereTheBlockEndsRanksFirst() {
        val ls = ranked("package r1\nfun returnFun() {}\nfun usage(): Int {\n  if (true) {\n    re|\n  }\n  return 1\n}")
        assertEquals("return", ls.first(), "the last statement of a nested block is where a return goes; got ${ls.take(5)}")
        val top = ranked("package r2\nfun returnFun() {}\nfun usage() {\n  re|\n}")
        assertEquals("returnFun", top.first(), "falling off a Unit body's end returns already; got ${top.take(5)}")
    }

    @Test
    fun keywordsRankAfterTheCallablesAndBeforeTheClasses() {
        val ls = ranked("package r3\nfun main(variables: Array<String>) {\n  val values = \"\"\n  va|\n}")
        assertEquals(listOf("values", "variables", "val", "var"), ls.take(4), "got ${ls.take(8)}")
        val brk = ranked("package r4\nfun bar(breach: Int) {\n  while (true)\n    bre|\n}")
        assertEquals(listOf("breach", "break"), brk.take(2), "a keyword outranks a class that needs an import; got ${brk.take(5)}")
    }

    @Test
    fun theInnermostLocalsRankFirst() {
        val ls = ranked("package r5\nfun test(fals: Int) {\n  val falt = 111\n  fal|\n}")
        assertEquals(listOf("falt", "fals", "false"), ls.take(3), "got ${ls.take(5)}")
    }

    @Test
    fun nullRanksFirstWhereNothingElseIsKnown() {
        val ls = ranked("package r6\nfun nulls() {}\nfun foo() {\n  notYetImported(nu|\n}")
        assertEquals(listOf("null", "nulls"), ls.take(2), "got ${ls.take(5)}")
        val any = ranked("package r7\nclass A {\n  val name = \"name\"\n  fun testMeAny(): Any? {\n    return n|\n  }\n}")
        assertEquals(listOf("null", "name"), any.take(2), "`null` is what an `Any?` slot is most often given; got ${any.take(5)}")
    }

    @Test
    fun anObjectOfTheExpectedTypeRanksAsAValue() {
        val ls = ranked("package r8\ninterface Foo\nclass CandidateClass : Foo\nobject CandidateObject : Foo\nfun foo(f: Foo) {\n  if (f == Candidate|) {\n  }\n}")
        assertEquals("CandidateObject", ls.first(), "the other side of `==` is the expected type; got ${ls.take(4)}")
    }

    @Test
    fun aSmartCastLocalFitsTheExpectedType() {
        val ls = ranked("package r9\nfun test(valueA: Any, valueZ: Any): String {\n  if (valueZ is String) {\n    return value|\n  }\n  return \"\"\n}")
        assertEquals(listOf("valueZ", "valueA"), ls.take(2), "got ${ls.take(4)}")
        val reassigned = ranked("package r10\nfun test(input: Any, valueZ: String): String {\n  var valueA = input\n  if (valueA is String) {\n    valueA = 42\n    return value|\n  }\n  return \"\"\n}")
        assertEquals(listOf("valueZ", "valueA"), reassigned.take(2), "an assignment ends the smart cast; got ${reassigned.take(4)}")
    }

    @Test
    fun aValueOfAnotherTypeRanksBelowTheKeywords() {
        val ls = ranked("package r11\nfun f(b: Boolean, tra: Int) {}\nfun test(tri: Boolean, trb: Int) {\n  f(tr|)\n}")
        assertEquals(listOf("tri", "true", "tra =", "try", "trb"), ls.take(5), "got ${ls.take(6)}")
    }

    @Test
    fun namedArgumentsComeFirstInAMultiLineCall() {
        val ls = ranked("package r12\nclass Foo(\n  somePrefixA: Int,\n  somePrefixB: Int,\n)\nfun foo(somePrefixValue: Int, somePrefixUnrelated: String) {\n  Foo(\n    somePrefix|\n  )\n}")
        assertEquals(listOf("somePrefixA =", "somePrefixB =", "somePrefixValue", "somePrefixUnrelated"), ls.take(4), "got ${ls.take(5)}")
    }

    @Test
    fun aValueNamedLikeTheParameterRanksFirst() {
        val ls = ranked("package r13\nclass User(val name: String)\nfun test(someName: String) {\n  val aVariable = \"\"\n  val name = \"\"\n  User(|)\n}")
        assertEquals(listOf("name", "someName"), ls.take(2), "got ${ls.take(5)}")
        val types = ranked("package r14\nopen class Apple\nclass GreenApple : Apple()\nclass RedApple : Apple()\nval apple: |")
        assertEquals(listOf("Apple", "GreenApple", "RedApple"), types.take(3), "a type named like the property ranks first; got ${types.take(5)}")
    }

    @Test
    fun theExpectedTypesCompanionFactoriesAreOffered() {
        val ls = labels("package r16\nclass Foo {\n  companion object {\n    val foo: Foo = Foo()\n    fun baz(): Foo = Foo()\n    fun qux() = \"\"\n  }\n}\nval a: Foo = |")
        assertTrue("Foo.foo" in ls && ls.any { it.startsWith("Foo.baz(") }, "got ${ls.take(12)}")
        assertFalse(ls.any { it.startsWith("Foo.qux") }, "a factory of another type is not offered; got ${ls.take(12)}")
    }

    @Test
    fun aWhenBranchOffersIsChecksAndElse() {
        val ls = ranked("package r17\nsealed class SEALED\nclass AAAA : SEALED()\nobject BBBB : SEALED()\nclass CCCC : SEALED()\nfun foo(e: SEALED) {\n  when (e) {\n    |\n  }\n}")
        val want = listOf("is AAAA", "BBBB", "is CCCC", "else ->")
        assertEquals(want, ls.filter { it in want }.take(4), "got ${ls.take(8)}")
        assertTrue(ls.indexOf("else ->") < ls.indexOf("SEALED"), "the bare subject type comes after the branches; got ${ls.take(8)}")
    }

    @Test
    fun aPlainReturnOnlyInsideInlinedLambdas() {
        val code = "package r18\nfun takeHandler1(handler: () -> Unit) {}\ninline fun takeHandler2(handler: () -> Unit) {}\n" +
            "fun foo() {\n  takeHandler1 {\n    takeHandler2 { | }\n  }\n}"
        val ks = keywords(code)
        assertFalse("return" in ks, "a non-inline lambda cannot return from foo; got $ks")
        assertTrue("return@takeHandler1" in ks && "return@takeHandler2" in ks, "got $ks")
        val inline = keywords("package r19\nfun foo() {\n  listOf(1).forEach { | }\n}")
        assertTrue("return" in inline, "forEach is inline; got $inline")
    }

    @Test
    fun anExtensionWhoseBoundsRejectTheReceiverIsLeftOut() {
        val ls = labels(
            "package r20\nopen class Base\nopen class OtherBase\nclass Some<T>\n" +
                "fun <TSome : Base> Some<TSome>.extensionBase() = 1\nfun <TSome : OtherBase> Some<TSome>.extensionOtherBase() = 1\n" +
                "fun test() {\n  Some<Base>().ex|\n}",
        )
        assertTrue(ls.any { it.startsWith("extensionBase") }, "got $ls")
        assertFalse(ls.any { it.startsWith("extensionOtherBase") }, "Base is no OtherBase; got $ls")
        val member = labels("package r21\nclass Foo {\n  fun Int.bar() = this\n}\nfun main() {\n  Foo().|\n}")
        assertFalse(member.any { it.startsWith("bar") }, "an Int extension declared in Foo is no member of Foo; got ${member.take(10)}")
    }

    @Test
    fun keywordsMatchTheirCase() {
        assertTrue(keywords("package r22\nP|").isEmpty(), "a capitalized prefix names a type, not a keyword")
        assertTrue(labels("package r23\nclass T {\n  fun f() {\n    '|'\n  }\n}").isEmpty(), "a character literal holds no code")
    }

    // --- keywords: type positions, casts, use-site targets, accessors, the file's class, return labels ---

    @Test
    fun aTypeMayStartWithSuspendOrContextAndAnArgumentWithItsVariance() {
        val plain = keywords("package s1\nfun f(a: |) {}")
        assertTrue("suspend" in plain && "context" in plain, "got $plain")
        assertFalse("in" in plain || "out" in plain, "a parameter's own type takes no projection; got $plain")
        val argument = keywords("package s2\nclass Box<T>\nfun f(a: Box<|>) {}")
        assertTrue("in" in argument && "out" in argument && "suspend" in argument, "got $argument")
        val call = keywords("package s3\nfun f() { mutableListOf<|>() }")
        assertFalse("in" in call, "a call's type argument takes no projection; got $call")
    }

    @Test
    fun anElseLessIfOrATryMayStillBeCast() {
        val ks = keywords("package s4\nfun f(p: Int) {\n  if (p > 0) {\n  }\n  |\n}")
        assertTrue("as" in ks && "as?" in ks && "else" in ks, "got $ks")
        assertFalse("as" in keywords("package s5\nfun f() {\n  val x = 1\n  |\n}"), "a declaration is no operand")
    }

    @Test
    fun anAnnotationStartOffersTheUseSiteTargets() {
        val ks = keywords("package s6\nannotation class Ann\nclass C(@|)")
        assertTrue(listOf("file:", "get:", "param:", "field:").all { it in ks }, "got $ks")
        val negated = keywords("package s7\nfun f(v: String) {\n  if (v !|) {}\n}")
        assertTrue("in" in negated && "is" in negated, "got $negated")
        assertFalse("if" in negated || "val" in negated, "only the negated operators follow a `!` after an operand; got $negated")
    }

    @Test
    fun aPropertyIsContinuedByItsAccessorsWrittenOut() {
        val ks = keywords("package s8\nclass Some {\n  var a: Int = 1\n    |\n}")
        assertTrue(listOf("get", "get() = ...", "get() {...}", "set(value) = ...", "as").all { it in ks }, "got $ks")
        val sameLine = keywords("package s9\nclass Some {\n  var a: Int |\n}")
        assertFalse("fun" in sameLine, "no declaration starts on the property's own line; got $sameLine")
    }

    @Test
    fun aTopLevelClassIsOfferedUnderTheFilesName() {
        val ks = keywords("package s10\n|")
        assertTrue("class Use" in ks && "sealed interface Use" in ks, "the file is Use.kt; got $ks")
        val declared = keywords("package s11\nclass Use\n|")
        assertFalse("class Use" in declared, "the file already declares it; got $declared")
    }

    @Test
    fun aReturnLabelStopsAtTheFirstLambdaThatIsNotInlined() {
        val ks = keywords(
            "package s12\nfun takeHandler1(handler: () -> Unit) {}\nfun takeHandler2(handler: () -> Unit) {}\n" +
                "fun foo() {\n  takeHandler1 {\n    takeHandler2 { | }\n  }\n}",
        )
        assertTrue("return@takeHandler2" in ks, "got $ks")
        assertFalse("return@takeHandler1" in ks, "no return reaches past a lambda that is not inlined; got $ks")
        val quoted = keywords("package s13\ninline fun `fun`(handler: () -> Unit) {}\nfun foo() {\n  `fun` { ret| }\n}")
        assertTrue("return@`fun`" in quoted, "a keyword label is quoted; got $quoted")
    }

    // --- what a position offers ---

    @Test
    fun aContextualFunctionIsOfferedOnlyWhereItsContextIsAvailable() {
        val code = "package s14\ncontext(_: String)\nfun fooString() {}\ncontext(_: Int)\nfun fooInt() {}\nfun fooNone() {}\n"
        val none = labels(code + "fun test() {\n  foo|\n}")
        assertTrue(none.any { it.startsWith("fooNone") }, "got $none")
        assertFalse(none.any { it.startsWith("fooString") || it.startsWith("fooInt") }, "no context value in scope; got $none")
        val withString = labels(code + "fun test() {\n  with(\"\") {\n    foo|\n  }\n}")
        assertTrue(withString.any { it.startsWith("fooString") }, "got $withString")
        assertFalse(withString.any { it.startsWith("fooInt") }, "got $withString")
    }

    @Test
    fun aContextParameterIsAValueInItsBody() {
        val ls = labels("package s15\ninterface Logger\ncontext(logger: Logger)\nfun log() {\n  logge|\n}")
        assertTrue("logger" in ls, "got $ls")
    }

    @Test
    fun anAnnotationArgumentOffersOnlyCompileTimeConstants() {
        val code = "package s16\nconst val constInt: Int = 1\nval nonConstInt: Int = 1\nfun foo(): Int = 1\n" +
            "annotation class A(val n: Int)\n@A(|)\nclass B"
        val ls = labels(code)
        assertTrue("constInt" in ls, "got ${ls.take(20)}")
        assertFalse("nonConstInt" in ls || ls.any { it.startsWith("foo(") }, "got ${ls.take(20)}")
        val ks = keywords(code)
        assertTrue("true" in ks && "when" in ks, "got $ks")
        assertFalse("null" in ks || "for" in ks || "throw" in ks, "got $ks")
    }

    @Test
    fun anImportAliasIsOfferedByItsAlias() {
        val ls = labels("package s17\nimport kotlin.collections.ArrayList as KotlinArrayList\nfun foo(): KotAr|")
        assertTrue("KotlinArrayList" in ls, "got $ls")
    }

    @Test
    fun aLambdaOffersItsParameterList() {
        val named = labels("package s18\nfun <T> T.foo(block: (reference: T) -> Unit) {}\nfun bar() {\n  42.foo { | }\n}")
        assertTrue("reference" in named, "the function type names it; got ${named.take(10)}")
        val made = labels("package s19\nclass FooBar\nfun bar() {\n  FooBar().let { | }\n}")
        assertTrue("fooBar" in made && "bar" in made, "named after the type; got ${made.take(10)}")
        val destructured = labels("package s20\ndata class P(val x: Int, val y: String)\nfun bar() {\n  P(1, \"\").let { | }\n}")
        assertTrue("(x, y)" in destructured, "got ${destructured.take(10)}")
        val rest = labels("package s21\nfun <T, U> foo(a: T, b: U, block: (first: T, second: U) -> Unit) {}\nfun bar() {\n  foo(1, \"\") { first, | }\n}")
        assertTrue("second" in rest, "the rest of the list; got $rest")
        val own = labels("package s22\nfun <T, U> foo(a: T, b: U, block: (first: T, second: U) -> Unit) {}\nfun bar() {\n  foo(1, \"\") { acc, | }\n}")
        assertFalse("second" in own, "names of the writer's own are not continued; got $own")
    }

    @Test
    fun anObjectsExtensionAppliesToAnImplicitReceiver() {
        val ls = labels("package s23\nclass T\nobject A {\n  fun T.fooExtension() {}\n}\nfun T.usage() {\n  foo|\n}")
        assertTrue(ls.any { it.startsWith("fooExtension") }, "got $ls")
    }

    @Test
    fun aQualifiedTypeOffersWhatItsQualifierHolds() {
        val ls = labels("package s24\nclass Outer {\n  class Nested\n}\nval x: Outer.|")
        assertTrue("Nested" in ls, "got $ls")
        assertFalse("Any" in ls, "not every type by name; got ${ls.take(10)}")
    }

    @Test
    fun anUntypedLateinitIsNamedWithItsType() {
        val ls = labels("package s25\nclass Foo\nclass BarFoo\nlateinit var f|")
        assertTrue("foo: Foo" in ls && "foo: BarFoo" in ls, "got $ls")
    }

    // --- matching, visibility, declaration names, receivers ---

    @Test
    fun theFirstLetterTypedKeepsItsCase() {
        val lower = labels("package s26\nclass SomeType\nclass someRareType\nfun test(a: s|) {}")
        assertTrue("someRareType" in lower, "got $lower")
        assertFalse("SomeType" in lower, "a lowercase first letter does not reach a capitalized name; got $lower")
        val upper = labels("package s27\nclass SomeType\nclass someRareType\nfun test(a: S|) {}")
        assertTrue("SomeType" in upper && "someRareType" !in upper, "got $upper")
    }

    @Test
    fun protectedAndPrivateMembersAreOfferedOnlyWhereTheyAreVisible() {
        val outside = labels("package s28\nclass Some {\n  val pub = 1\n  protected val prot = 1\n  private val priv = 1\n}\nfun t() { Some().| }")
        assertTrue("pub" in outside, "got $outside")
        assertFalse("prot" in outside || "priv" in outside, "got $outside")
        val sub = labels("package s29\nopen class Some {\n  protected val prot = 1\n  private val priv = 1\n}\nclass Sub : Some() {\n  fun t() { | }\n}")
        assertTrue("prot" in sub, "a subclass sees the protected member; got ${sub.take(20)}")
        assertFalse("priv" in sub, "but not the superclass's private one; got ${sub.take(20)}")
        val companion = labels("package s30\nopen class Base {\n  companion object {\n    val pubVal = 1\n    protected val protVal = 1\n  }\n}\nfun t() { Base.| }")
        assertTrue("pubVal" in companion && "protVal" !in companion, "got $companion")
    }

    @Test
    fun aDeclarationIsNamedAfterAnUnresolvedReference() {
        val fn = labels("package s31\nfun foo() {\n  unresolvedCall()\n  val v = unresolvedValue\n}\nfun |")
        assertTrue("unresolvedCall" in fn, "a function is named after a call; got $fn")
        assertFalse("unresolvedValue" in fn, "not after a value read; got $fn")
        val cls = labels("package s32\nfun foo(p: Missing1) {\n  val x = Missing2()\n}\nclass |")
        assertTrue("Missing1" in cls && "Missing2" in cls, "a class after a type and a constructor call; got $cls")
    }

    @Test
    fun aTopLevelClassIsOfferedTheFileName() {
        val ls = runBlocking { analyzer.completeAtCaret(srcDir, "Gadget.kt", "package s33\nclass |") }.items.map { it.label }
        assertTrue("Gadget" in ls, "got $ls")
    }

    @Test
    fun theExpectedClassAlsoCompletesAsAConstructorCall() {
        val items = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package s34\nclass Widget(a: Int)\nfun create(): Widget {\n  return Widg|\n}")
        }.items.filter { it.label.startsWith("Widget") }
        assertTrue(items.any { it.label == "Widget" && it.insertText == "Widget" }, "the class inserts bare; got $items")
        assertTrue(items.any { it.label == "Widget(a: Int)" && it.insertText.startsWith("Widget(") }, "got $items")
    }

    @Test
    fun aTypealiasReceiverReachesWhatItAliases() {
        val enumLs = labels("package s35\nenum class A { ONE; class B }\ntypealias AA = A\ntypealias AAA = AA\nfun t() { AAA.| }")
        assertTrue("ONE" in enumLs, "got $enumLs")
        assertFalse("B" in enumLs, "a nested class is not reached through an alias; got $enumLs")
        val obj = labels("package s36\nobject Obj { val inObject = 1 }\ntypealias TA = Obj\nval u = TA.|")
        assertTrue("inObject" in obj, "got $obj")
    }

    @Test
    fun aDotAfterATemplateEntryCompletesItsMembers() {
        val items = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package s37\nfun foo(param: String) {\n  val s = \"\$param.len|bla\"\n}")
        }.items
        val length = items.firstOrNull { it.label == "length" }
        assertTrue(length != null, "got ${items.map { it.label }}")
        assertEquals("length}", length!!.insertText, "the entry becomes a block")
        assertTrue(length.additionalEdits.any { it.newText == "{" }, "got ${length.additionalEdits}")
    }

    @Test
    fun strayRangeDotsOfferNothing() {
        assertTrue(labels("package s38\nclass Test..|").isEmpty())
        val ls = labels("package s39\nclass Foo\nclass Bar\nfun Foo.rangeTo(bar: Bar): IntRange = IntRange.EMPTY\nfun t() {\n  val bar = Bar()\n  Foo()..|\n}")
        assertTrue(ls.isEmpty(), "a rangeTo without `operator` is no range; got $ls")
        val range = labels("package s40\nfun t() {\n  val b2 = 2\n  val b = 3..|\n}")
        assertTrue("b2" in range, "got $range")
    }

    @Test
    fun aPropertyIsNotOfferedInItsOwnInitializer() {
        val ls = labels("package s41\nclass A {\n  val xxx: Int = this.xx|\n}")
        assertFalse("xxx" in ls, "got $ls")
    }

    @Test
    fun aClassTypeParameterTakesOnlyItsModifiers() {
        val kws = keywords("package s42\nclass T<|>")
        assertTrue(kws.containsAll(listOf("in", "out", "reified")), "got $kws")
        assertFalse("fun" in kws || "val" in kws, "no declaration keywords; got $kws")
    }

    // --- ported weighers and contributors ---

    @Test
    fun aNullCheckedOrIntersectedValueRanksByItsSmartCastType() {
        val nullCheck = ranked("package w1\nfun test(valueA: String?, valueZ: String?): String {\n  if (valueZ != null) {\n    return value|\n  }\n  return \"\"\n}")
        assertEquals(listOf("valueZ", "valueA"), nullCheck.take(2), "got $nullCheck")
        val member = ranked("package w2\nclass Box(val valueA: Any, val valueZ: Any)\nfun test(box: Box): String {\n  if (box.valueZ is String) {\n    return box.value|\n  }\n  return \"\"\n}")
        assertEquals(listOf("valueZ", "valueA"), member.take(2), "got $member")
    }

    @Test
    fun aSealedSubjectOfAnIsCheckRanksItsInheritorsFirst() {
        val ls = ranked("package w3\nclass A1\nsealed class Foo\nclass Bar : Foo()\nclass Baz : Foo()\nfun f(foo: Foo) {\n  if (foo is |) {}\n}")
        assertEquals(listOf("Bar", "Baz", "Foo"), ls.take(3), "got ${ls.take(6)}")
    }

    @Test
    fun aDeprecatedCandidateSinksUnlessItsDeprecationLiesAhead() {
        val ls = ranked(
            "package w4\nfun foo1() {}\n@Deprecated(\"\")\n@DeprecatedSinceKotlin(warningSince = \"1.0\")\nfun foo2() {}\n" +
                "@Deprecated(\"\")\n@DeprecatedSinceKotlin(warningSince = \"999.999\")\nfun foo3() {}\nfun foo4() {}\nfun test() {\n  foo|\n}",
        )
        assertEquals(listOf("foo1", "foo3", "foo4", "foo2"), ls.take(4), "got $ls")
    }

    @Test
    fun ownMembersThenOverridesAndAnysMembersThenExtensions() {
        val ls = ranked(
            "package w5\ninterface Common { fun xxFun1() }\nclass A : Common {\n  override fun xxFun1() {}\n  fun xxFun2() {}\n}\n" +
                "fun test(a: A) {\n  a.|\n}",
        )
        assertEquals(listOf("xxFun2", "xxFun1", "equals", "hashCode", "toString"), ls.take(5), "got ${ls.take(8)}")
    }

    @Test
    fun theNearerImplicitReceiversMembersComeFirst() {
        val ls = ranked(
            "package w6\nclass A { fun fooA() {} }\nclass B { fun fooB(): Int = 3 }\nfun <T> myWith(t: T, init: T.() -> Unit) {}\n" +
                "fun main() {\n  myWith(A()) {\n    myWith(B()) {\n      foo|\n    }\n  }\n}",
        )
        assertEquals(listOf("fooB", "fooA"), ls.take(2), "got $ls")
    }

    @Test
    fun aMemberSeveralSupertypesProvideIsOfferedOncePerSupertype() {
        val items = runBlocking {
            analyzer.completeAtCaret(
                srcDir, "Use.kt",
                "package w7\ninterface A { fun foo(i: Int) {} }\ninterface B { fun foo(i: Int) {} }\nfun A.bar() {}\n" +
                    "class C : A, B {\n  override fun foo(j: Int) {\n    super.fo|\n  }\n}",
            )
        }.items.filter { it.label.startsWith("foo") }
        assertEquals(4, items.size, "foo(i: Int) and foo(j), each for A and for B; got ${items.map { it.label + " " + it.detail }}")
        assertTrue(items.any { it.label == "foo(j)" && it.additionalEdits.any { e -> e.newText == "super<B>" } }, "got $items")
    }

    @Test
    fun anIndexingOperatorIsOfferedAsBrackets() {
        val items = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package w8\nclass T {\n  operator fun get(a: Int): Int = 0\n}\nfun f(t: T) {\n  t.|\n}")
        }.items
        val brackets = items.firstOrNull { it.label.startsWith("[]") }
        assertTrue(brackets != null, "got ${items.map { it.label }}")
        assertEquals("[]", brackets!!.insertText)
        assertTrue(runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package w9\nclass T {\n  operator fun get(a: Int): Int = 0\n}\nfun f(t: T?) {\n  t?.|\n}") }
            .items.none { it.label.startsWith("[]") }, "not after a safe call")
    }

    @Test
    fun aValueOfExtensionFunctionTypeIsOfferedOnItsReceiver() {
        val ls = labels("package w10\nfun test(i: Int, foo: Int.(String) -> Char, fooS: String.() -> Unit) {\n  i.fo|\n}")
        assertTrue("foo" in ls, "got $ls")
        assertFalse("fooS" in ls, "a String receiver is not an Int; got $ls")
    }

    @Test
    fun theLocalScopeMatchesAtAnyWordOfAName() {
        val ls = labels("package w11\nfun foo() {\n  ba|\n}\nfun fooBar() {}")
        assertTrue(ls.any { it.startsWith("fooBar") }, "got $ls")
    }

    @Test
    fun intellijsNameMatcherMatchesWordStartsAndTheFirstLettersCase() {
        fun m(p: String, n: String) = dev.ide.lang.kotlin.completion.KotlinNameMatcher(p).matches(n)
        assertTrue(m("ba", "fooBar") && m("S", "IllegalStateException") && m("i", "buildList"))
        assertTrue(m("fB", "fooBar") && m("old", "folder"))
        assertFalse(m("s", "IllegalStateException"), "the first letter's case must agree")
        assertFalse(m("Ch", "char"))
        assertTrue(dev.ide.lang.kotlin.completion.KotlinNameMatcher("foo").isStartMatch("fooBar"))
        assertFalse(dev.ide.lang.kotlin.completion.KotlinNameMatcher("bar").isStartMatch("fooBar"))
    }

    // --- typealias type names, annotation array literals, smart-cast receivers of function values ---

    @Test
    fun aProjectTypealiasIsOfferedAsATypeName() {
        val type = labels("package u1\nclass MyClass\ntypealias MyAlias = MyClass\nval x: MyA|")
        assertTrue("MyAlias" in type, "got $type")
        val annotation = labels("package u2\nannotation class Anno\ntypealias TypedAnno = Anno\n@Typed|\nfun f() {}")
        assertTrue("TypedAnno" in annotation, "an alias of an annotation class completes after `@`; got $annotation")
    }

    @Test
    fun anAnnotationsArrayLiteralOffersItsElements() {
        val ls = labels("package u3\nenum class MyEnum { FIRST, SECOND }\nannotation class Ann(val enums: Array<MyEnum>)\n@Ann(enums = [|])\nfun method() {}")
        assertTrue(ls.any { it.endsWith("FIRST") } && ls.any { it.endsWith("SECOND") }, "got ${ls.take(10)}")
    }

    @Test
    fun aSmartCastReceiverKeepsItsDeclaredTypeForFunctionValues() {
        val ls = labels("package u4\ninterface I\ninterface J\nfun test(p: I, fooI: I.() -> Unit, fooJ: J.() -> Unit) {\n  if (p is J) {\n    p.fo|\n  }\n}")
        assertTrue("fooI" in ls && "fooJ" in ls, "got $ls")
    }

    // Per test instance, not in a companion: an analyzer parked in a static is heap that every later test
    // class in the fork runs without.
    private val srcDir: Path = tempProject(mapOf("Seed.kt" to "package seed\nclass Seed"))
    private val analyzer by lazy { KotlinSourceAnalyzer(fakeContext(srcDir)) }
}

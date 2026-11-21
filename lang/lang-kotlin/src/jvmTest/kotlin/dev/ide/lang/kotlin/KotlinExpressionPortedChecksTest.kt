package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checks ported from K2's expression and syntax checkers and its call resolution: each error case is
 * reported with the code and on the text the compiler marks, and each look-alike the compiler accepts is clean.
 */
class KotlinExpressionPortedChecksTest {

    /** The text of every ERROR with [code] that analyzing [source] reports. */
    private fun errors(code: String, source: String): List<String> {
        val dir = tempProject(mapOf("Use.kt" to source))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        try {
            val doc = SnippetDoc(source, DiskFile(dir.resolve("Use.kt")))
            return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                .filter { it.severity == Severity.ERROR && it.code == code }
                .map { source.substring(it.range.start, it.range.end) }
        } finally {
            analyzer.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun literalsThatDenoteNoValue() {
        val got = errors(
            KotlinDiagnosticCodes.ILLEGAL_LITERAL,
            "package demo\nfun f() {\n  val a = 1l; val b = 0x1L; val c = 1_; val d = 1_000\n" +
                "  val e = 00; val g = 0; val h = 99999999999999999999; val i = 9223372036854775807\n" +
                "  val j = ''; val k = 'ab'; val l = '\\x'; val m = '\\n'; val n = '\\u0041'\n" +
                "  val o = \"a\\qb\\tc\"\n}\n",
        )
        assertEquals(listOf("l", "1_", "00", "99999999999999999999", "''", "'ab'", "'\\x'", "\\q"), got, "got $got")
    }

    @Test
    fun selectorsAndSuper() {
        val selectors = errors(KotlinDiagnosticCodes.ILLEGAL_SELECTOR, "package demo\nfun f(x: String) {\n  1.\"s\"; x.length; x.plus(\"\")\n}\n")
        assertEquals(listOf("\"s\""), selectors, "got $selectors")
        val supers = errors(
            KotlinDiagnosticCodes.SUPER_MISUSE,
            "package demo\nopen class B { open fun g() {} }\nclass C : B() {\n  override fun g() { super.g() }\n  fun h() { super }\n}\n" +
                "fun String.e() { super.compareTo(\"\") }\n",
        )
        assertEquals(listOf("super", "super"), supers, "got $supers")
    }

    @Test
    fun superCallsToAbstractOrAmbiguousMembers() {
        val got = errors(
            KotlinDiagnosticCodes.SUPER_MISUSE,
            "package demo\ninterface I { fun a() {}; fun m() }\ninterface J { fun a() {} }\nabstract class A { abstract fun f(); open fun g() {} }\n" +
                "class C : A(), I, J {\n  override fun f() { super.f() }\n  override fun g() { super.g() }\n" +
                "  override fun m() { super.m() }\n  override fun a() { super.a(); super<I>.a() }\n}\n",
        )
        assertEquals(listOf("f", "m", "super"), got, "got $got")
    }

    @Test
    fun underscoreNamesUsedWithoutBackticks() {
        val got = errors(
            KotlinDiagnosticCodes.UNDERSCORE_USAGE,
            "package demo\nfun `__`() {}\nfun <T> id(): T? = null\nfun f(`_`: Int) {\n  _ + 1; `_` + 1; __(); `__`()\n" +
                "  val n: Int? = id<_>()\n  listOf(1).forEach { _ -> }\n}\n",
        )
        assertEquals(listOf("_", "__"), got, "got $got")
    }

    @Test
    fun statementsWhereAnExpressionIsExpected() {
        val got = errors(
            KotlinDiagnosticCodes.EXPRESSION_EXPECTED,
            "package demo\nfun c() = true\nfun f() = while (c()) {}\nfun g() {\n  val x = for (i in 1..2) {}\n  while (c()) {}\n" +
                "  if (c()) while (c()) {}\n}\n",
        )
        assertEquals(listOf("while (c()) {}", "for (i in 1..2) {}"), got, "got $got")
    }

    @Test
    fun argumentsThatCannotBeMapped() {
        val got = errors(
            KotlinDiagnosticCodes.ARGUMENT_MAPPING,
            "package demo\nfun foo(a: Int, b: Int, c: Int = 0) {}\nfun v(vararg xs: Int) {}\nfun h(a: Int, f: () -> Unit) {}\n" +
                "fun f(arr: IntArray, fn: (Int) -> Unit) {\n  foo(b = 1, 2)\n  foo(1, b = 2, 3)\n  foo(a = 1, a = 2, b = 3)\n" +
                "  foo(*arr)\n  v(*arr)\n  v {}\n  h(1) {}\n  h(1) {} {}\n  fn(p1 = 1)\n  fn(1)\n}\n",
        )
        assertEquals(listOf("2", "a", "*", "{}", "{}", "p1"), got, "got $got")
    }

    @Test
    fun incompatibleEqualities() {
        val got = errors(
            KotlinDiagnosticCodes.INCOMPARABLE_EQUALITY,
            "package demo\nenum class E1 { A }\nenum class E2 { B }\ninterface I\nenum class E3 : I { C }\n" +
                "fun f(e1: E1, e2: E2, i: I, s: String, n: Int, e: E1?, o: E2?) {\n" +
                "  e1 == e2; e1 == E1.A; E1.A === E2.B; e1 == i; E3.C == i; s == n; e == o\n" +
                "  when (e1) { E2.B -> {}; E1.A -> {}; else -> {} }\n}\n",
        )
        assertEquals(listOf("e1 == e2", "E1.A === E2.B", "e1 == i", "s == n", "E2.B"), got, "got $got")
    }

    @Test
    fun erasedAndBareTypeChecks() {
        val got = errors(
            KotlinDiagnosticCodes.CAST_ERASED,
            "package demo\nclass G<T>\ninline fun <reified R> r(x: Any) = x is R\n" +
                "fun <T> f(a: Any, l: List<Int>, s: String) {\n  a is List<String>; a is List<*>; a is T; a is G; a as G<Int>\n" +
                "  l is ArrayList; l is ArrayList<Int>; a is List<Any?>; s is CharSequence\n}\n",
        )
        assertEquals(listOf("List<String>", "T", "G"), got, "got $got")
    }

    @Test
    fun errorLevelDeprecations() {
        val got = errors(
            KotlinDiagnosticCodes.DEPRECATION,
            "package demo\n@Deprecated(\"x\", level = DeprecationLevel.ERROR) fun old() {}\n@Deprecated(\"x\") fun soft() {}\n" +
                "@Deprecated(\"x\", ReplaceWith(\"\"), DeprecationLevel.HIDDEN) class H\n" +
                "fun f(h: H) {\n  old(); soft()\n}\n",
        )
        assertEquals(listOf("H", "old"), got.sorted(), "got $got")
    }

    @Test
    fun conventionCallsOfFunctionsWithoutOperator() {
        val got = errors(
            KotlinDiagnosticCodes.OPERATOR_MODIFIER_REQUIRED,
            "package demo\nclass A {\n  fun plus(o: A) = o\n  operator fun minus(o: A) = o\n  fun not() = false\n  fun inc() = this\n" +
                "  fun contains(o: A) = false\n}\nabstract class B { abstract operator fun times(o: B): B }\nclass C : B() { override fun times(o: B) = o }\n" +
                "fun f(a: A, c: C) {\n  var x = a\n  a + a; a - a; !a; x++; a in a; c * c; a.plus(a)\n}\n",
        )
        assertEquals(listOf("+", "!", "++", "in"), got, "got $got")
    }

    @Test
    fun classLiteralsThatNameNoClass() {
        val got = errors(
            KotlinDiagnosticCodes.CLASS_LITERAL,
            "package demo\nclass A<T> { class N }\ninline fun <reified R> r() = R::class\n" +
                "fun <T> f() {\n  A?::class; A<String>::class; A::class; A.N::class; T::class; Array<Int>::class\n" +
                "  arrayOf<T>(); arrayOf<Int>(); r<String>()\n}\n",
        )
        assertEquals(listOf("A?::class", "A<String>::class", "T::class", "T"), got, "got $got")
    }

    @Test
    fun lambdaParametersCalledWhereTheyAreNotInlined() {
        val got = errors(
            KotlinDiagnosticCodes.NON_LOCAL_RETURN_NOT_ALLOWED,
            "package demo\ninline fun f(p: () -> Unit, crossinline c: () -> Unit, noinline n: () -> Unit) {\n" +
                "  p(); run { p() }\n  val o = object { fun a() { p(); c(); n() } }\n  fun local() { p() }\n  { p() }()\n}\n",
        )
        assertEquals(listOf("p", "p", "p"), got, "got $got")
    }

    @Test
    fun nonBooleanConditionsOfLogicOperatorsAndSubjectlessWhen() {
        val got = errors(
            KotlinDiagnosticCodes.TYPE_MISMATCH,
            "package demo\nfun f(b: Boolean, n: Int) {\n  if (b && n) {}\n  val x = b || true\n  when { 4 -> {}; b -> {}; else -> {} }\n  when (n) { 4 -> {}; else -> {} }\n}\n",
        )
        assertEquals(listOf("n", "4"), got, "got $got")
    }

    @Test
    fun labeledReceiversThatNameNothing() {
        val got = errors(
            KotlinDiagnosticCodes.UNRESOLVED_LABEL,
            "package demo\nopen class P { open fun p() {} }\nclass A : P() {\n  fun m() = this@A\n  fun Int.e() = this@e\n  fun f() = this@f\n" +
                "  fun g() = run { this@run }\n  fun h() = listOf(1).map L@{ this@L }\n  override fun p() { super@A.p(); super@Q.p() }\n" +
                "  companion object { fun c() = this@Companion }\n}\nfun top() { this@top }\n",
        )
        assertEquals(listOf("@f", "@Q", "@top"), got, "got $got")
    }

    @Test
    fun starImportFromAnObject() {
        val dir = tempProject(mapOf("O.kt" to "package lib\nobject O { val x = 1 }\nclass K { companion object { val y = 2 } }\n"))
        val source = "package demo\nimport lib.O.*\nimport lib.*\nfun f() = 1\n"
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        val got = try {
            val doc = SnippetDoc(source, DiskFile(dir.resolve("Use.kt")))
            runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                .filter { it.code == KotlinDiagnosticCodes.IMPORT_FROM_SINGLETON }
                .map { source.substring(it.range.start, it.range.end) }
        } finally {
            analyzer.dispose()
            dir.toFile().deleteRecursively()
        }
        assertEquals(listOf("O"), got, "got $got")
    }
}

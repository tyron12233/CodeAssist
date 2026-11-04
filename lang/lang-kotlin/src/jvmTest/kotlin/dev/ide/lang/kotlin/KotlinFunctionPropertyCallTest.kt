package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bare call on a function-typed VALUE (`log("x")` where `log: (String) -> Unit` is a property, a parameter or
 * a local) binds to that value's `invoke`, ahead of any top-level function of the same name. The editor only
 * collected functions for a bare call, so the reported class called `kotlin.math.log(Double, Double)` and
 * flagged "inferred type is String but Double was expected" on a valid line, imported or not.
 *
 * The same line also hinted `trimEnd('\r')`'s argument as `predicate:`: a `Char` was never rejected for a
 * `(Char) -> Boolean` parameter, so the lambda overload won over `trimEnd(vararg chars: Char)`.
 */
class KotlinFunctionPropertyCallTest {

    private val reported = """
        class TestClass(private val log: (String) -> Unit) {
            fun testFun() {
                var n: Int = 0
                val pending: StringBuilder = StringBuilder()
                log(pending.substring(0, n).trimEnd('\r'))
            }
        }
    """.trimIndent()

    @Test fun reportedIsClean() { clean(reported) }

    @Test fun reportedWithMathImportIsClean() { clean("import kotlin.math.*\n$reported") }

    @Test
    fun everyKindOfNearerValueShadowsTheTopLevelFunction() {
        val cases = listOf(
            "import kotlin.math.log\nclass C { val log: (String) -> Unit = {}; fun f() { log(\"x\") } }",
            "import kotlin.math.log\nfun f(log: (String) -> Unit) { log(\"x\") }",
            "import kotlin.math.log\nfun f() { val log: (String) -> Unit = {}; log(\"x\") }",
            "import kotlin.math.log\nfun f() { val log = { s: String -> s.length }; val n: Int = log(\"x\") }",
            "import kotlin.math.log\nfun f(log: String.(Int) -> Unit) { log(\"x\", 1) }",
        )
        for (c in cases) clean(c)
    }

    @Test
    fun theCallIsTypedByTheValuesInvoke() {
        val hs = hints("import kotlin.math.log\nclass C(private val log: (String) -> Int) { fun f() { val r = log(\"x\") } }")
        assertTrue(": Int" in hs, "log(\"x\") must type as the property's Int, not kotlin.math.log's Double; got $hs")
    }

    @Test
    fun aWrongArgumentToTheValueIsStillReported() {
        val errors = errors("class C(private val log: (String) -> Unit) { fun f() { log(1) } }")
        assertEquals(1, errors.size, "log(1) on a (String) -> Unit must be one mismatch; got $errors")
        assertTrue("String" in errors.single().message, "the mismatch is against the property's String; got $errors")
    }

    @Test
    fun anUnshadowedTopLevelFunctionStillResolves() {
        clean("import kotlin.math.log\nfun f(): Double = log(8.0, 2.0)")
        // A same-named member FUNCTION beside the property wins in that scope, so this binds to it, not the
        // property (and not kotlin.math.log).
        clean("class C { val log: (String) -> Unit = {}; fun log(n: Int) {}; fun f() { log(1) } }")
    }

    @Test
    fun aCharArgumentPicksTheVarargOverload() {
        val hs = hints("fun f(s: String) = s.trimEnd('\\r')")
        assertTrue(hs.none { it.startsWith("predicate") }, "a Char is not a (Char) -> Boolean; got $hs")
    }

    @Test
    fun functionTypedArgumentsStillApply() {
        val cases = listOf(
            "fun f(s: String) = s.trimEnd { it == 'a' }",
            "fun g(x: Int) = x\nfun f() = listOf(1).map(::g)",
            "fun take(f: (Int) -> Unit) {}\nfun t(h: (Int) -> Unit) = take(h)",
        )
        for (c in cases) clean(c)
    }

    /** A class that implements a function type IS one. Its supertype text (`(Int) -> Unit`, or a bare
     *  `Function1<…>`) never resolved to `kotlin.FunctionN`, so passing an instance read as a mismatch. */
    @Test
    fun aClassImplementingAFunctionTypeIsOne() {
        val cases = listOf(
            "class F : (Int) -> Unit { override fun invoke(p: Int) {} }\nfun take(f: (Int) -> Unit) {}\nfun t() = take(F())",
            "class F : Function1<Int, Unit> { override fun invoke(p: Int) {} }\nfun take(f: (Int) -> Unit) {}\nfun t() = take(F())",
            "class F : String.() -> Int { override fun invoke(p: String) = 1 }\nfun take(f: String.() -> Int) {}\nfun t() = take(F())",
            "open class Base : (Int) -> Unit { override fun invoke(p: Int) {} }\nclass F : Base()\nfun take(f: (Int) -> Unit) {}\nfun t() = take(F())",
            "class F : (Int) -> Unit { override fun invoke(p: Int) {} }\nfun t() { val f: (Int) -> Unit = F() }",
            "class F : (Int) -> Unit { override fun invoke(p: Int) {} }\nfun t() { F()(1) }",
            "fun take(f: Function1<Int, Unit>) {}\nfun t() = take { }",
        )
        for (c in cases) clean(c)
    }

    @Test
    fun aClassNotImplementingTheFunctionTypeIsStillRejected() {
        val errors = errors("class F\nfun t() { val f: (Int) -> Unit = F() }")
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors.map { it.code }, "F is not a function; got $errors")
        val arg = errors("class F\nfun take(f: (Int) -> Unit) {}\nfun t() = take(F())")
        assertEquals(listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), arg.map { it.code }, "F is not a function argument; got $arg")
        val unknown = errors("class F : Function1x<Int, Unit>")
        assertEquals(listOf(KotlinDiagnosticCodes.UNRESOLVED), unknown.map { it.code }, "a non-FunctionN name still resolves strictly; got $unknown")
    }

    private fun clean(code: String) {
        val errors = errors(code)
        assertTrue(errors.isEmpty(), "expected `$code` clean, got $errors")
    }

    private fun errors(code: String): List<Diagnostic> = runBlocking {
        val (a, doc) = open(code)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    private fun hints(code: String): List<String> = runBlocking {
        val (a, doc, text) = open(code)
        a.inlayHints!!.hints(doc.file, TextRange(0, text.length)).map { h -> h.parts.joinToString("") { it.text } }
    }

    private fun open(code: String): Triple<KotlinSourceAnalyzer, SnippetDoc, String> {
        val text = "package demo\n$code\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath())))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        return Triple(a, doc, text)
    }
}

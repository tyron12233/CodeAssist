package dev.ide.lang.kotlin

import dev.ide.lang.highlight.HighlightModifier
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Semantic highlighting: the Kotlin distinctions the user asked for — `@Composable` and `suspend` functions,
 * extension functions/properties, `var` (mutable) vs `val` (read-only) — plus the baseline kinds. Conservative
 * (only confidently-resolved names), so each assertion checks the token IS classified, not that nothing else is.
 */
class KotlinSemanticHighlightTest {

    private data class Tok(val text: String, val kind: String, val mods: Set<HighlightModifier>)

    private fun tokens(file: String, code: String): List<Tok> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(file)))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.semanticHighlighter!!.highlight(doc.file) }
            .map { Tok(code.substring(it.range.start, it.range.end), it.kind.id, it.modifiers) }
    }

    @Test
    fun composableCallIsMarked() {
        val code = "@Composable fun Greeting() {}\n" +
            "@Composable fun App() { Greeting() }\n"
        val toks = tokens("Comp.kt", code)
        assertTrue(
            toks.any { it.text == "Greeting" && it.kind == "method" && HighlightModifier.COMPOSABLE in it.mods },
            "composable call should be marked; got $toks",
        )
    }

    @Test
    fun extensionCallIsMarked() {
        val code = "fun String.shout(): String = this\n" +
            "fun f() { \"x\".shout() }\n"
        val toks = tokens("Ext.kt", code)
        assertTrue(
            toks.any { it.text == "shout" && HighlightModifier.EXTENSION in it.mods },
            "extension call should be marked; got $toks",
        )
    }

    @Test
    fun suspendFunctionDeclarationIsMarked() {
        val toks = tokens("Susp.kt", "suspend fun load() {}\n")
        assertTrue(
            toks.any { it.text == "load" && it.kind == "function" && HighlightModifier.SUSPEND in it.mods },
            "suspend fun decl should be marked; got $toks",
        )
    }

    @Test
    fun mutableVarVsReadonlyVal() {
        val code = "fun f() {\n  var a = 1\n  val b = 2\n  a = a + b\n}\n"
        val toks = tokens("Var.kt", code)
        assertTrue(
            toks.any { it.text == "a" && it.kind == "localVariable" && HighlightModifier.MUTABLE in it.mods },
            "var reference should be mutable; got $toks",
        )
        assertTrue(
            toks.any { it.text == "b" && it.kind == "localVariable" && HighlightModifier.READONLY in it.mods },
            "val reference should be read-only; got $toks",
        )
    }

    @Test
    fun stdlibInfixCallIsMarked() {
        val toks = tokens("Infix.kt", "package demo\nfun f() { val p = 1 to 2 }\n")
        assertTrue(
            toks.any { it.text == "to" && it.kind == "method" },
            "the stdlib infix `to` should be colored as a method; got $toks",
        )
    }

    @Test
    fun userInfixCallIsMarked() {
        // `combine` is a user infix extension declared in another file (on disk → in the model).
        val toks = tokens("UseInfix.kt", "package demo\nfun f(a: Money, b: Money) { val c = a combine b }")
        assertTrue(
            toks.any { it.text == "combine" && it.kind == "method" && HighlightModifier.EXTENSION in it.mods },
            "a user infix call should be colored as a method; got $toks",
        )
    }

    @Test
    fun namedArgumentLabelIsMarkedAsParameter() {
        val code = "fun greet(name: String, loud: Boolean) {}\n" +
            "fun f() { greet(name = \"x\", loud = true) }\n"
        val toks = tokens("Named.kt", code)
        assertTrue(
            toks.count { it.text == "name" && it.kind == "parameter" } >= 1 &&
                toks.any { it.text == "loud" && it.kind == "parameter" },
            "named-argument labels should be colored as parameters; got $toks",
        )
    }

    @Test
    fun instancePropertyReadIsColored() {
        val toks = tokens("Prop.kt", "package demo\nfun f(p: Point) { val a = p.x }")
        assertTrue(
            toks.any { it.text == "x" && it.kind == "property" },
            "an instance property read should be colored as a property; got $toks",
        )
    }

    @Test
    fun enumConstantReadIsColored() {
        val toks = tokens("Enum.kt", "package demo\nfun f() { val h = Hue.RED }")
        assertTrue(
            toks.any { it.text == "RED" && it.kind == "enumConstant" },
            "an enum constant read should be colored as enumConstant; got $toks",
        )
    }

    @Test
    fun annotationUsageIsColored() {
        val toks = tokens("Anno.kt", "package demo\n@Composable fun A() {}\n")
        assertTrue(
            toks.any { it.text == "Composable" && it.kind == "annotation" },
            "an annotation usage should be colored as annotation; got $toks",
        )
    }

    @Test
    fun deprecatedDeclarationAndCallAreMarked() {
        val code = "package demo\n@Deprecated(\"x\") fun old() {}\nfun f() { old(); oldApi() }\n"
        val toks = tokens("Dep.kt", code)
        assertTrue(
            toks.any { it.text == "old" && HighlightModifier.DEPRECATED in it.mods && HighlightModifier.DECLARATION in it.mods },
            "a @Deprecated declaration should be struck through; got $toks",
        )
        assertTrue(
            toks.any { it.text == "oldApi" && it.kind == "method" && HighlightModifier.DEPRECATED in it.mods },
            "a call to a cross-file @Deprecated function should be struck through; got $toks",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf(
            "Seed.kt" to "package demo\n",
            "Money.kt" to "package demo\nclass Money\ninfix fun Money.combine(o: Money): Money = o",
            "Models.kt" to "package demo\n" +
                "data class Point(val x: Int, val y: Int)\n" +
                "enum class Hue { RED, GREEN }\n" +
                "@Deprecated(\"old\") fun oldApi() {}\n",
        ))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

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
    fun implicitItInScopeLambdaIsColoredParameter() {
        // `it` is synthetic (no KtParameter) but must color like a parameter inside let/also.
        val let = tokens("It.kt", "package demo\nfun f() { \"hello\".let { it.length } }")
        assertTrue(let.any { it.text == "it" && it.kind == "parameter" }, "implicit `it` should be a parameter in let; got $let")
        val also = tokens("It.kt", "package demo\nfun f(p: Point) { p.also { it.x } }")
        assertTrue(also.any { it.text == "it" && it.kind == "parameter" }, "implicit `it` should be a parameter in also; got $also")
    }

    @Test
    fun bareMemberViaImplicitReceiverIsColored() {
        // The `this` of apply/with/run is an implicit receiver — a bare member read off it colors like `p.x`.
        val apply = tokens("Recv.kt", "package demo\nfun f(p: Point) { p.apply { x } }")
        assertTrue(apply.any { it.text == "x" && it.kind == "property" }, "bare `x` via apply receiver should be a property; got $apply")
        val with = tokens("Recv.kt", "package demo\nfun f(p: Point) { with(p) { y } }")
        assertTrue(with.any { it.text == "y" && it.kind == "property" }, "bare `y` via with receiver should be a property; got $with")
    }

    @Test
    fun unresolvedBareNameInScopeLambdaIsNotColored() {
        // Conservative: a name that resolves to nothing on the receiver is left to the lexical layer.
        val toks = tokens("Recv.kt", "package demo\nfun f(p: Point) { p.apply { bogusXyz } }")
        assertTrue(toks.none { it.text == "bogusXyz" }, "an unresolved bare name must not be colored; got $toks")
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

    @Test
    fun stringTemplateVariableIsColored() {
        // The core ask: a variable interpolated into a string colors like the variable it is (not string green).
        val short = tokens("Tpl.kt", "fun f() {\n  val name = \"x\"\n  println(\"hi \$name\")\n}\n")
        assertTrue(
            short.count { it.text == "name" && it.kind == "localVariable" } >= 2,
            "the interpolated `\$name` should color as a localVariable; got $short",
        )
        val block = tokens("Tpl.kt", "package demo\nfun f(p: Point) { println(\"x=\${p.x}\") }")
        assertTrue(
            block.any { it.text == "p" && it.kind == "parameter" } && block.any { it.text == "x" && it.kind == "property" },
            "a `\${p.x}` member read should color the receiver + property; got $block",
        )
    }

    @Test
    fun stringTemplateDelimitersAreColored() {
        val toks = tokens("Tpl.kt", "package demo\nfun f(p: Point) { println(\"a\$p b\${p.x}c\") }")
        assertTrue(toks.any { it.text == "\$" && it.kind == "stringTemplateEntry" }, "the `\$` of `\$p` should be colored; got $toks")
        assertTrue(toks.any { it.text == "\${" && it.kind == "stringTemplateEntry" }, "the `\${` should be colored; got $toks")
        assertTrue(toks.any { it.text == "}" && it.kind == "stringTemplateEntry" }, "the closing `}` should be colored; got $toks")
    }

    @Test
    fun escapeSequenceIsColored() {
        val toks = tokens("Esc.kt", "fun f() { val s = \"tab\\there\\n\" }")
        assertTrue(toks.any { it.text == "\\t" && it.kind == "stringEscape" }, "`\\t` should color as stringEscape; got $toks")
        assertTrue(toks.any { it.text == "\\n" && it.kind == "stringEscape" }, "`\\n` should color as stringEscape; got $toks")
    }

    @Test
    fun destructuringDeclarationAndUseAreColored() {
        val toks = tokens("Destr.kt", "package demo\nfun f(p: Point) {\n  val (a, b) = p\n  println(a + b)\n}\n")
        // Both entries are declared read-only locals, and the later `a`/`b` reads resolve back to them.
        assertTrue(
            toks.count { it.text == "a" && it.kind == "localVariable" && HighlightModifier.DECLARATION in it.mods } == 1 &&
                toks.count { it.text == "a" && it.kind == "localVariable" && HighlightModifier.DECLARATION !in it.mods } >= 1,
            "the destructured `a` should be a localVariable at both its declaration and use; got $toks",
        )
        assertTrue(
            toks.any { it.text == "b" && it.kind == "localVariable" && HighlightModifier.READONLY in it.mods },
            "the destructured `b` should be read-only; got $toks",
        )
    }

    @Test
    fun forLoopDestructuringIsColored() {
        val toks = tokens("ForD.kt", "package demo\nfun f(m: Map<String, Int>) {\n  for ((k, v) in m) { println(k); println(v) }\n}\n")
        assertTrue(
            toks.count { it.text == "k" && it.kind == "localVariable" } >= 2 &&
                toks.count { it.text == "v" && it.kind == "localVariable" } >= 2,
            "for-loop destructuring `(k, v)` should color both the declaration and the uses; got $toks",
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

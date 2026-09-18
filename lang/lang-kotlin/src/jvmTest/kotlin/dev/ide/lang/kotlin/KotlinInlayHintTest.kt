package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHintKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inlay hints: inferred local-variable types, lambda parameter / implicit-`it` types, and call-site
 *  parameter names. */
class KotlinInlayHintTest {

    private fun hints(file: String, code: String): List<Pair<String, InlayHintKind>> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(file)))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.inlayHints!!.hints(doc.file, TextRange(0, code.length)) }
            .map { it.parts.joinToString("") { p -> p.text } to it.kind }
    }

    @Test
    fun inferredLocalTypeHint() {
        // A member-call result and a generic call both infer their declared type.
        assertTrue(
            hints("Use.kt", "fun f() { val s = \"hi\".uppercase() }").any { it.first == ": String" && it.second == InlayHintKind.TYPE },
            "val s should hint ': String'",
        )
        assertTrue(
            hints("Use.kt", "fun f() { val xs = listOf(\"a\") }").any { it.first == ": List<String>" },
            "val xs should hint ': List<String>'",
        )
    }

    @Test
    fun explicitlyTypedLocalGetsNoHint() {
        assertTrue(hints("Use.kt", "fun f() { val n: Int = 1 }").isEmpty(), "an explicit type needs no hint")
    }

    @Test
    fun lambdaImplicitItTypeHint() {
        // `"".let { it.length }` → `it: String`.
        val hs = hints("Use.kt", "fun f() { \"\".let { it.length } }")
        assertTrue(hs.any { it.first.startsWith("it: String") }, "implicit it should hint String; got $hs")
    }

    @Test
    fun lambdaExplicitParamTypeHint() {
        // `listOf("").forEach { s -> … }` → `s: String`.
        val hs = hints("Use.kt", "fun f() { listOf(\"\").forEach { s -> s.length } }")
        assertTrue(hs.any { it.first == ": String" }, "explicit lambda param should hint ': String'; got $hs")
    }

    @Test
    fun receiverLambdaScopeHint() {
        // A receiver-typed lambda parameter (`Foo.() -> Unit`) → `this: Foo` at the brace.
        val hs = hints(
            "Use.kt",
            "class Foo { fun bar() {} }\nfun build(block: Foo.() -> Unit) {}\nfun f() { build { bar() } }",
        )
        assertTrue(hs.any { it.first == "this: Foo" && it.second == InlayHintKind.TYPE }, "receiver lambda should hint 'this: Foo'; got $hs")
    }

    @Test
    fun withBlockScopeHint() {
        // `with(x) { … }` binds the receiver from its argument → `this: StringBuilder`.
        val hs = hints("Use.kt", "fun f() { with(StringBuilder()) { append(\"x\") } }")
        assertTrue(hs.any { it.first == "this: StringBuilder" }, "with-block should hint 'this: StringBuilder'; got $hs")
    }

    @Test
    fun plainLambdaHasNoScopeHint() {
        // `forEach`'s lambda is a plain `(T) -> Unit`, not a receiver type — no `this:` hint.
        val hs = hints("Use.kt", "fun f() { listOf(\"\").forEach { s -> s.length } }")
        assertTrue(hs.none { it.first.startsWith("this:") }, "plain lambda should have no 'this:' hint; got $hs")
    }

    // --- call-site parameter names ------------------------------------------------------------------

    private val decl = "fun setPadding(left: Int, top: Int) {}\n"

    @Test
    fun parameterNameHintForLiteralArguments() {
        val hs = hints("Use.kt", decl + "fun f() { setPadding(0, 8) }")
        assertEquals(
            listOf("left:" to InlayHintKind.PARAMETER, "top:" to InlayHintKind.PARAMETER),
            hs.filter { it.second == InlayHintKind.PARAMETER },
            "both literal arguments should be named; got $hs",
        )
    }

    @Test
    fun noParameterNameHintForANamedVariable() {
        // `n` already documents itself, so only the bare `8` is opaque.
        val hs = hints("Use.kt", decl + "fun f() { val n = 1\n setPadding(n, 8) }")
        assertEquals(listOf("top:"), hs.filter { it.second == InlayHintKind.PARAMETER }.map { it.first }, "got $hs")
    }

    @Test
    fun noParameterNameHintWhenTheArgumentIsAlreadyNamed() {
        val hs = hints("Use.kt", decl + "fun f() { setPadding(left = 0, top = 8) }")
        assertTrue(hs.none { it.second == InlayHintKind.PARAMETER }, "a named argument needs no hint; got $hs")
    }

    @Test
    fun parameterNameHintForAConstructorCall() {
        val hs = hints("Use.kt", "class Point(val x: Int, val y: Int)\nfun f() { Point(1, 2) }")
        assertEquals(
            listOf("x:", "y:"),
            hs.filter { it.second == InlayHintKind.PARAMETER }.map { it.first },
            "constructor arguments should be named too; got $hs",
        )
    }

    /** `p0`/`p1` is what a binary Java callee falls back to when neither `MethodParameters` nor attached
     *  sources can name it (`p0: 8` teaches nothing), so no hint is better than a synthetic one. */
    @Test
    fun noParameterNameHintForSyntheticNames() {
        val hs = hints("Use.kt", "fun f() { \"abc\".substring(1) }")
        assertTrue(
            hs.filter { it.second == InlayHintKind.PARAMETER }.none { it.first.startsWith("p") },
            "a synthetic pN name must not be rendered; got $hs",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

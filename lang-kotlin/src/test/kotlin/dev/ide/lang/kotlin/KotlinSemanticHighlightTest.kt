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

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

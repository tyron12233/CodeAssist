package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A positional argument past a `vararg` parameter fills the vararg, never the parameter declared after it.
 * `remember(a, b, c, d) { … }` picks Compose's `remember(vararg keys, calculation)` overload; binding `b` against
 * `calculation: () -> T` would pin `T` to `b`'s type argument and type the call as `String` instead of the
 * lambda's result.
 */
class KotlinVarargCallInferenceTest {

    private fun unresolved(fileName: String, body: String): List<Diagnostic> {
        val code = "package demo\n" +
            "import androidx.compose.runtime.remember\n" +
            "data class Layout(val edges: List<String>, val rootNodes: List<String>)\n" +
            "fun f(a: List<String>, b: List<String>, c: List<String>, flag: Boolean) {\n$body\n}"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        val diags = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        return diags.filter { it.code == "kt.unresolved" }
    }

    @Test
    fun rememberWithFourKeysTypesAsLambdaResult() {
        val diags = unresolved(
            "FourKeys.kt",
            "val l = remember(a, b, c, flag) { Layout(c, if (flag) b else emptyList()) }\n" +
                "l.rootNodes\nl.edges.forEach { println(it) }",
        )
        assertTrue(diags.isEmpty(), "the vararg overload must type as the lambda's result; got $diags")
    }

    @Test
    fun rememberWithFixedKeysStillResolves() {
        val diags = unresolved("ThreeKeys.kt", "val l = remember(a, b, c) { Layout(c, b) }\nl.rootNodes")
        assertTrue(diags.isEmpty(), "the three-key overload must type as the lambda's result; got $diags")
    }

    @Test
    fun plainVarargThenLambdaTypesAsLambdaResult() {
        val diags = unresolved(
            "PlainVararg.kt",
            "val l = androidx.compose.runtime.keyed(a, b, c) { Layout(c, b) }\nl.rootNodes",
        )
        assertTrue(diags.isEmpty(), "a vararg-then-lambda call must type as the lambda's result; got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                // Mirrors the Compose runtime's `remember` overload set: 0..3 fixed keys, then a vararg.
                "Compose.kt" to "package androidx.compose.runtime\n" +
                    "inline fun <T> remember(crossinline calculation: () -> T): T = calculation()\n" +
                    "inline fun <T> remember(key1: Any?, crossinline calculation: () -> T): T = calculation()\n" +
                    "inline fun <T> remember(key1: Any?, key2: Any?, crossinline calculation: () -> T): T = calculation()\n" +
                    "inline fun <T> remember(key1: Any?, key2: Any?, key3: Any?, crossinline calculation: () -> T): T = calculation()\n" +
                    "inline fun <T> remember(vararg keys: Any?, crossinline calculation: () -> T): T = calculation()\n" +
                    "fun <T> keyed(vararg keys: Any?, block: () -> T): T = block()",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

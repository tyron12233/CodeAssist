package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHintKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Inlay hints: inferred local-variable types, and lambda parameter / implicit-`it` types. */
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

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

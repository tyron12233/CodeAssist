package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A library `private`/`internal` callable must never leak into the editor — the kotlin-stdlib ships a
 * `private fun String.getRootLength()`, which user code cannot access ("Cannot access ...: it is private in
 * file"). It must not be offered in completion, and using it must be flagged. A public stdlib extension
 * (`removePrefix`) is the positive control: it stays available.
 */
class KotlinLibraryPrivateVisibilityTest {
    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun privateStdlibExtensionNotCompleted() {
        val items = labels("package demo\nfun f() { \"\".getRootL| }")
        assertTrue("getRootLength" !in items, "the stdlib's private String.getRootLength must NOT complete; got $items")
    }

    @Test
    fun publicStdlibExtensionStillCompletes() {
        val items = labels("package demo\nfun f() { \"\".removePref| }")
        assertTrue("removePrefix" in items, "a public stdlib extension must still complete; got $items")
    }

    @Test
    fun usingPrivateStdlibExtensionIsFlagged() {
        val diags = diagnose("package demo\nfun f() { val t = \"\"\n  t.getRootLength() }")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && "getRootLength" in it.message },
            "accessing the stdlib's private getRootLength should be flagged unresolved; got $diags",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Use.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

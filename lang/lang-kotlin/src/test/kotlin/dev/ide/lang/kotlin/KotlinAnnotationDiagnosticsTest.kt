package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unresolved-reference diagnostics for Kotlin annotations. An unknown / unimported annotation type is flagged
 * (`@Foo` with no such symbol) — it was previously skipped, so a typo'd or un-imported annotation went
 * unreported — while a valid annotation (builtin, imported, same-package, use-site target) is left alone, and
 * an unresolved reference inside an annotation's arguments is still flagged.
 */
class KotlinAnnotationDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(fileName: String, code: String): List<Diagnostic> =
        diagnose(fileName, code).filter { it.code == KotlinDiagnosticCodes.UNRESOLVED }

    @Test
    fun unknownAnnotationTypeOnClassIsUnresolved() {
        val d = unresolved("A1.kt", "package demo\n@Foo\nclass Use")
        assertTrue(d.any { it.message.contains("Foo") }, "unknown annotation @Foo should be unresolved; got $d")
    }

    @Test
    fun unknownAnnotationWithArgsIsUnresolved() {
        val d = unresolved("A2.kt", "package demo\n@Foo(1)\nclass Use")
        assertTrue(d.any { it.message.contains("Foo") }, "unknown annotation @Foo(1) should be unresolved; got $d")
    }

    @Test
    fun unknownUseSiteAnnotationIsUnresolved() {
        val d = unresolved("A3.kt", "package demo\nclass Use { @get:Foo val x = 1 }")
        assertTrue(d.any { it.message.contains("Foo") }, "unknown use-site @get:Foo should be unresolved; got $d")
    }

    @Test
    fun unresolvedAnnotationArgumentIsFlagged() {
        val d = unresolved("A4.kt", "package demo\nannotation class Ann(val v: Int)\n@Ann(NOPE)\nclass Use")
        assertTrue(d.any { it.message.contains("NOPE") }, "unresolved annotation arg should be flagged; got $d")
    }

    @Test
    fun builtinAnnotationIsNotFlagged() {
        val d = unresolved("V1.kt", "package demo\n@Deprecated(\"x\")\nclass Use")
        assertTrue(d.none { it.message.contains("Deprecated") }, "builtin @Deprecated must not be flagged; got $d")
    }

    @Test
    fun importedAnnotationIsNotFlagged() {
        val d = unresolved("V2.kt", "package demo\nimport lib.Marker\n@Marker\nclass Use")
        assertTrue(d.none { it.message.contains("Marker") }, "imported @Marker must not be flagged; got $d")
    }

    @Test
    fun samePackageAnnotationIsNotFlagged() {
        val d = unresolved("V3.kt", "package lib\n@Marker\nclass Use2")
        assertTrue(d.none { it.message.contains("Marker") }, "same-package @Marker must not be flagged; got $d")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Marker.kt" to "package lib\nannotation class Marker"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

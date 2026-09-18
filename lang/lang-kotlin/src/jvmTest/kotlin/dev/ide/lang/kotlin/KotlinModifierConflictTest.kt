package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `kt.modifiers` — repeated and incompatible modifiers, and the annotations that are NOT either.
 *
 * The check keyed on `et is KtModifierKeywordToken`, which reads like a type test and is not one:
 * `KtModifierKeywordToken` is a typealias for `SyntaxElementType` in the vendored vocabulary, so it was true
 * of every child of the modifier list. Annotations are children of the modifier list. Keyed by element type,
 * every annotation after the first counted as a repeat of the one before, so ordinary code like
 * `@Composable @Preview fun` was underlined with "Repeated '@Preview' modifier".
 *
 * Found by sweeping the checkers over the Kotlin standard library's own sources, where it fired 7,713 times.
 * It is the same defect class as `isRepresented(psi) = psi is KtElement` in the parser port: a type test that
 * survives a port unchanged can still mean something completely different on the other side.
 */
class KotlinModifierConflictTest {

    private fun modifierDiagnostics(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == KotlinDiagnosticCodes.MODIFIERS }
    }

    @Test
    fun severalDifferentAnnotationsOnOneDeclarationAreNotRepeats() {
        val diags = modifierDiagnostics(
            "ManyAnnotations.kt",
            "package demo\n@Deprecated(\"x\")\n@JvmName(\"y\")\n@Suppress(\"z\")\nfun a() {}\n",
        )
        assertTrue(diags.isEmpty(), "three different annotations are not repeats; got ${diags.map { it.message }}")
    }

    @Test
    fun annotationsMixedWithRealModifiersAreNotRepeats() {
        val diags = modifierDiagnostics(
            "Mixed.kt",
            "package demo\nabstract class C {\n    @Deprecated(\"x\")\n    @Suppress(\"y\")\n    protected abstract fun a()\n}\n",
        )
        assertTrue(diags.isEmpty(), "got ${diags.map { it.message }}")
    }

    @Test
    fun aGenuinelyRepeatedAnnotationIsStillFlagged() {
        val diags = modifierDiagnostics(
            "RepeatedAnnotation.kt",
            "package demo\n@Deprecated(\"x\")\n@Deprecated(\"x\")\nfun a() {}\n",
        )
        val d = assertNotNull(diags.firstOrNull(), "the same annotation twice IS a repeat")
        assertTrue(d.message.contains("Deprecated"), d.message)
    }

    @Test
    fun aRepeatedKeywordModifierIsFlagged() {
        val diags = modifierDiagnostics("RepeatedKeyword.kt", "package demo\nopen open class C\n")
        val d = assertNotNull(diags.firstOrNull(), "`open open` is a repeat")
        assertEquals("Repeated 'open' modifier", d.message)
    }

    @Test
    fun incompatibleModifiersAreStillFlagged() {
        val diags = modifierDiagnostics("Incompatible.kt", "package demo\nprivate public fun a() {}\n")
        assertTrue(diags.isNotEmpty(), "two visibility modifiers conflict")
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

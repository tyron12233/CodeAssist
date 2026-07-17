package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completion of annotation arguments for an arbitrary annotation (resolved from its type, not hardcoded): the
 * annotation's parameter names are offered inside `@Foo(…)` and ranked first, and an already-supplied
 * parameter isn't re-offered. Also: at an annotation NAME (`@Comp…`) only annotation classes are offered.
 */
class KotlinAnnotationCompletionTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }

    private fun names(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun offersAnnotationParameterNames() {
        val ls = labels("package demo\n@Cfg(co|)\nfun f() {}")
        assertTrue("count =" in ls, "expected the annotation's parameter name; got $ls")
    }

    @Test
    fun annotationParameterNamesRankFirst() {
        val ls = labels("package demo\n@Cfg(co|)\nfun f() {}")
        assertEquals("count =", ls.first(), "annotation parameter names should rank first; got $ls")
    }

    @Test
    fun suppliedParameterIsNotReoffered() {
        val ls = labels("package demo\n@Cfg(count = 1, la|)\nfun f() {}")
        assertTrue("label =" in ls, "the remaining parameter should be offered; got $ls")
        assertTrue("count =" !in ls, "an already-supplied parameter must not be re-offered; got $ls")
    }

    @Test
    fun annotationNamePositionOffersOnlyAnnotationTypes() {
        val ns = names("package demo\nannotation class MyMarker\nclass MyWidget\n@My| class Foo")
        assertTrue("MyMarker" in ns, "an annotation class must be offered at `@…`; got $ns")
        assertTrue("MyWidget" !in ns, "a non-annotation class must NOT be offered at `@…`; got $ns")
    }

    @Test
    fun normalTypePositionStillOffersNonAnnotationTypes() {
        // A normal type slot is unfiltered — a class and an annotation are both valid there.
        val ns = names("package demo\nannotation class MyMarker\nclass MyWidget\nfun f() { val x: My| }")
        assertTrue("MyWidget" in ns, "a normal type position must offer classes; got $ns")
        assertTrue("MyMarker" in ns, "and annotation classes (still valid as types); got $ns")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf("Anno.kt" to "package demo\nannotation class Cfg(val count: Int = 0, val label: String = \"\")\n"),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

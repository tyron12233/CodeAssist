package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completion of annotation arguments for an arbitrary annotation (resolved from its type, not hardcoded): the
 * annotation's parameter names are offered inside `@Foo(…)` and ranked first, and an already-supplied
 * parameter isn't re-offered.
 */
class KotlinAnnotationCompletionTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }

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

    companion object {
        val srcDir: Path = tempProject(
            mapOf("Anno.kt" to "package demo\nannotation class Cfg(val count: Int = 0, val label: String = \"\")\n"),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

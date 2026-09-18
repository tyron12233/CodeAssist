package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `private` top-level function/property (or `@Composable`) is FILE-SCOPED: it must not be offered when
 * completing in ANOTHER file, nor resolve there. The in-memory project source model used to leak private
 * top-levels into cross-file completion (the disk indexes already excluded them). A same-file private top-level
 * is still offered — it IS accessible there.
 */
class KotlinPrivateTopLevelCompletionTest {

    private fun labels(fileName: String, code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, fileName, code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun privateTopLevelFromAnotherFileIsNotOffered() {
        val labels = labels("Use.kt", "package demo\nfun use() { helper| }")
        assertTrue("helperPublic" in labels, "a public top-level from another file should be offered; got ${labels.take(30)}")
        assertTrue("helperSecret" !in labels, "a PRIVATE top-level from another file must NOT be offered; got ${labels.take(30)}")
    }

    @Test
    fun privateTopLevelInTheSameFileIsStillOffered() {
        val labels = labels("Use.kt", "package demo\nprivate fun helperLocal() {}\nfun use() { helper| }")
        assertTrue("helperLocal" in labels, "a private top-level in the SAME file must still be offered; got ${labels.take(30)}")
    }

    // A private top-level TYPE is FILE-scoped too — the same rule as functions, but it flows through the
    // type-NAME candidate path (`typeNameCandidates`), which used to leak it cross-file, labelled "same package"
    // with no usable import (the reported `private enum class BoxState` from a copied `updateTransition` sample).

    @Test
    fun privateTopLevelTypeFromAnotherFileIsNotOffered() {
        assertTrue(
            "BoxState" !in labels("Use.kt", "package demo\nfun use() { val x: BoxSt| }"),
            "a PRIVATE top-level type from another file must NOT be offered as a type-name candidate",
        )
        assertTrue(
            "PublicState" in labels("Use.kt", "package demo\nfun use() { val x: PublicSt| }"),
            "but a PUBLIC top-level type from another file must still be offered",
        )
    }

    @Test
    fun privateTopLevelTypeInTheSameFileIsStillOffered() {
        val labels = labels("Use.kt", "package demo\nprivate enum class LocalState { A, B }\nfun use() { val x: LocalSt| }")
        assertTrue("LocalState" in labels, "a private top-level type in the SAME file must still be offered; got ${labels.take(30)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Helper.kt" to "package demo\nprivate fun helperSecret() {}\nfun helperPublic() {}\n" +
                    "private enum class BoxState { Collapsed, Expanded }\nclass PublicState\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

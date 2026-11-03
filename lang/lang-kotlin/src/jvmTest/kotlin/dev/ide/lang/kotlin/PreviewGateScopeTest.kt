package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The preview's error gate checks only what the `@Preview` function can reach in its file: an error in a
 * declaration it never names doesn't block it, while one it reaches through a chain of names still does, and the
 * answer is the same whether or not the editor has already analysed the file.
 */
class PreviewGateScopeTest {

    private val text = """
        package app

        class Unrelated {
            val broken: Int = "not a number"
        }

        class Reached {
            val alsoBroken: Int = "still not a number"
        }

        fun helper(): Int = 1

        fun viaChain(): Int = Reached().alsoBroken

        fun Clean() { println(helper()) }

        fun Dirty() { println(viaChain()) }
    """.trimIndent() + "\n"

    private val srcDir = tempProject(mapOf("placeholder.kt" to "package app\n"))

    private fun parsed(name: String): Pair<KotlinSourceAnalyzer, DiskFile> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val vf = DiskFile(srcDir.resolve(name))
        analyzer.incrementalParser.parseFull(SnippetDoc(text, vf))
        return analyzer to vf
    }

    @Test
    fun anErrorThePreviewCannotReachDoesNotBlockIt() {
        val (analyzer, vf) = parsed("Scope.kt")
        assertTrue(analyzer.previewBlockingErrors(vf).isNotEmpty(), "the whole file does have blocking errors")
        assertEquals(emptyList(), analyzer.previewBlockingErrors(vf, "Clean"))
    }

    @Test
    fun anErrorReachedThroughAChainOfNamesStillBlocks() {
        val (analyzer, vf) = parsed("Chain.kt")
        val errors = analyzer.previewBlockingErrors(vf, "Dirty")
        assertEquals(1, errors.size, "only the reached class's error: $errors")
    }

    @Test
    fun theAnswerFromTheEditorsCacheMatchesAFreshOne() {
        val (fresh, freshVf) = parsed("Fresh.kt")
        val expected = fresh.previewBlockingErrors(freshVf, "Dirty")

        val (warm, warmVf) = parsed("Warm.kt")
        runBlocking { warm.analyze(warmVf) } // the editor's full pass fills the per-file cache
        assertEquals(expected, warm.previewBlockingErrors(warmVf, "Dirty"))
        assertEquals(emptyList(), warm.previewBlockingErrors(warmVf, "Clean"))
    }

    @Test
    fun anUnknownEntryFallsBackToTheWholeFile() {
        val (analyzer, vf) = parsed("Unknown.kt")
        assertEquals(analyzer.previewBlockingErrors(vf), analyzer.previewBlockingErrors(vf, "NoSuchPreview"))
    }
}

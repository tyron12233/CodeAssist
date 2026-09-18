package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Editor-side Compose preview support: detecting `@Preview @Composable` functions and lowering the file so a
 * detected preview is a runnable [RNode] tree. The actual on-device render is the injected runner; this
 * verifies the detect → lower pipeline (CI, no Compose runtime needed).
 */
class KotlinComposePreviewTest {

    // The file is written to disk so the symbol service's source model includes its top-level functions
    // (so a same-file composable call resolves), then parsed so the analyzer's per-file cache is populated.
    private fun analyze(file: String, code: String): Pair<KotlinSourceAnalyzer, DiskFile> {
        val srcDir = tempProject(mapOf(file to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val vf = DiskFile(srcDir.resolve(file))
        runBlocking { analyzer.incrementalParser.parseFull(SnippetDoc(code, vf)) }
        return analyzer to vf
    }

    @Test
    fun detectsPreviewComposables() {
        val code = """
            package demo
            annotation class Preview
            annotation class Composable
            @Preview @Composable fun HelloPreview() {}
            @Composable fun NotAPreview() {}
            fun plain() {}
        """.trimIndent()
        val (analyzer, vf) = analyze("Ui.kt", code)
        val previews = analyzer.composePreviews(vf)
        assertEquals(listOf("HelloPreview"), previews.map { it.functionName }, "only the @Preview @Composable is a target")
        assertEquals(0, previews.single().arity)
    }

    @Test
    fun lowersAPreviewToACompleteTree() {
        // A preview calling a same-file composable — both lower; the preview tree has no Unsupported nodes.
        val code = """
            package demo
            annotation class Preview
            annotation class Composable
            @Composable fun Card(n: Int) {}
            @Preview @Composable fun CardPreview() { Card(1) }
        """.trimIndent()
        val (analyzer, vf) = analyze("Ui.kt", code)
        val program = analyzer.lowerFile(vf)
        val preview = assertNotNull(program["CardPreview/0"], "preview should lower; have ${program.keys}")
        assertTrue(preview.isComplete, "preview should lower without Unsupported nodes; diags=${preview.diagnostics}")
        var calls = 0
        preview.body.walk { if (it is RNode.Call) calls++ }
        assertTrue(calls >= 1, "the preview body should contain the Card(1) call")
    }

    @Test
    fun composeRuntimeNotAttachedWithoutComposeOnClasspath() {
        // With no androidx.compose.* on the classpath (the Learn scratch's state before its one-time download
        // attaches the AARs), the runtime reports not-attached. This is the signal the preview host polls to
        // show a transient "Preparing" state (and retry) rather than latching a first-run "unresolved call"
        // failure. classpathReady() is trivially true here (no index injected), so this gate is what tells the
        // scratch apart from a warmed classpath.
        val (analyzer, _) = analyze("Ui.kt", "package demo\nfun x() {}\n")
        assertTrue(analyzer.classpathReady(), "no index → classpathReady() is trivially true")
        assertFalse(analyzer.composeRuntimeAttached(), "no compose on the classpath → runtime not attached")
    }
}

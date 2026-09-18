package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Editor support for `@Preview`: argument-name + constant completion inside the annotation, and the
 * `kt.preview*` validation diagnostics (non-@Composable target, an unfed required parameter).
 */
class KotlinPreviewEditorTest {

    private val preamble = """
        package demo
        annotation class Composable
        annotation class Preview(val name: String = "", val widthDp: Int = -1, val uiMode: Int = 0, val device: String = "")
        annotation class PreviewParameter(val provider: kotlin.reflect.KClass<*>, val limit: Int = Int.MAX_VALUE)
        class Names
    """.trimIndent()

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "P.kt", code) }.items.map { it.label }

    private fun diags(name: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(name)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    // --- completion ---

    @Test
    fun offersPreviewArgumentNames() {
        val ls = labels("$preamble\n@Preview(wid|)\n@Composable fun P() {}")
        assertTrue("widthDp =" in ls, "expected the widthDp argument name; got $ls")
    }

    @Test
    fun offersUiModeConstantsInValuePosition() {
        val ls = labels("$preamble\n@Preview(uiMode = UI_MODE|)\n@Composable fun P() {}")
        assertTrue("Configuration.UI_MODE_NIGHT_YES" in ls, "expected uiMode constants; got $ls")
    }

    @Test
    fun offersDeviceConstantsInValuePosition() {
        val ls = labels("$preamble\n@Preview(device = PIXEL|)\n@Composable fun P() {}")
        assertTrue("Devices.PIXEL_4" in ls, "expected device constants; got $ls")
    }

    @Test
    fun noPreviewExtrasOutsideAnnotation() {
        val ls = labels("$preamble\n@Composable fun P() { val x = wid| }")
        assertTrue(ls.none { it == "widthDp = " }, "preview arg names must not leak outside @Preview; got $ls")
    }

    // --- diagnostics ---

    @Test
    fun flagsPreviewWithoutComposable() {
        val d = diags("A.kt", "$preamble\n@Preview fun NotComposable() {}")
        assertTrue(
            d.any { it.code == "kt.previewNotComposable" },
            "a @Preview without @Composable should be flagged; got $d",
        )
    }

    @Test
    fun flagsUnfedRequiredParameter() {
        val d = diags("B.kt", "$preamble\n@Preview @Composable fun P(title: String) {}")
        assertTrue(
            d.any { it.code == "kt.previewParameters" },
            "a required parameter without default/@PreviewParameter should be flagged; got $d",
        )
    }

    @Test
    fun previewParameterParamIsNotFlagged() {
        val d = diags("C.kt", "$preamble\n@Preview @Composable fun P(@PreviewParameter(Names::class) n: String) {}")
        assertTrue(
            d.none { it.code == "kt.previewParameters" },
            "a @PreviewParameter-fed parameter must not be flagged; got $d",
        )
    }

    @Test
    fun cleanPreviewHasNoPreviewDiagnostics() {
        val d = diags("D.kt", "$preamble\n@Preview @Composable fun P() {}")
        assertTrue(
            d.none { it.code == "kt.previewNotComposable" || it.code == "kt.previewParameters" },
            "a valid @Preview must not carry preview diagnostics; got $d",
        )
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

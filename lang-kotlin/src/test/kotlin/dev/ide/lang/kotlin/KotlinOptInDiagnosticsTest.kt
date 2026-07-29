package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (`@RequiresOptIn` / `@OptIn`) usage diagnostics. A call to (or a type reference of) an experimental
 * API must be flagged when the use site hasn't opted in — as an ERROR for a default/`ERROR`-level marker and a
 * WARNING for a `WARNING`-level one — and must be CLEAN when opted in via `@OptIn(Marker::class)`, a
 * propagating `@Marker`, or a file-level `@file:OptIn(...)`. Exercised over source-defined markers (the marker
 * class + experimental declarations are seeded in the module, the usages analyzed as snippets); the library
 * path shares the same [KotlinSymbolService.optInMarkersOf] resolution.
 */
class KotlinOptInDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun optIn(diags: List<Diagnostic>) =
        diags.filter { it.code == KotlinDiagnosticCodes.OPT_IN_USAGE || it.code == KotlinDiagnosticCodes.OPT_IN_USAGE_ERROR }

    @Test
    fun experimentalCallWithoutOptInIsError() {
        val diags = diagnose("UseA.kt", "package demo\nfun use() { experimentalFun() }")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.OPT_IN_USAGE_ERROR },
            "an unopted-in call to an ERROR-level experimental API must be flagged; got $diags",
        )
    }

    @Test
    fun experimentalCallWithOptInAnnotationIsClean() {
        val diags = diagnose("UseB.kt", "package demo\n@OptIn(ExpApi::class)\nfun use() { experimentalFun() }")
        assertTrue(optIn(diags).isEmpty(), "@OptIn(ExpApi::class) opts the call in; got $diags")
    }

    @Test
    fun experimentalCallWithPropagatingMarkerIsClean() {
        val diags = diagnose("UseC.kt", "package demo\n@ExpApi\nfun use() { experimentalFun() }")
        assertTrue(optIn(diags).isEmpty(), "a propagating @ExpApi on the caller opts the call in; got $diags")
    }

    @Test
    fun fileLevelOptInIsClean() {
        val diags = diagnose("UseD.kt", "@file:OptIn(ExpApi::class)\npackage demo\nfun use() { experimentalFun() }")
        assertTrue(optIn(diags).isEmpty(), "@file:OptIn opts every usage in the file in; got $diags")
    }

    @Test
    fun warningLevelMarkerIsWarningNotError() {
        val diags = diagnose("UseE.kt", "package demo\nfun use() { warnFun() }")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.OPT_IN_USAGE } &&
                diags.none { it.code == KotlinDiagnosticCodes.OPT_IN_USAGE_ERROR },
            "a WARNING-level marker must produce kt.optInUsage (a warning), not the error variant; got $diags",
        )
    }

    @Test
    fun experimentalTypeReferenceWithoutOptInIsFlagged() {
        val diags = diagnose("UseF.kt", "package demo\nfun use(x: ExpType) { }")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.OPT_IN_USAGE_ERROR },
            "a reference to an experimental TYPE must be flagged without opt-in; got $diags",
        )
    }

    @Test
    fun nonExperimentalCallIsClean() {
        val diags = diagnose("UseG.kt", "package demo\nfun use() { normalFun() }")
        assertTrue(optIn(diags).isEmpty(), "a non-experimental call must never be flagged; got $diags")
    }

    companion object {
        // The marker classes + experimental declarations, seeded in the module so the usages (analyzed as
        // snippets in the same `demo` package) resolve to source symbols carrying the markers.
        private val MARKERS = """
            package demo
            @RequiresOptIn(level = RequiresOptIn.Level.ERROR)
            annotation class ExpApi
            @RequiresOptIn(level = RequiresOptIn.Level.WARNING)
            annotation class ExpWarn
            @ExpApi fun experimentalFun() {}
            @ExpApi class ExpType
            @ExpWarn fun warnFun() {}
            fun normalFun() {}
        """.trimIndent()

        val srcDir: Path = tempProject(mapOf("experimental.kt" to MARKERS, "Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}

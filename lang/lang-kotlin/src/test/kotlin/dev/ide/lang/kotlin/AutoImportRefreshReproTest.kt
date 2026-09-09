package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adding an import (the completion auto-import edit) must clear a previously-unresolved reference's diagnostic
 * in the SCOPED (incremental) analyze path, exactly as a full re-analyze would — the "auto-import doesn't
 * refresh diagnostics" report. Covers plain named references (type / value / call); the operator/`by`-delegate/
 * destructuring (convention-invoked) cases are covered at the plan level by [IncrementalDeclsImportPlanTest].
 */
class AutoImportRefreshReproTest {

    private fun key(d: Diagnostic) = "${d.range.start}-${d.range.end}:${d.severity}:${d.code}:${d.message}"

    private fun diagsOf(srcDir: Path, text: String, analyzer: KotlinSourceAnalyzer): List<String> {
        val doc = SnippetDoc(text, DiskFile(srcDir.resolve("Big.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file).diagnostics
        }.map(::key).sorted()
    }

    private fun assertImportClearsDiagnostic(v1: String, v2: String) {
        val srcDir = tempProject(mapOf("Big.kt" to v2)) // disk = final version
        val incremental = KotlinSourceAnalyzer(fakeContext(srcDir))
        val d1 = diagsOf(srcDir, v1, incremental)                  // seed cache with v1 (unresolved)
        val scoped = diagsOf(srcDir, v2, incremental)              // v2 → scoped/reuse path (import added)
        val full = diagsOf(srcDir, v2, KotlinSourceAnalyzer(fakeContext(srcDir))) // fresh → full
        assertTrue(d1.any { "kt.unresolved" in it }, "v1 must have the unresolved reference: $d1")
        assertEquals(full, scoped, "adding the import must clear the diagnostic in the scoped path (matching full)")
        assertTrue(scoped.none { "kt.unresolved" in it }, "the unresolved reference must be gone after the import: $scoped")
    }

    @Test
    fun addingATypeImportClearsUnresolved() {
        assertImportClearsDiagnostic(
            "package demo\nfun f(): Random? = null\n",
            "package demo\nimport kotlin.random.Random\nfun f(): Random? = null\n",
        )
    }

    @Test
    fun addingAValueImportClearsUnresolved() {
        assertImportClearsDiagnostic(
            "package demo\nfun f() { println(PI) }\n",
            "package demo\nimport kotlin.math.PI\nfun f() { println(PI) }\n",
        )
    }

    @Test
    fun addingACallImportClearsUnresolved() {
        assertImportClearsDiagnostic(
            "package demo\nfun f() { sqrt(2.0) }\n",
            "package demo\nimport kotlin.math.sqrt\nfun f() { sqrt(2.0) }\n",
        )
    }
}

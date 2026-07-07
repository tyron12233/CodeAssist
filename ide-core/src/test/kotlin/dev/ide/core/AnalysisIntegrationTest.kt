package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.DiagnosticTag
import dev.ide.lang.dom.Severity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end check that the analysis engine, wired into [IdeServices] over the real JDT backend,
 * merges compiler errors and the built-in Java analyzers into one diagnostic set for the editor.
 */
class AnalysisIntegrationTest {

    @Test
    fun compilerErrorAndAnalyzerWarningsMergeForOneFile() {
        val dir = Files.createTempDirectory("ide-analysis")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            // A probe in :app's source root (need not exist on disk — analysis runs off the live overlay).
            val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
            val text = buildString {
                appendLine("package com.example.app;")
                appendLine("import com.example.util.Formatter;") // never referenced -> unused-import warning
                appendLine("public class Probe {")
                appendLine("    void m() {")
                appendLine("        System.out.println(\"hi\")")  // System.out warning + a missing ';' compiler error
                appendLine("    }")
                appendLine("}")
            }

            val diags = runBlocking { ide.analyzeDiagnostics(probe, text) }
            val codes = diags.mapNotNull { it.code }.toSet()

            assertTrue(
                diags.any { it.source == DiagnosticSource.Compiler && it.severity == Severity.ERROR },
                "expected a compiler error (the missing ';'); got: ${diags.map { it.source to it.message }}",
            )
            assertTrue("java.systemOut" in codes, "expected the System.out analyzer warning; codes=$codes")
            assertTrue("java.unusedImport" in codes, "expected the unused-import analyzer warning; codes=$codes")
            assertTrue(
                diags.any { it.code == "java.unusedImport" && DiagnosticTag.UNUSED in it.tags },
                "the unused import should carry the UNUSED tag (drives muted rendering)",
            )
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun suppressionSilencesAnAnalyzerWarning() {
        val dir = Files.createTempDirectory("ide-suppress")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
            val text = buildString {
                appendLine("package com.example.app;")
                appendLine("public class Probe {")
                appendLine("    void m() {")
                appendLine("        // noinspection java.systemOut")
                appendLine("        System.out.println(\"hi\");")
                appendLine("    }")
                appendLine("}")
            }

            val codes = runBlocking { ide.analyzeDiagnostics(probe, text) }.mapNotNull { it.code }.toSet()
            assertTrue("java.systemOut" !in codes, "// noinspection on the prior line should suppress it; codes=$codes")
        }
        dir.toFile().deleteRecursively()
    }
}

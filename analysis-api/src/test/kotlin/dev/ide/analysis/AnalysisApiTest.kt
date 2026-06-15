package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the analysis-api contract end-to-end without an engine, proving the SPI is implementable.
 * Covers a trivial analyzer reporting a warning, the quick-fix → [WorkspaceEdit] path, and the
 * profile + lint-report model.
 */
class AnalysisApiTest {

    @Test
    fun fileAnalyzerReportsThroughSink() {
        val analyzer = FileNameAnalyzer()
        val lower = CollectingSink(analyzer.id)
        analyzer.analyze(FakeTarget(FakeFile("/src/main.java")), lower)

        assertEquals(1, lower.diagnostics.size)
        val d = lower.diagnostics.single()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals("test.naming", d.code)
        assertEquals(DiagnosticSource.Analyzer(analyzer.id), d.source)

        val upper = CollectingSink(analyzer.id)
        analyzer.analyze(FakeTarget(FakeFile("/src/Main.java")), upper)
        assertTrue(upper.diagnostics.isEmpty(), "A properly-cased file should produce no finding")
    }

    @Test
    fun quickFixComputesAtomicWorkspaceEdit() {
        val file = FakeFile("/src/Main.java")
        val fix: QuickFix = InsertImportFix(file, "java.util.List")
        assertEquals(CodeActionKind.QUICK_FIX, fix.kind)

        val edit = runBlocking { fix.computeEdits(FakeFixContext(FakeTarget(file))) }

        assertFalse(edit.isEmpty)
        assertEquals(setOf<VirtualFile>(file), edit.files)
        val edits = edit.edits.getValue(file)
        assertEquals(1, edits.size)
        assertEquals(DocumentEdit(0, 0, "import java.util.List;\n"), edits.single())
    }

    @Test
    fun profileDisablesAndOverridesSeverity() {
        val analyzer = FileNameAnalyzer()
        val profile = AnalysisProfile(
            disabled = setOf(AnalyzerId("other")),
            severityOverrides = mapOf(analyzer.id to Severity.ERROR),
        )
        assertTrue(profile.isEnabled(analyzer.id))
        assertFalse(profile.isEnabled(AnalyzerId("other")))
        assertEquals(Severity.ERROR, profile.severityFor(analyzer))      // override wins
        assertEquals(Severity.WARNING, AnalysisProfile.DEFAULT.severityFor(analyzer)) // falls back to default
    }

    @Test
    fun lintReportAggregatesAcrossFiles() {
        val a = FakeFile("/a.java")
        val b = FakeFile("/b.java")
        val report = LintReport(
            listOf(
                FileDiagnostics(a, listOf(diag(Severity.ERROR), diag(Severity.WARNING))),
                FileDiagnostics(b, listOf(diag(Severity.WARNING))),
            ),
        )
        assertEquals(3, report.all.size)
        assertEquals(mapOf(Severity.ERROR to 1, Severity.WARNING to 2), report.countsBySeverity)
        assertTrue(report.hasErrors)
    }

    private fun diag(severity: Severity) = Diagnostic(
        range = TextRange(0, 1), severity = severity, message = "x",
        source = DiagnosticSource.Analyzer(AnalyzerId("test")),
    )
}

/** A SYNTAX analyzer: flag a Java file whose name does not start uppercase. */
private class FileNameAnalyzer : FileAnalyzer {
    override val id = AnalyzerId("test.fileName")
    override val displayName = "File name convention"
    override val languages = setOf(LanguageId("java"))
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.SYNTAX
    override val interestedIn: Set<NodeKind>? = null

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        val first = target.file.name.firstOrNull() ?: return
        if (!first.isUpperCase()) {
            sink.report(
                range = TextRange(0, 1),
                severity = defaultSeverity,
                message = "Type file '${target.file.name}' should start with an uppercase letter",
                code = "test.naming",
            )
        }
    }
}

/** A quick-fix that inserts an import at the top of a file. */
private class InsertImportFix(private val file: VirtualFile, private val import: String) : QuickFix {
    override val title = "Import $import"
    override val kind = CodeActionKind.QUICK_FIX
    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
        ctx.checkCanceled()
        return WorkspaceEdit.of(file, DocumentEdit(offset = 0, oldLength = 0, newText = "import $import;\n"))
    }
}

/** Collects reports and stamps the analyzer source, mimicking what the engine's sink does. */
private class CollectingSink(private val analyzer: AnalyzerId) : DiagnosticSink {
    val diagnostics = mutableListOf<Diagnostic>()
    override fun report(
        range: TextRange,
        severity: Severity,
        message: String,
        code: String?,
        fixes: List<QuickFix>,
        tags: Set<DiagnosticTag>,
        related: List<RelatedRange>,
    ) {
        diagnostics += Diagnostic(range, severity, message, DiagnosticSource.Analyzer(analyzer), code, fixes, tags, related)
    }
}

private class FakeFixContext(override val target: AnalysisTarget) : FixContext {
    override fun checkCanceled() {}
}

/** Only [file] and [documentVersion] are exercised; the heavier members error if a test touches them. */
private class FakeTarget(override val file: VirtualFile) : AnalysisTarget {
    override val parsed get() = error("parsed not used in this test")
    override val documentVersion = 1L
    override val resolver get() = error("resolver not used in this test")
    override val index get() = error("index not used in this test")
    override val module get() = error("module not used in this test")
    override fun checkCanceled() {}
}

private class FakeFile(override val path: String) : VirtualFile {
    override val name = path.substringAfterLast('/')
    override val isDirectory = false
    override val exists = true
    override val length = 0L
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash() = ContentHash("")
    override fun readBytes() = ByteArray(0)
    override fun readText(): CharSequence = ""
}

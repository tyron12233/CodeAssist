package dev.codeassist.csharp.editor


import dev.codeassist.csharp.project.CsMains
import dev.codeassist.csharp.project.csproj.CsprojCheck
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.FileAnalyzer
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reports problems in `.csproj`, `.props` and `.targets` files while they are being edited.
 *
 * Uses the SEMANTIC tier because some checks read project files from disk, such as checking
 * whether referenced files exist and finding C# entry points.
 */
internal class CsprojAnalyzer : FileAnalyzer {

    override val id = AnalyzerId("csproj")
    override val displayName = "C# project file problems"
    override val languages = setOf(LanguageId("csproj"))
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.SEMANTIC
    override val interestedIn: Set<NodeKind>? = null

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        val file = Paths.get(target.file.path)
        val text = target.parsed.text().toString()
        val dir = file.parent
        val isProjectFile = file.fileName.toString().endsWith(".csproj", ignoreCase = true)

        val env = CsprojCheck.Env(
            exists = { path -> exists(dir, path) },
            mainClasses = if (isProjectFile && dir != null) {
                CsMains.classes(dir)
            } else {
                emptyList()
            },
            hasTopLevelStatements = isProjectFile &&
                dir != null &&
                CsMains.hasTopLevelStatements(dir),
        )

        target.checkCanceled()

        CsprojCheck.check(text, env).forEach { finding ->
            reportFinding(text, finding, sink)
        }
    }

    private fun reportFinding(
        text: String,
        finding: CsprojCheck.Finding,
        sink: DiagnosticSink,
    ) {
        val start = finding.start.coerceIn(0, text.length)
        val end = finding.end.coerceIn(start, text.length)
        val rangeEnd = if (end == start) minOf(start + 1, text.length) else end

        sink.report(
            TextRange(start, rangeEnd),
            if (finding.level == CsprojCheck.Level.ERROR) Severity.ERROR else Severity.WARNING,
            finding.message,
            finding.code,
        )
    }

    /** Resolves a path relative to the project directory, accepting either path separator. */
    private fun exists(dir: Path?, path: String): Boolean = try {
        val normalized = path.replace('\\', '/')
        val resolved = if (dir == null) Paths.get(normalized) else dir.resolve(normalized)
        Files.exists(resolved)
    } catch (e: Exception) {
        true // Don't report paths that could not be checked.
    }
}

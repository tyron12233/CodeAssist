package dev.ide.lang.kotlin.analysis

import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.KotlinDiagnosticCodes
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * Contributes the Kotlin editor analysis surface onto the analysis-api extension points: a diagnostic
 * provider (the tolerant PSI parse + the resolver's semantic findings) and a code-action provider (the
 * "import unresolved reference" fixes). Both delegate to the per-module [KotlinSourceAnalyzer] reached via
 * `target.resolver`, so the analyzer's tuned incremental work is preserved — the engine's `targetFor`
 * already parsed the live buffer through that same cached instance. Declares `languages = {kotlin}` so it
 * never runs on Java/XML targets now that one pipeline serves every language.
 */
private val KOTLIN = KotlinLanguageBackend.LANGUAGE_ID

/** The Kotlin diagnostics, adapted from the resolver into the unified [Diagnostic] stream. */
class KotlinDiagnosticProvider(override val id: String = "kotlin") : DiagnosticProvider {
    override val languages = setOf(KOTLIN)

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> {
        val analyzer = target.resolver as? KotlinSourceAnalyzer ?: return emptyList()
        return analyzer.analyze(target.file).diagnostics.map { d ->
            // The tolerant parser tags syntax errors `kt.syntax`; everything else is a semantic finding.
            val sid = if (d.code == KotlinDiagnosticCodes.SYNTAX) "kotlin.syntax" else "kotlin.semantic"
            Diagnostic(d.range, d.severity, d.message, DiagnosticSource.Analyzer(AnalyzerId(sid)), d.code)
        }
    }
}

/** Caret intentions for Kotlin: offer an `import` for each unresolved reference at the caret. */
class KotlinImportActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> {
        val analyzer = target.resolver as? KotlinSourceAnalyzer ?: return emptyList()
        return analyzer.importFixesAt(target.file, range.start).map { fix ->
            object : QuickFix {
                override val title = fix.title
                override val kind = CodeActionKind.QUICK_FIX
                override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit =
                    WorkspaceEdit.of(ctx.target.file, *fix.edits.toTypedArray())
            }
        }
    }
}

object KotlinAnalysisSupport {
    val PLUGIN = PluginId("kotlin-analysis")

    fun register(extensions: ExtensionRegistry, plugin: PluginId = PLUGIN) {
        extensions.register(DIAGNOSTIC_PROVIDER_EP, KotlinDiagnosticProvider(), plugin)
        extensions.register(ACTION_PROVIDER_EP, KotlinImportActionProvider(), plugin)
    }
}

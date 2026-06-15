package dev.ide.analysis

import dev.ide.index.IndexService
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile

/**
 * The Analyzer SPI: two analyzer shapes over a common base, with the [tier] driving scheduling.
 * Built-ins (the JDT/Kotlin plugins) use the same API as third-party plugins; all register on
 * [ANALYZER_EP].
 */

/**
 * Which event stream feeds an analyzer and how aggressively it runs:
 *  - [SYNTAX]   — DOM only (naming, structure, formatting). Cheapest; effectively every keystroke.
 *  - [SEMANTIC] — needs the [AnalysisTarget.resolver] (types/symbols): unused var, type mismatch, NPE.
 *  - [PROJECT]  — needs the cross-file [IndexService] view: unused public API, duplicate declarations.
 */
enum class AnalyzerTier { SYNTAX, SEMANTIC, PROJECT }

interface Analyzer {
    val id: AnalyzerId
    val displayName: String
    val languages: Set<LanguageId>
    val defaultSeverity: Severity
    val tier: AnalyzerTier
}

/**
 * Runs over one file. The framework performs one shared DOM traversal per file pass and dispatches
 * each node to the SYNTAX/SEMANTIC analyzers that registered interest in its [NodeKind] — N analyzers,
 * one walk. [analyze] is synchronous: it reads an already-parsed [AnalysisTarget.parsed] and the
 * synchronous resolver, and runs inside the framework's cancellable read action.
 */
interface FileAnalyzer : Analyzer {
    /** `null` = invoked once for the whole file; else the framework only dispatches these node kinds. */
    val interestedIn: Set<NodeKind>?

    fun analyze(target: AnalysisTarget, sink: DiagnosticSink)
}

/**
 * Runs over the project/index and emits diagnostics attributed back to specific files (hence the
 * file-aware [ProjectDiagnosticSink]). Suspending and cancellable because it may pull many files'
 * targets and query the index — the heaviest, lowest-priority tier.
 */
interface ProjectAnalyzer : Analyzer {
    suspend fun analyze(scope: ProjectAnalysisScope, sink: ProjectDiagnosticSink)
}

/** Per-file semantic context for a [FileAnalyzer] / a single file in a project sweep. */
interface AnalysisTarget {
    val file: VirtualFile
    /** The neutral, error-tolerant DOM (always covers the whole file, even mid-edit). */
    val parsed: ParsedFile
    /** The document version [parsed] was built from — the cache/staleness key. */
    val documentVersion: Long
    /** Resolution + types for this file: `resolve(node)`, `scopeAt`, `expectedTypeAt` (language-api). */
    val resolver: SourceAnalyzer
    /** Global lookups for analyzers that need them (e.g. "is this name declared elsewhere?"). */
    val index: IndexService
    val module: Module
    fun checkCanceled()
}

/** The whole-project view for [ProjectAnalyzer]s and batch lint. */
interface ProjectAnalysisScope {
    val modules: List<Module>
    val index: IndexService

    /** Every analyzable source file in scope. */
    fun files(): Sequence<VirtualFile>

    /** The per-file context for [file] (parses/maintains the DOM on demand). */
    suspend fun targetFor(file: VirtualFile): AnalysisTarget

    fun checkCanceled()
}

/**
 * Where a [FileAnalyzer] reports findings — implicitly against [AnalysisTarget.file]. The framework
 * stamps [DiagnosticSource], applies the profile's severity override, and filters suppressions before
 * publishing, so analyzers just describe the problem.
 */
interface DiagnosticSink {
    fun report(
        range: TextRange,
        severity: Severity,
        message: String,
        code: String? = null,
        fixes: List<QuickFix> = emptyList(),
        tags: Set<DiagnosticTag> = emptySet(),
        related: List<RelatedRange> = emptyList(),
    )
}

/** Like [DiagnosticSink] but the report names its target [file] — required for cross-file analyzers. */
interface ProjectDiagnosticSink {
    fun report(
        file: VirtualFile,
        range: TextRange,
        severity: Severity,
        message: String,
        code: String? = null,
        fixes: List<QuickFix> = emptyList(),
        tags: Set<DiagnosticTag> = emptySet(),
        related: List<RelatedRange> = emptyList(),
    )
}

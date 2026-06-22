package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import dev.ide.vfs.VirtualFile

/**
 * The framework facade and its plug-in seams. The engine that implements
 * [AnalysisService] — edit-driven scheduling with per-tier debounce + cancellation, per-version result
 * caching, central suppression filtering, and atomic fix application — lives in analysis-impl; this
 * file is only the contract.
 */

/**
 * A non-[Analyzer] producer of diagnostics, run by the scheduler exactly like an analyzer. The
 * compiler is the built-in one: it adapts a backend's `SourceAnalyzer.analyze(file)` and the build
 * system's compiler output into [Diagnostic]s with `source = `[DiagnosticSource.Compiler] and a stable
 * [Diagnostic.code]. From the merge step on it is indistinguishable from analyzer output, and its fixes
 * come from code-keyed [QuickFixProvider]s — the compiler itself stays fix-agnostic. Registered on
 * [DIAGNOSTIC_PROVIDER_EP].
 */
interface DiagnosticProvider {
    val id: String

    /** Languages this provider applies to; **empty = all languages**. The engine skips it for files in
     *  any other language, so a language-specific provider (the JDT compiler, the Kotlin/XML analyzers)
     *  no longer runs on foreign files once the pipeline is multi-language. */
    val languages: Set<LanguageId> get() = emptySet()

    suspend fun diagnose(target: AnalysisTarget): List<Diagnostic>
}

/**
 * Per-analyzer enable/disable + severity override, stored in project settings. A default profile
 * ships with the built-ins. Compiler-error severity is fixed at [Severity.ERROR] and is not overridable
 * here (the engine ignores overrides for [DiagnosticSource.Compiler]).
 */
data class AnalysisProfile(
    val disabled: Set<AnalyzerId> = emptySet(),
    val severityOverrides: Map<AnalyzerId, Severity> = emptyMap(),
) {
    fun isEnabled(id: AnalyzerId): Boolean = id !in disabled

    /** The effective severity for [analyzer]: its override if any, else its declared default. */
    fun severityFor(analyzer: Analyzer): Severity =
        severityOverrides[analyzer.id] ?: analyzer.defaultSeverity

    companion object {
        val DEFAULT = AnalysisProfile()
    }
}

/** The merged diagnostics for one file, the unit of a [LintReport]. */
data class FileDiagnostics(val file: VirtualFile, val diagnostics: List<Diagnostic>)

/**
 * The result of a whole-project batch sweep — every diagnostic, groupable by severity/file/source
 * for pre-commit / CI-style checks. Produced by the same analyzers as live editing; only the timing
 * differs (a non-incremental pass over every file plus the project analyzers).
 */
data class LintReport(val files: List<FileDiagnostics>) {
    val all: List<Diagnostic> get() = files.flatMap { it.diagnostics }
    val countsBySeverity: Map<Severity, Int> get() = all.groupingBy { it.severity }.eachCount()
    val hasErrors: Boolean get() = all.any { it.severity == Severity.ERROR }
}

/**
 * The single entry point the editor + Problems view talk to. The current merged set is served
 * cheaply from cache; analysis happens off-thread and pushes updates through [addListener].
 */
interface AnalysisService {
    /** The last-published merged set for [file] (compiler + analyzers). Cheap, non-blocking; may be stale. */
    fun diagnostics(file: VirtualFile): List<Diagnostic>

    /** Force a fresh, complete analysis of [file] now and return its merged diagnostics. */
    suspend fun analyzeNow(file: VirtualFile): List<Diagnostic>

    /** A whole-project sweep producing a [LintReport] (batch lint mode). */
    suspend fun lint(scope: ProjectAnalysisScope): LintReport

    /**
     * All fixes available for [diagnostic]: those it already carries, plus any contributed by code-keyed
     * [QuickFixProvider]s. Computed lazily — call this when the user opens the lightbulb / fix menu.
     */
    fun fixesFor(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix>

    /**
     * The unified editor actions at [range] in [file]: quick-fixes for diagnostics overlapping [range]
     * (via [fixesFor]) merged with caret/selection intentions from the [ActionProvider]s. This is what the
     * editor's lightbulb / Alt+Enter menu lists. The order is stable for a given (file, current text,
     * range), so a host may round-trip an action by its list index. Cheap: it uses the last-published
     * diagnostics ([diagnostics]) and the syntax tree, and does not force a fresh binding analysis.
     */
    suspend fun editorActionsAt(file: VirtualFile, range: TextRange): List<QuickFix>

    /**
     * Compute (but do NOT apply) the edits of the action at [index] in [editorActionsAt]'s list for
     * (`file`, `range`). The host applies the returned edits to its live buffer (the editor round-trip,
     * like a block edit) so undo/redo and re-analysis follow the normal text path. Returns
     * [WorkspaceEdit.EMPTY] when [index] is out of range or the action yields nothing.
     */
    suspend fun computeActionEdits(file: VirtualFile, range: TextRange, index: Int): WorkspaceEdit

    /**
     * Compute [fix]'s edits against a fresh snapshot and apply them as one atomic write action, then
     * re-analyze the touched files. Returns the [WorkspaceEdit] that was applied (empty if it was
     * rejected as stale).
     */
    suspend fun apply(fix: QuickFix, ctx: FixContext): WorkspaceEdit

    /** Swap the active profile (enable/disable analyzers, override severities); triggers re-analysis. */
    fun configure(profile: AnalysisProfile)

    /** React to "the diagnostics for this file changed" — what the editor underlines + Problems view bind to. */
    fun addListener(listener: AnalysisListener): Disposable
}

/** Notified when a file's published diagnostic set changes (replace, not append — never leaves stale results). */
fun interface AnalysisListener {
    fun diagnosticsChanged(file: VirtualFile, diagnostics: List<Diagnostic>)
}

// ---------------------------------------------------------------------------
// Extension points. Built-ins and third-party plugins register here alike; gate third-party
// analyzers behind the plugin trust/permission model (they run in-process with file access).
// ---------------------------------------------------------------------------

/** FileAnalyzer / ProjectAnalyzer contributions. */
val ANALYZER_EP = ExtensionPoint<Analyzer>("platform.analyzer")

/** Code-keyed fix providers — attach actions to diagnostics by [Diagnostic.code], incl. the compiler's. */
val QUICK_FIX_PROVIDER_EP = ExtensionPoint<QuickFixProvider>("platform.quickFixProvider")

/** Position-keyed action providers — caret/selection intentions + refactorings (no diagnostic needed). */
val ACTION_PROVIDER_EP = ExtensionPoint<ActionProvider>("platform.actionProvider")

/** Non-analyzer diagnostic producers; the compiler backend is the built-in one. */
val DIAGNOSTIC_PROVIDER_EP = ExtensionPoint<DiagnosticProvider>("platform.diagnosticProvider")

package dev.ide.analysis.impl

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.Analyzer
import dev.ide.analysis.AnalysisListener
import dev.ide.analysis.AnalysisProfile
import dev.ide.analysis.AnalysisService
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.DiagnosticTag
import dev.ide.analysis.FileAnalyzer
import dev.ide.analysis.FileDiagnostics
import dev.ide.analysis.FixContext
import dev.ide.analysis.LintReport
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.ProjectAnalyzer
import dev.ide.analysis.ProjectDiagnosticSink
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.RelatedRange
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.platform.Disposable
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Per-tier debounce windows. Set all to 0 for synchronous/deterministic runs. */
data class SchedulerConfig(
    val syntaxDelayMs: Long = 50,
    val semanticDelayMs: Long = 300,
    val projectDelayMs: Long = 800,
)

/**
 * The diagnostics engine — one pipeline merging analyzer findings and compiler errors. It runs
 * [FileAnalyzer]s with a shared node-kind gate, the [DiagnosticProvider]s (the compiler) as a peer, the
 * [ProjectAnalyzer]s as a coalesced sweep, applies the [AnalysisProfile] + [SuppressionFilter], and
 * publishes a version-gated set via [PublishedState] to [AnalysisListener]s. Quick-fixes apply through
 * the [AnalysisEnvironment] and trigger re-analysis.
 *
 * Background passes are launched on [scope] (so the host controls the dispatcher / lifecycle);
 * [analyzeNow], [lint], and [apply] run in the caller's context.
 */
class AnalysisEngine(
    analyzers: List<Analyzer>,
    private val quickFixProviders: List<QuickFixProvider>,
    private val diagnosticProviders: List<DiagnosticProvider>,
    private val environment: AnalysisEnvironment,
    private val scope: CoroutineScope,
    initialProfile: AnalysisProfile = AnalysisProfile.DEFAULT,
    private val config: SchedulerConfig = SchedulerConfig(),
    // Position-keyed code-action providers (caret/selection intentions). Last + defaulted so the positional
    // test constructor (… , config) keeps compiling; production wires it by name.
    private val actionProviders: List<ActionProvider> = emptyList(),
) : AnalysisService {

    private val fileAnalyzers: List<FileAnalyzer> = analyzers.filterIsInstance<FileAnalyzer>()
    private val projectAnalyzers: List<ProjectAnalyzer> = analyzers.filterIsInstance<ProjectAnalyzer>()

    @Volatile
    private var profile: AnalysisProfile = initialProfile

    private val published = PublishedState()
    private val listeners = CopyOnWriteArrayList<AnalysisListener>()

    private val fileJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var projectJob: Job? = null

    // ---------------------------------------------------------------------- AnalysisService

    override fun diagnostics(file: VirtualFile): List<Diagnostic> = published.merged(file)

    override suspend fun analyzeNow(file: VirtualFile): List<Diagnostic> {
        val target = environment.targetFor(file, needsBindings = fileNeedsBindings(environment.languageOf(file)))
        if (target == null) {
            if (published.clear(file)) notify(file)
            return emptyList()
        }
        runSyntax(target)
        runSemantic(target)
        runCompiler(target)
        return published.merged(file)
    }

    override suspend fun lint(scope: ProjectAnalysisScope): LintReport {
        val perFile = LinkedHashMap<String, MutableEntry>()

        for (file in scope.files()) {
            scope.checkCanceled()
            val target = scope.targetFor(file)
            val raw = ArrayList<Diagnostic>()
            raw += collect(target, fileAnalyzers.filter { isEnabled(it) && matchesLanguage(it, environment.languageOf(file)) })
            for (provider in diagnosticProviders) raw += provider.diagnose(target)
            val kept = SuppressionFilter.from(target.parsed).retain(raw)
            if (kept.isNotEmpty()) perFile.getOrPut(file.path) { MutableEntry(file) }.diagnostics += kept
        }

        for (analyzer in projectAnalyzers.filter(::isEnabled)) {
            scope.checkCanceled()
            analyzer.analyze(scope, lintSink(analyzer, perFile))
        }
        return LintReport(perFile.values.map { FileDiagnostics(it.file, it.diagnostics) })
    }

    override fun fixesFor(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> =
        diagnostic.fixes + providerFixes(diagnostic, target)

    override suspend fun editorActionsAt(file: VirtualFile, range: TextRange): List<QuickFix> {
        // A syntax-only target is enough: intentions navigate the structure (no bindings), and quick-fixes
        // read the already-published diagnostics. So opening the menu never pays a fresh binding analysis.
        val target = environment.targetFor(file, needsBindings = false) ?: return emptyList()
        return actionsFor(target, range)
    }

    override suspend fun computeActionEdits(file: VirtualFile, range: TextRange, index: Int): WorkspaceEdit {
        val target = environment.targetFor(file, needsBindings = false) ?: return WorkspaceEdit.EMPTY
        // Re-derive the same (stable-ordered) list and compute the chosen action's edits against this target.
        val action = actionsFor(target, range).getOrNull(index) ?: return WorkspaceEdit.EMPTY
        return runCatching { action.computeEdits(SimpleFixContext(target)) }.getOrDefault(WorkspaceEdit.EMPTY)
    }

    /**
     * The unified action list at [range]: quick-fixes for the published diagnostics overlapping [range]
     * (point-inclusive, so a bare caret on a squiggle still lists its fixes) + the [ActionProvider]
     * intentions for the file's language. Order is deterministic for a (target, range) so a host can
     * round-trip by index.
     */
    private fun actionsFor(target: AnalysisTarget, range: TextRange): List<QuickFix> {
        val lang = environment.languageOf(target.file)
        val fixes = diagnostics(target.file)
            .filter { overlaps(it.range, range) }
            .flatMap { fixesFor(it, target) }
        val intentions = actionProviders
            .filter { lang == null || lang in it.languages }
            .flatMap { runCatching { it.actions(target, range) }.getOrDefault(emptyList()) }
        return fixes + intentions
    }

    /** Point-inclusive overlap (treats a caret — an empty range — as touching a diagnostic it sits on). */
    private fun overlaps(a: TextRange, b: TextRange): Boolean = a.start <= b.end && b.start <= a.end

    /** Minimal [FixContext] over a freshly-built [target] (the editor round-trip recomputes per call). */
    private class SimpleFixContext(override val target: AnalysisTarget) : FixContext {
        override fun checkCanceled() = target.checkCanceled()
    }

    override suspend fun apply(fix: QuickFix, ctx: FixContext): WorkspaceEdit {
        val edit = fix.computeEdits(ctx)
        if (edit.isEmpty) return WorkspaceEdit.EMPTY
        val applied = environment.applyEdit(edit)
        // Re-analyze every touched file against its fresh content.
        for (file in applied.files) analyzeNow(file)
        return applied
    }

    override fun configure(profile: AnalysisProfile) {
        this.profile = profile
        // The engine does not retain the open-file set; the host re-triggers analysis (analyzeNow /
        // fileChanged) for visible files after a profile change.
    }

    override fun addListener(listener: AnalysisListener): Disposable {
        listeners.add(listener)
        return Disposable { listeners.remove(listener) }
    }

    // ---------------------------------------------------------------------- scheduler

    /**
     * Feed an edit into the scheduler. SYNTAX runs almost immediately; SEMANTIC + the compiler settle
     * after a typing pause; a coalesced PROJECT sweep runs later. A newer edit cancels the superseded
     * file pass and re-coalesces the project sweep.
     */
    fun fileChanged(file: VirtualFile) {
        val key = file.path
        fileJobs.remove(key)?.cancel()
        fileJobs[key] = scope.launch {
            delay(config.syntaxDelayMs)
            // The SYNTAX pass needs only the syntax tree (cheap); the later SEMANTIC pass gets bindings iff a
            // SEMANTIC+ analyzer is enabled for the language — so a syntactic file never pays binding cost.
            environment.targetFor(file, needsBindings = false)?.let { runSyntax(it) } ?: return@launch
            delay((config.semanticDelayMs - config.syntaxDelayMs).coerceAtLeast(0))
            val target = environment.targetFor(file, needsBindings = fileNeedsBindings(environment.languageOf(file))) ?: return@launch
            runSemantic(target)
            runCompiler(target)
        }
        projectJob?.cancel()
        projectJob = scope.launch {
            delay(config.projectDelayMs)
            runProjectPass()
        }
    }

    /** Cancel all in-flight passes (call on shutdown / project close). */
    fun dispose() {
        fileJobs.values.forEach(Job::cancel)
        fileJobs.clear()
        projectJob?.cancel()
    }

    // ---------------------------------------------------------------------- passes

    private fun runSyntax(target: AnalysisTarget) =
        recordFileBucket(target, PublishedState.Bucket.SYNTAX, AnalyzerTier.SYNTAX)

    private fun runSemantic(target: AnalysisTarget) =
        recordFileBucket(target, PublishedState.Bucket.SEMANTIC, AnalyzerTier.SEMANTIC)

    private suspend fun runCompiler(target: AnalysisTarget) {
        if (diagnosticProviders.isEmpty()) return
        val raw = ArrayList<Diagnostic>()
        for (provider in diagnosticProviders) raw += provider.diagnose(target)
        record(target, PublishedState.Bucket.COMPILER, raw)
    }

    private fun recordFileBucket(target: AnalysisTarget, bucket: PublishedState.Bucket, tier: AnalyzerTier) {
        val lang = environment.languageOf(target.file)
        val applicable = fileAnalyzers.filter { it.tier == tier && isEnabled(it) && matchesLanguage(it, lang) }
        record(target, bucket, collect(target, applicable))
    }

    private fun record(target: AnalysisTarget, bucket: PublishedState.Bucket, raw: List<Diagnostic>) {
        val kept = SuppressionFilter.from(target.parsed).retain(raw)
        if (published.record(target.file, target.documentVersion, bucket, kept)) notify(target.file)
    }

    /**
     * Run [analyzers] over [target] in one shared DOM traversal: a single pass collects the node
     * kinds present in the file, then each analyzer is invoked only if its [FileAnalyzer.interestedIn]
     * is null (whole-file) or intersects those kinds — N analyzers, one walk.
     */
    private fun collect(target: AnalysisTarget, analyzers: List<FileAnalyzer>): List<Diagnostic> {
        if (analyzers.isEmpty()) return emptyList()
        val present = target.parsed.nodesIn(target.parsed.range).mapTo(HashSet()) { it.kind }
        val out = ArrayList<Diagnostic>()
        for (analyzer in analyzers) {
            val interested = analyzer.interestedIn
            if (interested != null && interested.none { it in present }) continue
            target.checkCanceled()
            analyzer.analyze(target, AnalyzerSink(analyzer, profile, out))
        }
        return out
    }

    private suspend fun runProjectPass() {
        val enabled = projectAnalyzers.filter(::isEnabled)
        if (enabled.isEmpty() && lastProjectByPath.isEmpty()) return
        val pscope = environment.projectScope()
        val perFile = LinkedHashMap<String, MutableEntry>()
        for (analyzer in enabled) {
            pscope.checkCanceled()
            analyzer.analyze(pscope, lintSink(analyzer, perFile))
        }
        // Publish each reported file's PROJECT bucket (suppression-filtered).
        for (entry in perFile.values) {
            val kept = runCatching { SuppressionFilter.from(pscope.targetFor(entry.file).parsed) }
                .getOrNull()?.retain(entry.diagnostics) ?: entry.diagnostics
            if (published.record(entry.file, 0L, PublishedState.Bucket.PROJECT, kept)) notify(entry.file)
        }
        // Clear PROJECT diagnostics for files that carried them last sweep but were not reported this time.
        for ((path, file) in lastProjectByPath) {
            if (path !in perFile.keys && published.record(file, 0L, PublishedState.Bucket.PROJECT, emptyList())) {
                notify(file)
            }
        }
        lastProjectByPath = perFile.values.associate { it.file.path to it.file }
    }

    /** Files that carried project-tier diagnostics in the previous sweep (path → file), for stale-clearing. */
    @Volatile private var lastProjectByPath: Map<String, VirtualFile> = emptyMap()

    // ---------------------------------------------------------------------- helpers

    private fun providerFixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val code = diagnostic.code ?: return emptyList()
        return quickFixProviders.filter { code in it.forCodes }.flatMap { it.fixes(diagnostic, target) }
    }

    private fun notify(file: VirtualFile) {
        val current = published.merged(file)
        for (listener in listeners) listener.diagnosticsChanged(file, current)
    }

    private fun isEnabled(analyzer: Analyzer): Boolean = profile.isEnabled(analyzer.id)

    /**
     * Whether a file in [language] needs a binding-resolved analysis tree this run: true iff an enabled
     * file analyzer above the SYNTAX tier applies. This is the tier gate — a file with only SYNTAX
     * analyzers (plus the compiler, which resolves its own diagnostics) stays on the cheap syntax-only tree.
     */
    private fun fileNeedsBindings(language: LanguageId?): Boolean =
        fileAnalyzers.any { it.tier != AnalyzerTier.SYNTAX && isEnabled(it) && matchesLanguage(it, language) }

    private fun matchesLanguage(analyzer: Analyzer, language: LanguageId?): Boolean =
        language == null || language in analyzer.languages

    private fun lintSink(analyzer: Analyzer, into: MutableMap<String, MutableEntry>) =
        object : ProjectDiagnosticSink {
            override fun report(
                file: VirtualFile,
                range: TextRange,
                severity: Severity,
                message: String,
                code: String?,
                fixes: List<QuickFix>,
                tags: Set<DiagnosticTag>,
                related: List<RelatedRange>,
            ) {
                into.getOrPut(file.path) { MutableEntry(file) }.diagnostics +=
                    Diagnostic(range, severityFor(analyzer, severity), message, DiagnosticSource.Analyzer(analyzer.id), code, fixes, tags, related)
            }
        }

    private fun severityFor(analyzer: Analyzer, reported: Severity): Severity =
        profile.severityOverrides[analyzer.id] ?: reported

    private class MutableEntry(val file: VirtualFile) {
        val diagnostics = ArrayList<Diagnostic>()
    }

    /** Stamps the source + applies the profile's severity override for one analyzer's reports. */
    private inner class AnalyzerSink(
        private val analyzer: Analyzer,
        private val activeProfile: AnalysisProfile,
        private val out: MutableList<Diagnostic>,
    ) : DiagnosticSink {
        override fun report(
            range: TextRange,
            severity: Severity,
            message: String,
            code: String?,
            fixes: List<QuickFix>,
            tags: Set<DiagnosticTag>,
            related: List<RelatedRange>,
        ) {
            val effective = activeProfile.severityOverrides[analyzer.id] ?: severity
            out += Diagnostic(range, effective, message, DiagnosticSource.Analyzer(analyzer.id), code, fixes, tags, related)
        }
    }
}

package dev.ide.analysis.impl

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.Analyzer
import dev.ide.analysis.AnalysisListener
import dev.ide.analysis.AnalysisProfile
import dev.ide.analysis.AnalysisService
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.CaretSnapshot
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.DiagnosticTag
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FileAnalyzer
import dev.ide.analysis.FileDiagnostics
import dev.ide.analysis.FixContext
import dev.ide.analysis.LintReport
import dev.ide.analysis.NodeIndex
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.ProjectAnalyzer
import dev.ide.analysis.ProjectDiagnosticSink
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.RelatedRange
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.platform.Disposable
import dev.ide.platform.EngineCanceledException
import dev.ide.platform.PluginId
import dev.ide.platform.log.Log
import dev.ide.platform.log.Logger
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Where the engine reports a third-party analyzer misbehaving; records carry the plugin as their source. */
private const val LOG_TAG = "ide.analysis"

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
    /**
     * The analyzers that came from an installed (third-party) plugin, each mapped to the plugin that
     * contributed it; an analyzer absent from this map is one of the IDE's own. The engine treats the two
     * differently on purpose, the same way plugin loading does (a built-in that throws is our bug and is
     * left to fail; an installed one costs the user that plugin, not the IDE): an external analyzer runs
     * last in its tier, publishes into its own bucket, is isolated from its own exceptions, and is held to
     * [budget] by the watchdog.
     */
    private val externalAnalyzers: Map<AnalyzerId, PluginId> = emptyMap(),
    budget: AnalyzerBudget = AnalyzerBudget.DEFAULT,
    /** Source of the watchdog's clock, in nanoseconds; injectable so tests need no real time to pass. */
    private val nanoTime: () -> Long = System::nanoTime,
) : AnalysisService {

    private val fileAnalyzers: List<FileAnalyzer> = analyzers.filterIsInstance<FileAnalyzer>()
    private val projectAnalyzers: List<ProjectAnalyzer> = analyzers.filterIsInstance<ProjectAnalyzer>()

    private val watchdog = AnalyzerWatchdog(budget) { report ->
        loggerFor(report.plugin).warn(
            when (report.cause) {
                QuarantinedAnalyzer.Cause.SLOW ->
                    "'${report.displayName}' took ${report.tookMs}ms per pass (budget ${report.budgetMs}ms) and was " +
                        "turned off for this session; re-enable it in the inspection settings"
                QuarantinedAnalyzer.Cause.FAILING ->
                    "'${report.displayName}' failed repeatedly and was turned off for this session; " +
                        "re-enable it in the inspection settings"
            }
        )
    }

    /** Loggers attributed to the contributing plugin, so the Logs viewer can filter by it. */
    private val pluginLoggers = ConcurrentHashMap<String, Logger>()

    private fun loggerFor(plugin: PluginId): Logger =
        pluginLoggers.getOrPut(plugin.value) { Log.logger(LOG_TAG, plugin.value.ifEmpty { null }) }

    /**
     * The third-party analyzers the watchdog has taken off the pass this session, oldest first. A host
     * surfaces these so a plugin that degrades the editor is attributable rather than anonymous; the list
     * empties on [configure].
     */
    val quarantinedAnalyzers: List<QuarantinedAnalyzer> get() = watchdog.quarantined.toList()

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
        val suppression = Suppression(target.parsed)
        runSyntax(target, suppression)
        runSemantic(target, suppression)
        runCompiler(target, suppression)
        return published.merged(file)
    }

    override suspend fun lint(scope: ProjectAnalysisScope): LintReport {
        val perFile = LinkedHashMap<String, MutableEntry>()

        for (file in scope.files()) {
            scope.checkCanceled()
            val target = scope.targetFor(file)
            val raw = ArrayList<Diagnostic>()
            raw += collect(
                target,
                fileAnalyzers.filter { isEnabled(it) && matchesLanguage(it, environment.languageOf(file)) },
                watch = false,
            )
            for (provider in diagnosticProviders) if (providerMatches(provider.languages, target.file)) raw += provider.diagnose(target)
            // As in a file pass: a file that reported nothing is not worth scanning for suppressions, and
            // in a whole-project sweep most files report nothing.
            val kept = if (raw.isEmpty()) raw else SuppressionFilter.from(target.parsed).retain(raw)
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
     *
     * The two halves overlap by design: an intention is offered wherever the user goes looking for it,
     * while a fix is anchored on the diagnostic, so on the error itself the same action arrives twice
     * (e.g. Kotlin's "Implement members", offered anywhere in the class body *and* on the
     * `abstractNotImplemented` error). The menu shows one row per title: an intention whose title is
     * already listed is dropped, keeping the fix, since two rows a user cannot tell apart are noise.
     * Fixes are never dropped: a title repeated across two diagnostics in a selection is two edits.
     */
    private fun actionsFor(target: AnalysisTarget, range: TextRange): List<QuickFix> {
        val lang = environment.languageOf(target.file)
        val fixes = diagnostics(target.file)
            .filter { overlaps(it.range, range) }
            .flatMap { fixesFor(it, target) }
        val applicable = actionProviders.filter { lang == null || lang in it.languages }
        if (applicable.isEmpty()) return fixes
        // Resolve the caret's place in the tree ONCE and share it: every provider's first move is to find
        // the node at the caret and walk up from it, and that walk is identical for all of them.
        val ctx = EditorActionContext.of(target, range, lang)
        val titles = fixes.mapTo(HashSet()) { it.title }
        val intentions = applicable
            .flatMap { runCatching { it.actions(ctx) }.getOrDefault(emptyList()) }
            .filter { titles.add(it.title) }
        return fixes + intentions
    }

    override suspend fun caretSnapshotAt(file: VirtualFile, offset: Int): CaretSnapshot? {
        val target = environment.targetFor(file, needsBindings = false) ?: return null
        return runCatching { CaretSnapshot.of(target.parsed, offset, environment.languageOf(file)) }.getOrNull()
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
        // A profile change is the user revisiting which checks run, so it is also their way back: every
        // quarantine and every strike is released here, and an analyzer the watchdog dropped gets a fresh
        // chance to behave (after a plugin update, say). It re-earns the quarantine soon enough if not.
        watchdog.clear()
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
            // A fresh target: its own parse, so its own suppression state (shared by the two buckets below).
            val suppression = Suppression(target.parsed)
            runSemantic(target, suppression)
            runCompiler(target, suppression)
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

    private fun runSyntax(target: AnalysisTarget, suppression: Suppression = Suppression(target.parsed)) =
        recordFileBucket(target, AnalyzerTier.SYNTAX, suppression)

    private fun runSemantic(target: AnalysisTarget, suppression: Suppression = Suppression(target.parsed)) =
        recordFileBucket(target, AnalyzerTier.SEMANTIC, suppression)

    private suspend fun runCompiler(target: AnalysisTarget, suppression: Suppression = Suppression(target.parsed)) {
        if (diagnosticProviders.isEmpty()) return
        val raw = ArrayList<Diagnostic>()
        for (provider in diagnosticProviders) if (providerMatches(provider.languages, target.file)) raw += provider.diagnose(target)
        record(target, PublishedState.Bucket.COMPILER, raw, suppression)
    }

    /**
     * Run one tier over [target] as two halves, the IDE's own analyzers and the installed plugins', each
     * published as it finishes.
     *
     * The split is what keeps a slow plugin off everyone else's latency. A file pass is sequential on the
     * single engine thread, and a bucket is only published once every analyzer in it has returned, so under
     * one list the host's findings — and the compiler pass queued after them — waited out whatever the
     * slowest plugin did. Built-ins now go first and land at their own speed; the external half publishes
     * separately, late if it must.
     *
     * Both halves are recorded even when empty: an analyzer disabled (or quarantined) since the last pass
     * at this same document version must have its previous findings cleared, and only a record does that.
     */
    private fun recordFileBucket(target: AnalysisTarget, tier: AnalyzerTier, suppression: Suppression) {
        val lang = environment.languageOf(target.file)
        val applicable = fileAnalyzers.filter { it.tier == tier && isEnabled(it) && matchesLanguage(it, lang) }
        val (external, own) = applicable.partition { it.id in externalAnalyzers }
        record(target, bucketFor(tier, external = false), collect(target, own), suppression)
        record(target, bucketFor(tier, external = true), collect(target, external), suppression)
    }

    private fun bucketFor(tier: AnalyzerTier, external: Boolean): PublishedState.Bucket = when (tier) {
        AnalyzerTier.SYNTAX ->
            if (external) PublishedState.Bucket.SYNTAX_EXTERNAL else PublishedState.Bucket.SYNTAX
        AnalyzerTier.SEMANTIC ->
            if (external) PublishedState.Bucket.SEMANTIC_EXTERNAL else PublishedState.Bucket.SEMANTIC
        // PROJECT-tier analyzers never take this path (they publish through the coalesced sweep).
        AnalyzerTier.PROJECT -> PublishedState.Bucket.PROJECT
    }

    private fun record(
        target: AnalysisTarget, bucket: PublishedState.Bucket, raw: List<Diagnostic>, suppression: Suppression,
    ) {
        // Nothing to filter: skip the filter build entirely. Most buckets on most files are empty, and
        // parsing the file's suppression directives to filter an empty list is the pass's purest waste.
        val kept = if (raw.isEmpty()) raw else suppression.filter().retain(raw)
        if (published.record(target.file, target.documentVersion, bucket, kept)) notify(target.file)
    }

    /**
     * One pass's [SuppressionFilter], built at most once and only if some bucket actually reported.
     * The filter is derived purely from the file's text, so the three buckets of a pass share it rather
     * than each re-scanning the source for `@Suppress` / `// noinspection` directives.
     */
    private class Suppression(private val parsed: ParsedFile) {
        private var filter: SuppressionFilter? = null
        fun filter(): SuppressionFilter = filter ?: SuppressionFilter.from(parsed).also { filter = it }
    }

    /**
     * Run [analyzers] over [target] in one shared DOM traversal: a single [NodeIndex] groups the file's
     * nodes by kind, each analyzer is invoked only if its [FileAnalyzer.interestedIn] is null
     * (whole-file) or present in the index, and a node-keyed one is handed the nodes it asked for
     * instead of walking again: N analyzers, one walk.
     *
     * The index defers its traversal to the first query, so a pass whose analyzers are all whole-file
     * ones (every ide-core analyzer, hence every Kotlin file) never walks the DOM at all.
     */
    private fun collect(
        target: AnalysisTarget,
        analyzers: List<FileAnalyzer>,
        /** Whether a third-party analyzer's time is charged to the watchdog: true for the edit-driven passes
         *  it protects, false for a batch lint, whose budget is a user's patience with a whole-project sweep
         *  rather than the gap between two keystrokes. */
        watch: Boolean = true,
    ): List<Diagnostic> {
        if (analyzers.isEmpty()) return emptyList()
        val nodes = NodeIndex.over(target.parsed)
        val out = ArrayList<Diagnostic>()
        for (analyzer in analyzers) {
            val interested = analyzer.interestedIn
            if (interested != null && interested.none { it in nodes }) continue
            target.checkCanceled()
            val plugin = externalAnalyzers[analyzer.id]
            // The IDE's own analyzers run exactly as before: unmeasured, and free to fail the pass, because
            // a built-in throwing is a bug we want surfaced, not absorbed.
            if (plugin == null) analyzer.analyze(target, AnalyzerSink(analyzer, profile, out), nodes)
            else runExternal(analyzer, plugin, target, nodes, out, watch)
        }
        return out
    }

    /**
     * Run one installed plugin's [analyzer], contained: its exceptions cost the user that check rather than
     * the pass, and the time it took is charged to the watchdog, which decides whether it runs again.
     *
     * Containment is all the engine can offer *this* pass. The call is plain synchronous code on the engine
     * thread that need never poll [AnalysisTarget.checkCanceled], so there is no safe way to cut it short
     * once it has started — the protection is for the next keystroke, not this one.
     */
    private fun runExternal(
        analyzer: FileAnalyzer,
        plugin: PluginId,
        target: AnalysisTarget,
        nodes: NodeIndex,
        out: MutableList<Diagnostic>,
        watch: Boolean,
    ) {
        val reportedBefore = out.size
        val started = nanoTime()
        try {
            analyzer.analyze(target, AnalyzerSink(analyzer, profile, out), nodes)
        } catch (e: VirtualMachineError) {
            // Out of stack or heap says the CALLER is exhausted, never that this analyzer is faulty — and
            // swallowing one to log it is what turns a survivable error into a native crash on ART.
            throw e
        } catch (e: EngineCanceledException) {
            throw e // a higher-priority editor call preempted the pass: control flow, not a failure
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Drop whatever it reported before throwing: half an analyzer's findings are not a result.
            while (out.size > reportedBefore) out.removeAt(out.size - 1)
            loggerFor(plugin).warn("analyzer '${analyzer.id.value}' failed on ${target.file.path}", t)
            if (watch) watchdog.failed(analyzer, plugin)
            return
        }
        if (watch) watchdog.completed(analyzer, plugin, (nanoTime() - started) / 1_000_000)
    }

    /**
     * Whether a project sweep has anything to do: an enabled [ProjectAnalyzer] to run, or findings from the
     * previous sweep still to clear (the analyzer was since disabled). A host checks this before paying for
     * a trip to its analysis thread.
     */
    val hasProjectWork: Boolean
        get() = projectAnalyzers.any(::isEnabled) || lastProjectByPath.isNotEmpty()

    /**
     * Run the [ProjectAnalyzer]s once over [AnalysisEnvironment.projectScope] and publish what they report.
     *
     * The self-scheduling [fileChanged] path launches its sweep on the engine's [scope]; a host whose
     * language backends are confined to one thread calls this instead, from that thread, after its own
     * debounce. Returns the files whose published diagnostics changed, so the host can refresh them.
     */
    suspend fun analyzeProject(): List<VirtualFile> = runProjectPass()

    private suspend fun runProjectPass(): List<VirtualFile> {
        val enabled = projectAnalyzers.filter(::isEnabled)
        if (enabled.isEmpty() && lastProjectByPath.isEmpty()) return emptyList()
        val pscope = environment.projectScope()
        val perFile = LinkedHashMap<String, MutableEntry>()
        for (analyzer in enabled) {
            pscope.checkCanceled()
            val plugin = externalAnalyzers[analyzer.id]
            if (plugin == null) analyzer.analyze(pscope, lintSink(analyzer, perFile))
            else runExternalProject(analyzer, plugin, pscope, perFile)
        }
        val changed = ArrayList<VirtualFile>()
        val previous = lastProjectByPath
        val sweep = HashMap<String, ProjectFindings>()
        // Publish each reported file's PROJECT bucket (suppression-filtered). A file counts as changed only
        // when what the user would see differs: a sweep that re-reports the same findings (with freshly
        // built fix objects) must not make the host refresh, or its refresh would schedule the next sweep.
        for (entry in perFile.values) {
            val kept = runCatching { SuppressionFilter.from(pscope.targetFor(entry.file).parsed) }
                .getOrNull()?.retain(entry.diagnostics) ?: entry.diagnostics
            if (kept.isEmpty()) continue // all suppressed: cleared below like a file nothing reported on
            val findings = ProjectFindings(entry.file, kept.map(::visibleKey))
            sweep[entry.file.path] = findings
            published.record(entry.file, 0L, PublishedState.Bucket.PROJECT, kept)
            if (previous[entry.file.path]?.keys != findings.keys) changed += entry.file
        }
        // Clear PROJECT diagnostics for files that carried them last sweep but were not reported this time.
        for ((path, last) in previous) {
            if (path !in sweep && published.record(last.file, 0L, PublishedState.Bucket.PROJECT, emptyList())) {
                changed += last.file
            }
        }
        lastProjectByPath = sweep
        for (file in changed) notify(file)
        return changed
    }

    /** What a sweep published for one file, reduced to what the editor shows (see [visibleKey]). */
    private class ProjectFindings(val file: VirtualFile, val keys: List<Any>)

    /** A diagnostic minus its fixes, which are rebuilt on every run and compare by identity. */
    private fun visibleKey(d: Diagnostic): Any = d.copy(fixes = emptyList())

    /**
     * One installed plugin's project [analyzer], contained the way [runExternal] contains a file analyzer:
     * a throw costs the user that check (its partial findings are dropped) and counts toward the watchdog's
     * failure limit. It is not held to the per-pass time budget, which is sized for a single file.
     */
    private suspend fun runExternalProject(
        analyzer: ProjectAnalyzer,
        plugin: PluginId,
        pscope: ProjectAnalysisScope,
        into: MutableMap<String, MutableEntry>,
    ) {
        val own = LinkedHashMap<String, MutableEntry>()
        try {
            analyzer.analyze(pscope, lintSink(analyzer, own))
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: EngineCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            loggerFor(plugin).warn("project analyzer '${analyzer.id.value}' failed", t)
            watchdog.failed(analyzer, plugin)
            return
        }
        for ((path, entry) in own) into.getOrPut(path) { MutableEntry(entry.file) }.diagnostics += entry.diagnostics
    }

    /** What the previous sweep published, by path: for stale-clearing and for telling a real change apart. */
    @Volatile private var lastProjectByPath: Map<String, ProjectFindings> = emptyMap()

    // ---------------------------------------------------------------------- helpers

    private fun providerFixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val code = diagnostic.code ?: return emptyList()
        return quickFixProviders.filter { code in it.forCodes && providerMatches(it.languages, target.file) }
            .flatMap { it.fixes(diagnostic, target) }
    }

    private fun notify(file: VirtualFile) {
        val current = published.merged(file)
        for (listener in listeners) listener.diagnosticsChanged(file, current)
    }

    /**
     * Whether [analyzer] runs at all: the user's profile decides first, then the watchdog's session-local
     * quarantine (a third-party analyzer that would not keep to its time budget, or kept throwing). The
     * quarantine is checked here so it also reaches [fileNeedsBindings] — a dropped SEMANTIC analyzer must
     * stop forcing the expensive binding-resolved tree too.
     */
    private fun isEnabled(analyzer: Analyzer): Boolean =
        profile.isEnabled(analyzer.id) && !watchdog.isQuarantined(analyzer.id)

    /**
     * Whether a file in [language] needs a binding-resolved analysis tree this run: true iff an enabled
     * file analyzer above the SYNTAX tier applies. This is the tier gate — a file with only SYNTAX
     * analyzers (plus the compiler, which resolves its own diagnostics) stays on the cheap syntax-only tree.
     */
    private fun fileNeedsBindings(language: LanguageId?): Boolean =
        fileAnalyzers.any { it.tier != AnalyzerTier.SYNTAX && isEnabled(it) && matchesLanguage(it, language) }

    private fun matchesLanguage(analyzer: Analyzer, language: LanguageId?): Boolean =
        language == null || language in analyzer.languages

    /**
     * Language gate for [DiagnosticProvider]/[QuickFixProvider], which (unlike [Analyzer]) use **empty =
     * all languages**: a provider with no declared languages runs everywhere, otherwise only for files in
     * one of its languages. Keeps a language-specific provider (the JDT compiler, the Kotlin/XML analyzers)
     * off foreign files now that every language flows through the one pipeline.
     */
    private fun providerMatches(languages: Set<LanguageId>, file: VirtualFile): Boolean {
        if (languages.isEmpty()) return true
        val lang = environment.languageOf(file) ?: return true
        return lang in languages
    }

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

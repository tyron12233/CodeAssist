package dev.ide.core

import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ActionService
import dev.ide.ui.backend.BlockService
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.DependencyService
import dev.ide.ui.backend.DiagnosticsService
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.FileService
import dev.ide.ui.backend.ModuleService
import dev.ide.ui.backend.SigningService
import dev.ide.ui.backend.PreviewService
import dev.ide.ui.backend.LearnService
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.SdkService
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.SearchService
import dev.ide.ui.backend.SettingsService
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiError
import dev.ide.analytics.AnalyticsService
import dev.ide.core.backend.ActionBackend
import dev.ide.core.backend.BlockBackend
import dev.ide.core.backend.BuildBackend
import dev.ide.core.backend.DependencyBackend
import dev.ide.core.backend.DiagnosticsBackend
import dev.ide.core.backend.EditorBackend
import dev.ide.core.backend.stackTraceString
import dev.ide.core.backend.timedPass
import dev.ide.core.backend.FileBackend
import dev.ide.core.backend.ModuleBackend
import dev.ide.core.backend.PreviewBackend
import dev.ide.core.backend.LearnBackend
import dev.ide.core.backend.ProjectBackend
import dev.ide.core.backend.StoreBackend
import dev.ide.core.backend.SdkBackend
import dev.ide.core.backend.SearchBackend
import dev.ide.core.backend.SettingsBackend
import dev.ide.core.backend.SigningBackend
import dev.ide.platform.EngineCanceledException
import dev.ide.platform.EngineScheduler
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogLevel
import dev.ide.platform.log.LogSink
import dev.ide.preview.LayoutPreviewBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Paths

/**
 * Implements the UI's [IdeBackend] port over the JVM [IdeServices] facade.
 *
 * When a [ProjectManager] is supplied the backend is **project-aware**: `createProject`/`openProject`
 * swap the active engine and bump [projectEpoch], on which the UI keys its per-project state. The
 * flow getters ([buildState]/[indexStatus]/[depsState]) re-point to the live engine on each epoch, so a
 * project swap updates them automatically. With no manager the backend is single-project (the pre-existing
 * behaviour; create/open are unsupported).
 *
 * [initial] may be null: the backend then starts with **no project open** (the picker is shown, since
 * [projectEpoch] stays 0) and the first engine is created lazily on `openProject`/`createProject`. The
 * editor-screen call sites are only reachable once a project is open, so they go through the non-null
 * [services] accessor; the few surfaces reachable from the picker (the StateFlow getters, tab persistence,
 * close) tolerate a null engine.
 */
class IdeServicesBackend(
    initial: IdeServices? = null,
    override val manager: ProjectManager? = null,
    /**
     * Opt-in usage analytics. Defaults to the no-op service (desktop, or when no transport is configured);
     * the on-device host injects a [dev.ide.analytics.impl.DefaultAnalyticsService] backed by Supabase. The
     * backend gates it on the persisted consent preference — see [analyticsConsent]/[setAnalyticsConsent].
     */
    private val analytics: AnalyticsService = dev.ide.analytics.NoopAnalyticsService,
    /**
     * Host-injected factory for an out-of-process build runner (the `:build` daemon, supplied by
     * :ide-android). Null → in-process builds (desktop, or when the separate-process build is off), i.e.
     * each engine's own [IdeServices.buildRunner]. See docs/build-process-isolation.md.
     */
    private val buildRunnerFactory: ((IdeServices) -> BuildRunner)? = null,
) : IdeBackend, LayoutPreviewBackend, BackendContext {

    /** Per-engine build-runner cache: the chosen runner (remote daemon OR in-process) is decided once per
     *  engine and memoized, so [engineFlow]'s selector and the imperative methods always agree on the same
     *  instance, and the decision is stable for the project session. Weak-keyed so a closed project's runner
     *  (and any daemon binding) becomes collectible. */
    private val runnerCache = java.util.WeakHashMap<IdeServices, BuildRunner>()

    override fun buildRunnerFor(services: IdeServices): BuildRunner =
        synchronized(runnerCache) {
            runnerCache.getOrPut(services) {
                val factory = buildRunnerFactory
                if (factory != null && separateBuildProcessEnabled()) factory(services) else services.buildRunner
            }
        }

    /** The app-global "Build in a separate process" setting (default ON; see [BuiltInSettingsPages]). Read once
     *  per engine (the cache above freezes the choice), so toggling it applies on the next project open —
     *  which keeps the build-state flow and the build methods bound to one consistent runner. */
    private fun separateBuildProcessEnabled(): Boolean =
        manager?.preference("settings.${dev.ide.core.settings.BuiltInSettingsPages.BUILD_RUNTIME}.${dev.ide.core.settings.BuiltInSettingsPages.SEPARATE_PROCESS}")
            ?.toBooleanStrictOrNull() ?: true

    @Volatile
    private var activeServices: IdeServices? = initial

    /**
     * The active engine. Throws when accessed with no project open; only the editor-screen call sites use it,
     * and those are unreachable until a project is open ([projectEpoch] > 0 gates the editor UI). Picker-
     * reachable surfaces read [activeServices] directly and handle null.
     */
    override val services: IdeServices
        get() = activeServices ?: error("No project is open")

    override val servicesOrNull: IdeServices? get() = activeServices

    // The SDK manager + keystore registry are APPLICATION-scoped (one shared instance per app), so they're
    // reached through the project manager — available even with no project open, which is what lets the
    // picker's Settings & Tools hub drive them. The manager-less single-project path (tests) has no manager,
    // so it falls back to the active engine's (same instance, resolved from the engine's app container).
    override val sdkManager: SdkManagerService? get() = manager?.sdkManager() ?: activeServices?.sdkManager
    override val keystoreRegistry: dev.ide.android.support.tools.KeystoreRegistry?
        get() = manager?.keystoreRegistry() ?: activeServices?.keystoreRegistry

    /**
     * The thread the editor's language work (parse/complete/analyze/hints/actions/rename) runs on.
     *
     * Those calls reach the per-(module,language) [SourceAnalyzer]s, which hold mutable incremental-parser
     * and JDT-environment state and are NOT safe for concurrent use, and `IdeServices.runSync` takes no
     * lock — so they must stay serialized. They used to be serialized only incidentally, by all running on
     * the Compose main thread, which also meant every JDT call (tens to hundreds of ms on ART) blocked
     * typing: the editor stuttered whenever a debounced completion/analysis fired between keystrokes.
     *
     * Confining them to a single background thread keeps the serialization (one worker → never two
     * analyzer calls at once) while freeing the UI thread, so typing stays smooth no matter how slow a
     * given analysis is. `limitedParallelism(1)` borrows one worker from the shared Default pool (no
     * dedicated thread to close).
     */
    override val engineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * The priority scheduler the editor's engine calls run through (extracted to [EngineScheduler] so the
     * threading/scheduling policy is testable + instrumentable in isolation). Shares [engineDispatcher] with
     * the direct `withContext(engineDispatcher)` call sites below, so all engine work stays on the one worker.
     *
     *  1. [interactive] — completion: highest priority, preempts both background and preview.
     *  2. [background] — analysis/hints/semantic/folding/signature: preempts preview, preempted by interactive
     *     (throws [EngineCanceledException]; callers map it to a "skipped, retry next edit" result).
     *  3. [preview] — preview rendering/lowering: lowest priority, preempted by both; retries automatically.
     */
    private val scheduler = EngineScheduler(engineDispatcher)
    override suspend fun <T> interactive(block: suspend () -> T): T = logEditorFailures("completion") { scheduler.interactive(block = block) }
    override suspend fun <T> background(block: suspend () -> T): T = logEditorFailures("analysis") { scheduler.background(block = block) }
    override suspend fun <T> preview(block: suspend () -> T): T = logEditorFailures("preview") { scheduler.preview(block = block) }

    private val editorLog = Log.logger("ide.editor")

    /**
     * Records an unexpected failure of an editor engine call (completion/analysis/preview) into the logging
     * facade before rethrowing, so it shows up in the Logs viewer — the common "this feature does nothing"
     * case where an exception was previously swallowed silently upstream. Logged at WARN (not ERROR) so a
     * routine editor hiccup doesn't pop the critical-error dialog. Preemption (the priority scheduler
     * cancelling lower-priority work) and ordinary coroutine cancellation are normal control flow, not failures.
     */
    private suspend fun <T> logEditorFailures(lane: String, run: suspend () -> T): T =
        try {
            run()
        } catch (e: EngineCanceledException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            editorLog.warn("Editor $lane failed", t)
            throw t
        }

    private val _projectEpoch = MutableStateFlow(0)
    override val projectEpoch: StateFlow<Int> get() = _projectEpoch

    private val _fsEpoch = MutableStateFlow(0)
    override val fileSystemEpoch: StateFlow<Int> get() = _fsEpoch
    override fun bumpFileSystemEpoch() { _fsEpoch.value += 1 }

    /** Background scope for the analytics build/index watchers (see [init]); cancelled in [close]. */
    private val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Scope that keeps the epoch-keyed engine flows (build/index/deps/permission/sdk) alive; cancelled in [close]. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * A [StateFlow] that re-points to the live engine's [select] flow on every project swap (keyed on
     * [projectEpoch]) and yields [default] while no project is open. Lets the picker collect these app-wide
     * surfaces (notably the permission dialog) without an engine, and have them start working once one opens.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun <T> engineFlow(default: T, select: (IdeServices) -> StateFlow<T>): StateFlow<T> =
        _projectEpoch
            .flatMapLatest { activeServices?.let(select) ?: MutableStateFlow(default) }
            .stateIn(engineScope, SharingStarted.Eagerly, default)

    /** Aggregates per-keystroke latencies (completion/analysis) into periodic summary events. */
    private val perf = PerfSampler { name, props -> track(name, props) }
    override fun recordPerf(event: String, ms: Long) = perf.record(event, ms)

    // ---- critical-error surface (non-fatal dialog, fed by the logging facade) ----

    private val _errorEvents = MutableStateFlow<UiError?>(null)
    private val errorQueue = ArrayDeque<UiError>()
    private val errorIdSeq = AtomicInteger(0)
    private val errorLock = Any()

    /** Turns engine ERROR logs (caught failures) into the non-fatal dialog. Registered on [Log] in [init]. */
    private val errorDialogSink = LogSink { record ->
        val t = record.throwable
        if (record.level == LogLevel.ERROR && t != null) {
            showError(t.javaClass.simpleName.ifEmpty { "Error" }, record.message, stackTraceString(t))
        }
    }

    private val log = Log.logger("ide.backend")

    // The concern-segmented services (Stage 2 of the decomposition). Declared AFTER the shared state above
    // (engineDispatcher / scheduler / epochs / engineScope) because the extracted impls build their engine-
    // backed flows in their constructors via `engineFlow`, which reads that state — so it must already exist.
    override val files: FileService = FileBackend(this)
    override val editor: EditorService = EditorBackend(this)
    override val blocks: BlockService = BlockBackend(this)
    override val preview: PreviewService = PreviewBackend(this)
    override val search: SearchService = SearchBackend(this)
    override val build: BuildService = BuildBackend(this)
    override val deps: DependencyService = DependencyBackend(this)
    override val modules: ModuleService = ModuleBackend(this)
    override val signing: SigningService = SigningBackend(this)
    override val projects: ProjectService = ProjectBackend(this)
    override val store: StoreService = StoreBackend(this)
    // Held as the concrete type so the Compose preview host can reach its ide-core-only lesson-lowering methods
    // ([lowerLessonComposePreview]) that return an ide-core type the [LearnService] UI interface can't name.
    private val learnBackend = LearnBackend(this)
    override val learn: LearnService = learnBackend
    override val sdk: SdkService = SdkBackend(this)
    override val settings: SettingsService = SettingsBackend(this)
    override val actions: ActionService = ActionBackend(this)
    override val diagnostics: DiagnosticsService = DiagnosticsBackend(this)

    init {
        Log.addSink(errorDialogSink)

        // index_perf: time each index build (building → not building) and emit its duration. Low-volume
        // (once per build/reindex). Re-subscribes per project (collectLatest on the epoch).
        analyticsScope.launch {
            projectEpoch.collectLatest {
                val svc = activeServices ?: return@collectLatest
                var startNs = 0L
                var building = false
                svc.indexStatus.collectLatest { st ->
                    if (st.building && !building) { building = true; startNs = System.nanoTime() }
                    else if (!st.building && building) {
                        building = false
                        // Heap at index completion (Phase-0 build-isolation instrumentation) so the index
                        // phase's memory footprint is comparable to a build's peak across the fleet.
                        track(
                            dev.ide.analytics.Events.INDEX_PERF,
                            mapOf("duration_ms" to ((System.nanoTime() - startNs) / 1_000_000).toString()) + MemSample.now().props(),
                        )
                    }
                }
            }
        }
        // Emit build_result (the performance signal) when a build/run reaches a terminal status, watched off
        // the live buildState so it captures every trigger (Run button, task picker, android run). Re-subscribes
        // per project (collectLatest on the epoch) since a project swap re-points services.buildState. track()
        // no-ops while consent is absent, so this is harmless when analytics is off.
        analyticsScope.launch {
            projectEpoch.collectLatest {
                val svc = activeServices ?: return@collectLatest
                var prev = dev.ide.ui.backend.RunStatus.Idle
                svc.build.buildState.collectLatest { bs ->
                    val terminal = bs.status == dev.ide.ui.backend.RunStatus.Succeeded || bs.status == dev.ide.ui.backend.RunStatus.Failed
                    if (terminal && prev == dev.ide.ui.backend.RunStatus.Running) {
                        // Attach this build's heap peak (Phase-0 build-isolation instrumentation): the signal
                        // for whether a build/run is the dominant OOM driver vs. the project-open warm-up storm.
                        val peak = svc.build.lastBuildPeak
                        track(
                            dev.ide.analytics.Events.BUILD_RESULT,
                            mapOf(
                                "ok" to (bs.status == dev.ide.ui.backend.RunStatus.Succeeded).toString(),
                                "duration_ms" to bs.elapsedMs.toString(),
                                "steps" to bs.steps.size.toString(),
                            ) + (peak?.let {
                                mapOf(
                                    "peak_heap_mb" to it.usedMb.toString(),
                                    "min_headroom_mb" to it.headroomMb.toString(),
                                    "heap_max_mb" to it.maxMb.toString(),
                                )
                            } ?: emptyMap()),
                        )
                    }
                    prev = bs.status
                }
            }
        }
    }

    override val project: ProjectInfo
        get() = ProjectInfo(
            name = services.projectDisplayName(),
            rootPath = services.workspaceRoot.toString(),
            moduleCount = services.modules().size,
            compatibility = runCatching { services.isCompatibilityMode() }.getOrDefault(false),
        )

    // ---- Compose preview (LayoutPreviewBackend; aggregator-level, uses the preview lane) ----

    /** The lowered preview to render — lowest-priority engine work, preempted by analysis and completion,
     *  retries until the engine is free. Returns an ide-core type; on-device preview host calls this. */
    suspend fun lowerComposePreview(path: String, functionName: String, arity: Int, text: String): LoweredComposePreview? =
        timedPass("lower", path, { it?.program?.size ?: 0 }) {
            preview { services.lowerComposePreview(Paths.get(path), text, functionName, arity) }
        }

    /** Why [functionName] isn't interpretable yet (lowering diagnostics + offending source), for the preview
     *  panel's not-interpretable state. Lowest-priority engine work; preempted by analysis and completion. */
    suspend fun composePreviewDiagnostics(path: String, functionName: String, arity: Int, text: String): List<String> =
        preview { services.composePreviewDiagnostics(Paths.get(path), text, functionName, arity) }

    /** Whether [path]'s module can resolve library composables yet (see [IdeServices.composePreviewReady]).
     *  The preview host polls this to show a transient "Preparing" state (and retry) instead of latching a
     *  first-run (index still building) failure into a permanent "unresolved call" error. */
    suspend fun composePreviewReady(path: String): Boolean =
        preview { services.composePreviewReady(Paths.get(path)) }

    /** The project library inputs for the on-device Compose preview's `DexClassLoader` (see
     *  [IdeServices.composePreviewLibs]). Lowest-priority engine work; preempted by analysis and completion. */
    suspend fun composePreviewLibs(path: String): ComposePreviewLibs? =
        preview { services.composePreviewLibs(Paths.get(path)) }

    /** Lower a self-contained Learn-lesson Compose snippet [code] (with NO open project) through the Learn
     *  Compose scratch, for the preview host's `LessonPreview`. Rendering uses the bundled Compose runtime, so
     *  no `composePreviewLibs` is needed. Delegates to [LearnBackend], which resolves `androidx.compose.*` once. */
    suspend fun lowerLessonComposePreview(code: String): LoweredComposePreview? =
        learnBackend.lowerCompose(code)

    /** Why a Learn-lesson Compose snippet isn't interpretable yet (for the preview problem chip). */
    suspend fun lessonComposePreviewDiagnostics(code: String): List<String> =
        learnBackend.composeDiagnostics(code)

    /** Whether the hidden Learn Compose scratch can resolve library composables yet: the one-time
     *  `androidx.compose.*` download + attach may still be in flight on first run. The preview host polls this
     *  to show a transient "Preparing" state (and retry) instead of latching the first failed lower. */
    suspend fun lessonComposePreviewReady(): Boolean =
        learnBackend.composeReady()

    // The owned XML-layout preview (LayoutPreviewBackend); the preview host calls this directly. Runs on the
    // preview lane so the render (real-view dex-load + resource-context build + inflate/measure/draw, or the
    // owned engine pass) executes off the UI thread and is preempted by analysis and completion.
    override suspend fun layoutPreview(path: String, text: String, request: dev.ide.preview.PreviewRequest): dev.ide.preview.LayoutPreviewResult? =
        preview { services.layoutPreview(Paths.get(path), text, request) }

    // The Learn tab's standalone layout preview: an owned render of a self-contained lesson XML with NO open
    // project (the learner may be on the Learn tab with nothing open), so it deliberately does NOT go through
    // `services`. Uses an empty resource table + no theme; built-in + Material renderers do the rest. Off the
    // UI thread on the shared engine dispatcher (owned rendering is cheap, so no preview-lane priority needed).
    override suspend fun layoutPreviewStandalone(xml: String, request: dev.ide.preview.PreviewRequest): dev.ide.preview.LayoutPreviewResult? =
        withContext(Dispatchers.Default) { renderStandaloneLayout(xml, request) }

    // ---- usage analytics (opt-in) ----

    // --- usage analytics (opt-in) ---
    // Consent is persisted as a preference ("granted"/"denied"; absent = undecided → prompt). The injected
    // AnalyticsService does the collection; it no-ops while disabled, and revoking drops anything buffered.

    override fun analyticsAvailable(): Boolean = analytics !== dev.ide.analytics.NoopAnalyticsService

    override fun analyticsConsent(): Boolean? = when (manager?.preference(ANALYTICS_CONSENT_PREF)) {
        "granted" -> true
        "denied" -> false
        else -> null
    }

    override fun setAnalyticsConsent(granted: Boolean) {
        manager?.setPreference(ANALYTICS_CONSENT_PREF, if (granted) "granted" else "denied")
        analytics.enabled = granted
    }

    override fun track(event: String, props: Map<String, String>) {
        analytics.track(dev.ide.analytics.AnalyticsEvent(event, dev.ide.analytics.Events.categoryOf(event), props))
    }

    // --- critical-error dialog ---

    override val errorEvents: StateFlow<UiError?> get() = _errorEvents

    override fun dismissError(id: Int) {
        synchronized(errorLock) {
            if (_errorEvents.value?.id != id) return
            _errorEvents.value = if (errorQueue.isEmpty()) null else errorQueue.removeFirst()
        }
    }

    /** Enqueue an error for the dialog (shown one at a time; queue capped so a storm can't grow unbounded). */
    override fun showError(title: String, message: String, detail: String) {
        val err = UiError(errorIdSeq.incrementAndGet(), title, message, detail, timeLabel())
        synchronized(errorLock) {
            if (_errorEvents.value == null) _errorEvents.value = err
            else { errorQueue.addLast(err); while (errorQueue.size > 20) errorQueue.removeFirst() }
        }
    }

    /**
     * Install the process-wide uncaught-exception handler ([Thread.setDefaultUncaughtExceptionHandler], so it
     * covers every thread including the UI thread): surface the non-fatal dialog, report `app_crash`, and
     * **swallow** (don't chain to the system killer) so the app stays alive where it can. Hosts call this once
     * at startup. A UI-thread crash unwinds the looper, so after this handler reports it the process still
     * exits — an honest, reported crash rather than a silently-resumed corrupt state.
     */
    fun installCrashReporting() {
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching { Log.logger("uncaught").warn("Uncaught exception on ${thread.name}", t) } // ring/console only
            runCatching { showError("Application error", t.message ?: t.javaClass.simpleName, stackTraceString(t)) }
            runCatching {
                analytics.track(
                    dev.ide.analytics.AnalyticsEvent(
                        dev.ide.analytics.Events.APP_CRASH,
                        dev.ide.analytics.EventCategory.CRASH,
                        dev.ide.analytics.CrashScrub.scrub(t) + ("thread" to thread.name),
                    )
                )
                analytics.flush()
            }
        }
    }

    override fun timeLabel(): String = runCatching { java.time.LocalTime.now().withNano(0).toString() }.getOrDefault("")

    /** Close the active engine — the host calls this on teardown (window close / activity destroy). */
    fun close() {
        runCatching { Log.removeSink(errorDialogSink) }
        runCatching { analyticsScope.cancel() }
        runCatching { engineScope.cancel() }
        runCatching { perf.flushAll() } // drain partial latency windows so the last session's samples ship
        runCatching { analytics.flush() }
        runCatching { analytics.close() }
        activeServices?.close()
    }

    private companion object {
        const val ANALYTICS_CONSENT_PREF = "analytics.consent"
    }

    /** Make [next] the active project: swap it in, bump the epoch (re-keys UI state), and close the old one. */
    override fun swapEngine(next: IdeServices) {
        val prev = activeServices
        activeServices = next
        // Point the shared application environment at the now-active engine, for app-level extension callbacks
        // (command actions, synthetic-R, the XML resource host) that resolve the open project through it.
        manager?.env?.activeEngine = next
        _projectEpoch.value += 1
        if (prev !== next) runCatching { prev?.close() }
    }

}

/**
 * Owned render of a self-contained layout [xml] against an EMPTY resource table + no theme — the Learn tab's
 * Android-lesson preview, which visualizes a taught layout with no project open. Built-in + Material renderers
 * handle the tags; unresolved project resources fall back (lesson XML is authored to be self-contained). Never
 * the real-view path (that needs the SDK + a built project). Returns null on any inflation failure.
 */
private fun renderStandaloneLayout(
    xml: String, request: dev.ide.preview.PreviewRequest
): dev.ide.preview.LayoutPreviewResult? = runCatching {
    dev.ide.preview.impl.LayoutPreviewService().preview(
        xml = xml,
        repo = dev.ide.android.support.resources.ResourceRepository(emptyList()),
        themeName = null,
        title = "",
        density = request.density,
        scaledDensity = request.density,
        showChrome = request.showChrome,
        night = request.night,
    )
}.getOrNull()

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
import dev.ide.core.event.IdeEventTopics
import dev.ide.core.event.ProjectEvent
import dev.ide.core.backend.BlockBackend
import dev.ide.core.backend.BuildBackend
import dev.ide.core.backend.CustomizationBackend
import dev.ide.core.backend.DependencyBackend
import dev.ide.core.backend.IconBackend
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
import dev.ide.core.backend.VcsBackend
import dev.ide.platform.EngineBreadcrumb
import dev.ide.platform.EngineCanceledException
import dev.ide.platform.EnginePhase
import dev.ide.platform.EngineScheduler
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogLevel
import dev.ide.platform.log.LogSink
import dev.ide.preview.LayoutPreviewBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
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
     * Host-injected factory for an out-of-process build runner (the `:build` daemon, supplied by
     * :ide-android). Null → in-process builds (desktop, or when the separate-process build is off), i.e.
     * each engine's own [IdeServices.buildRunner]. See docs/build-process-isolation.md.
     */
    private val buildRunnerFactory: ((IdeServices) -> BuildRunner)? = null,
    /**
     * Whether the host currently permits notifications (Android: the POST_NOTIFICATIONS grant / app toggle).
     * The isolated `:build` process posts an ongoing progress notification via a foreground service, so with
     * notifications off we fall back to in-process builds — see [separateBuildProcessEnabled]. Defaults to
     * `true` (desktop / tests, where it's not gated); the on-device host injects the live check.
     */
    private val notificationsAllowed: () -> Boolean = { true },
) : IdeBackend, LayoutPreviewBackend, BackendContext {

    /** Opt-in usage analytics, resolved from the application service container (the on-device host registers a
     *  Supabase-backed [dev.ide.analytics.impl.DefaultAnalyticsService]); absent (desktop / tests) → the no-op
     *  service. Gated on the persisted consent preference — see [analyticsConsent]/[setAnalyticsConsent]. */
    private val analytics: AnalyticsService =
        manager?.applicationContainer?.getServiceOrNull(ANALYTICS_SERVICE) ?: dev.ide.analytics.NoopAnalyticsService

    override val separateProcessBuildsSupported: Boolean get() = buildRunnerFactory != null

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

    /** The app-global "Build in a separate process" setting (default ON; see [BuiltInSettingsPages]) AND-ed
     *  with [notificationsAllowed]: the isolated process shows an ongoing foreground-service notification, so
     *  without the notification permission we run in-process instead (the user is asked at the first build —
     *  see BuildNotificationGate — and can re-enable it from Settings). Read once per engine (the cache above
     *  freezes the choice), so a change applies on the next project open, keeping the build-state flow and the
     *  build methods bound to one consistent runner. */
    private fun separateBuildProcessEnabled(): Boolean =
        (manager?.preference("settings.${dev.ide.core.settings.BuiltInSettingsPages.BUILD_RUNTIME}.${dev.ide.core.settings.BuiltInSettingsPages.SEPARATE_PROCESS}")
            ?.toBooleanStrictOrNull() ?: true) && notificationsAllowed()

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

    // The app bus lives on the shared application platform (reached through the manager); the manager-less
    // single-project path falls back to the active engine, whose per-project platform shares that same bus.
    override val messageBus: dev.ide.platform.MessageBus?
        get() = manager?.env?.platform?.messageBus ?: activeServices?.appBus

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
     * given analysis is.
     *
     * This is a DEDICATED single thread, not `Dispatchers.Default.limitedParallelism(1)`. The latter
     * serializes (mutual exclusion) but HOPS between the shared pool's physical workers between calls, so the
     * analyzer state is written on one thread and read on the next — correctness then rests on the runtime
     * honouring the dispatcher's cross-thread happens-before (memory barriers). On some 32-bit ARM ARTs (e.g.
     * Unisoc SC9863A, issues #1396/#1332) that reliance produced a hard SIGSEGV (a torn reference read the
     * runtime never turned into an NPE) on this worker during editing. A single pinned thread gives true
     * single-thread confinement — what ecj/JDT expects, and immune to any weak-memory hand-off bug — for a
     * thread that never closes for the life of the backend. Named so a future tombstone points straight here.
     */
    private val engineExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ide-engine").apply { isDaemon = true } }
    override val engineDispatcher: CoroutineDispatcher = engineExecutor.asCoroutineDispatcher()

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
    // The observer records a crash breadcrumb the instant a lane's block STARTS running on the engine worker
    // (EnginePhase.RUNNING fires on the ide-engine thread). Persists the fine op label the caller passed (or the
    // lane name if none) — no file/path/source (analytics-safe) — so if the process then dies of the native
    // SIGSEGV (32-bit AND 64-bit; see EngineBreadcrumb / RuntimeInfo), the next launch can read WHICH engine
    // activity was in flight, not just "background". No-op until the launcher arms the breadcrumb file; the
    // direct withContext(engineDispatcher) editor ops don't route through the scheduler, so the dominant
    // per-keystroke work (completion=interactive, analysis/semantic/folding=background) is what's captured —
    // exactly the "crashes while typing" surface.
    private val scheduler = EngineScheduler(
        engineDispatcher,
        observer = { lane, phase, label ->
            if (phase == EnginePhase.RUNNING) EngineBreadcrumb.record(label.ifEmpty { lane.name.lowercase() })
        },
    )
    override suspend fun <T> interactive(op: String, block: suspend () -> T): T =
        logEditorFailures("completion") { scheduler.interactive(label = op.ifEmpty { "completion" }, block = block) }
    override suspend fun <T> background(op: String, block: suspend () -> T): T =
        logEditorFailures("analysis") { scheduler.background(label = op.ifEmpty { "background" }, block = block) }
    override suspend fun <T> preview(op: String, block: suspend () -> T): T =
        logEditorFailures("preview") { scheduler.preview(label = op.ifEmpty { "preview" }, block = block) }

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
            .flatMapLatest { engineSelect(default, select) }
            .stateIn(engineScope, SharingStarted.Eagerly, default)

    /**
     * [select] against the currently active engine, degrading to a constant [default] when there is no project
     * or the engine can no longer serve it.
     *
     * The guard is not defensive padding. [select] is arbitrary engine code -- `runner(it).buildState` resolves
     * the WORKSPACE-scoped build service, whose factory resolves [ENGINE_CONTEXT] -- and it runs on
     * [engineScope], not on the caller. Once a container has been disposed its registrations are gone and every
     * lookup is a hard `error("no service registered")`, which in a flow is not a stale value but process
     * death. Telemetry has that exact stack (`ServiceContainerImpl.getService` under this `flatMapLatest`)
     * across 3.5 through 3.9, always on a build or permission surface. The interleaving that leaves a disposed
     * engine reachable here is NOT pinned down -- [swapEngine] publishes `activeServices` before the epoch bump
     * and only closes the outgoing engine afterwards, so a straight read should see the live one -- but the
     * boundary is right either way: a project that is going away has nothing to report, and [default] is the
     * honest answer.
     */
    private fun <T> engineSelect(default: T, select: (IdeServices) -> StateFlow<T>): StateFlow<T> {
        val services = activeServices ?: return MutableStateFlow(default)
        return runCatching { select(services) }.getOrElse { t ->
            log.warn("engine flow unavailable (project closing or engine disposed)", t)
            MutableStateFlow(default)
        }
    }

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

    /**
     * The notification center, persisted under the app's storage root.
     *
     * Held as the concrete type because subsystems POST to it and the UI-facing [NotificationService] is
     * read-only by design: the UI marks things read, it does not invent notifications.
     */
    internal val notificationCenter: dev.ide.core.backend.NotificationCenter = run {
        val host: NotificationPresenter? =
            manager?.applicationContainer?.getServiceOrNull(NOTIFICATION_PRESENTER)
        dev.ide.core.backend.NotificationCenter(
            storageRoot = manager?.storageRoot?.toFile(),
            presenter = host?.let { presenter ->
                { n: dev.ide.ui.backend.UiNotification -> presenter.present(n) }
            },
        )
    }

    override val notifications: dev.ide.ui.backend.NotificationService = notificationCenter

    /**
     * Hand over notifications the host received while this engine did not exist.
     *
     * The push path needs it: FCM wakes the process with no engine, the platform layer builds the
     * notification and parks it, and this is where those land. Public because the launcher is in another
     * module, and narrow on purpose — posting anything else is the engine's own business.
     */
    fun adoptHostNotifications(incoming: List<dev.ide.ui.backend.UiNotification>) {
        notificationCenter.adopt(incoming)
    }
    override val editor: EditorService = EditorBackend(this)
    override val blocks: BlockService = BlockBackend(this)
    override val preview: PreviewService = PreviewBackend(this)
    override val search: SearchService = SearchBackend(this)
    override val build: BuildService = BuildBackend(this)
    override val deps: DependencyService = DependencyBackend(this)
    override val modules: ModuleService = ModuleBackend(this)
    override val signing: SigningService = SigningBackend(this)
    override val projects: ProjectService = ProjectBackend(this)
    /** The remote store catalog, resolved like [analytics]; absent → bundled-only. */
    private val storeCatalogSource: dev.ide.store.StoreCatalogSource =
        manager?.applicationContainer?.getServiceOrNull(STORE_CATALOG_SOURCE)
            ?: dev.ide.store.StoreCatalogSource.Unconfigured

    /** Sign-in, resolved the same way. Absent → the store is readable but not publishable. */
    private val storeAccountService: dev.ide.store.StoreAccountService =
        manager?.applicationContainer?.getServiceOrNull(STORE_ACCOUNT_SERVICE)
            ?: dev.ide.store.StoreAccountService.Unsupported

    /** Publishing, resolved the same way. Absent → the store is readable but not publishable. */
    private val storeSubmissionService: dev.ide.store.StoreSubmissionService =
        manager?.applicationContainer?.getServiceOrNull(STORE_SUBMISSION_SERVICE)
            ?: dev.ide.store.StoreSubmissionService.Unsupported

    override val store: StoreService =
        StoreBackend(this, storeCatalogSource, storeAccountService, storeSubmissionService, notificationCenter)
    // Held as the concrete type so the Compose preview host can reach its ide-core-only lesson-lowering methods
    // ([lowerLessonComposePreview]) that return an ide-core type the [LearnService] UI interface can't name.
    private val learnBackend = LearnBackend(this)
    override val learn: LearnService = learnBackend
    override val sdk: SdkService = SdkBackend(this)
    override val settings: SettingsService = SettingsBackend(this)
    override val customize: dev.ide.ui.backend.CustomizationService = CustomizationBackend(this)
    override val actions: ActionService = ActionBackend(this)
    override val icons: dev.ide.ui.backend.IconService = IconBackend(this)
    // The AI agent is a disablable, non-essential plugin ([AgentPlugin.ID]). When the user turns it off in
    // Settings > Plugins the plugin isn't loaded, so we hand the UI the no-op service — the chat panel, the
    // sparkle toggle, and the agent loop all disappear (the UI keys these surfaces off this being Unsupported).
    // A manager-less backend (tests / single-project) has no catalog, so the agent stays wired.
    override val agent: dev.ide.ui.backend.AgentService =
        if (manager?.env?.pluginCatalog?.isEnabled(AgentPlugin.ID) != false) AgentBackend(this)
        else dev.ide.ui.backend.AgentService.Unsupported

    // Version control is a disablable, non-essential plugin ([VcsPlugin.ID]). When it is off the plugin isn't
    // loaded, so the UI is handed the no-op service and every Git surface disappears. A manager-less backend
    // (tests / single-project) has no catalog, so it stays wired.
    override val vcs: dev.ide.ui.backend.VcsService =
        if (manager?.env?.pluginCatalog?.isEnabled(VcsPlugin.ID) != false) VcsBackend(this)
        else dev.ide.ui.backend.VcsService.Unsupported

    // The Compose UI facets of the enabled built-in plugins (see BuiltInPlugins). The shell registers them into
    // UiPluginHost; a disabled plugin's facet isn't in this list, so its UI never appears. Empty with no manager.
    override fun uiPlugins(): List<dev.ide.ui.ext.UiPlugin> = manager?.env?.enabledUiPlugins ?: emptyList()
    override val diagnostics: DiagnosticsService = DiagnosticsBackend(this)

    init {
        Log.addSink(errorDialogSink)

        // index_perf: time each index build (building → not building) and emit its duration + a per-indexer
        // breakdown, so the fleet reveals WHICH index (and which phase) dominates. Low-volume (once per
        // build/reindex). Re-subscribes per project (collectLatest on the epoch).
        analyticsScope.launch {
            projectEpoch.collectLatest {
                val svc = activeServices ?: return@collectLatest
                var startNs = 0L
                var building = false
                svc.indexStatus.collectLatest { st ->
                    if (st.building && !building) {
                        building = true; startNs = System.nanoTime()
                        // Mark the index as in flight so a native crash this session is attributed to
                        // concurrent index churn (the leading hypothesis for the residual SIGSEGV).
                        EngineBreadcrumb.noteIndexBuilding(true)
                    } else if (!st.building && building) {
                        building = false
                        EngineBreadcrumb.noteIndexBuilding(false)
                        val props = buildMap {
                            put("duration_ms", ((System.nanoTime() - startNs) / 1_000_000).toString())
                            // Phase split + cache effectiveness + source-diff counts: is the wall time in the
                            // library phase or the source phase, and how much came from the on-disk segment
                            // cache vs. a fresh build. All counts/times — no names or paths.
                            st.stats?.let { s ->
                                put("lib_ms", s.libMs.toString())
                                put("src_ms", s.sourceMs.toString())
                                put("artifacts", s.artifacts.toString())
                                put("artifacts_built", s.artifactsBuilt.toString())
                                put("artifacts_reused", s.artifactsReused.toString())
                                put("src_files", s.sourceFiles.toString())
                                put("src_parsed", s.sourceParsed.toString())
                            }
                            // Per-indexer time (the "which index is slow" signal), keyed by the stable index
                            // id (e.g. idx.java.classNames.ms) — never a file/artifact/project name. Skip the
                            // trivially-fast ones (<1ms) to keep the row bounded.
                            for (b in st.breakdown) if (b.indexMs >= 1) {
                                put("idx.${b.id}.ms", b.indexMs.toString())
                            }
                            // Heap at index completion (Phase-0 build-isolation instrumentation) so the index
                            // phase's memory footprint is comparable to a build's peak across the fleet.
                            putAll(MemSample.now().props())
                        }
                        track(dev.ide.analytics.Events.INDEX_PERF, props)
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
                            } ?: emptyMap())
                            // On failure, categorize WHY (compile vs resource vs tool vs oom vs no-diagnostic):
                            // the ok=false bit alone can't tell a user's compile error from our pipeline throwing.
                            + (if (bs.status == dev.ide.ui.backend.RunStatus.Failed)
                                mapOf("failure_kind" to BuildFailureKind.classify(bs.diagnostics, bs.log))
                              else emptyMap()),
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

    /** The preview-sandbox categories the open project restricts (`SandboxCategory.id` strings from the
     *  project-scoped Compose Preview settings); the preview host builds a `PreviewSandboxPolicy` from them.
     *  A pref read, but routed through the preview lane like its sibling calls so it never races a swap of
     *  the inner services on project switch. */
    suspend fun composePreviewSandbox(): Set<String> =
        preview { services.composePreviewSandbox() }

    /** Whether the `@Preview` should render in the `:preview` OS process (the isolation toggle, default OFF).
     *  The Android host reads this to choose the remote streaming path over the in-process renderer. */
    suspend fun composePreviewIsolated(): Boolean =
        preview { services.composePreviewIsolated() }

    /** The previewed module's res-dir paths + R namespace, so the `:preview` process can rebuild the resource
     *  repository (it can't receive the in-memory one). Null for a non-Android module / one with no res dirs. */
    suspend fun composePreviewResourceRoots(path: String): ComposePreviewResourceRoots? =
        preview { services.composePreviewResourceRoots(Paths.get(path)) }

    /** The previewed module's resources + R package for interpreter-mediated resource resolution
     *  (`stringResource`/`R.string.x`/…); the launcher builds a `PreviewResourceResolver` from it. Off the UI
     *  thread (the first `ResourceRepository` build parses all dependency/AAR res). */
    suspend fun composePreviewResources(path: String): ComposePreviewResources? =
        preview { services.composePreviewResources(Paths.get(path)) }

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
                        // Heap state at the crash: lets OOM-adjacent crashes (the 43 OutOfMemoryErrors, plus
                        // any that fail from memory pressure without saying so) be told apart from logic bugs.
                        dev.ide.analytics.CrashScrub.scrub(t) + ("thread" to thread.name) + MemSample.now().props(),
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
        // The version-control backend holds an open repository handle and its own refresh coroutine.
        runCatching { (vcs as? VcsBackend)?.close() }
        activeServices?.close()
        runCatching { engineExecutor.shutdown() } // stop the dedicated ide-engine thread on teardown
        // Clean shutdown ⇒ drop the crash breadcrumb, so a file that survives to the next launch means the
        // process did NOT exit cleanly (crashed/killed) — the fallback signal where the OS exit reason is absent.
        runCatching { EngineBreadcrumb.clear() }
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
        // Publish the project lifecycle for plugin subscribers, on the same app bus. Opened for the new engine;
        // Closed for the one being replaced (before it is disposed). Guarded so a subscriber can't break the swap.
        val bus = messageBus
        if (bus != null) {
            runCatching { bus.syncPublisher(IdeEventTopics.PROJECT).onProjectEvent(ProjectEvent.Opened(next.workspaceRoot.toString())) }
            if (prev != null && prev !== next) {
                runCatching { bus.syncPublisher(IdeEventTopics.PROJECT).onProjectEvent(ProjectEvent.Closed(prev.workspaceRoot.toString())) }
            }
        }
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

package dev.ide.core

import dev.ide.core.agent.AgentBackend
import dev.ide.core.build.BuildFailureKind
import dev.ide.core.build.BuildRunner
import dev.ide.core.perf.MemSample
import dev.ide.core.perf.PerfSampler
import dev.ide.core.perf.UsageCounter
import dev.ide.core.plugins.AgentPlugin
import dev.ide.core.plugins.VcsPlugin
import dev.ide.core.project.ProjectManager
import dev.ide.core.sdk.SdkManagerService
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
import dev.ide.model.event.ProjectEvent
import dev.ide.model.event.ProjectTopics
import dev.ide.platform.Disposable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
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
                val runner = if (factory != null && separateBuildProcessEnabled()) factory(services) else services.buildRunner
                // Only the chosen runner warms its compiler: the in-process one here, the remote one in the
                // `:build` process. Warming both would leave a second compiler resident that never compiles.
                // Not straight away: the warm-up is seconds of CPU and heap (a forked compiler VM either way),
                // and project open is when the index reopen and the editor's first analysis of the open file
                // need the device most. Wait for the index, then for the editor to go quiet.
                engineScope.launch {
                    services.awaitOpenIndex()
                    scheduler.awaitQuiet(COMPILER_WARMUP_QUIET)
                    runCatching { runner.warmCompiler() }
                }
                runner
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
     *  1. [interactive]: completion and the read-only requests the user waits on (go to, expand selection,
     *     format): highest priority, preempts both background and preview.
     *  2. [background] — analysis/hints/semantic/folding/signature: preempts preview, preempted by interactive
     *     (the scheduler throws [EngineCanceledException]; [background] below re-runs the call after it).
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
        logEditorFailures(op.substringBefore(':').ifEmpty { "completion" }) {
            scheduler.interactive(label = op.ifEmpty { "completion" }, block = block)
        }
    /**
     * A background-lane call that waits out preemption instead of failing with it. Completion preempting this
     * lane is the scheduler's business, not the caller's: most callers (icon import, the agent's workspace
     * tools, preview readiness) are one-off operations with no "skip it, the next keystroke retries" to fall
     * back on, and an [EngineCanceledException] escaping one of them crashed the app. So while the caller still
     * wants the result, the call runs again once the completion has had the worker. Cancelling the caller still
     * ends it, as a [kotlinx.coroutines.CancellationException].
     */
    override suspend fun <T> background(op: String, block: suspend () -> T): T =
        logEditorFailures("analysis") { backgroundUntilRun(op.ifEmpty { "background" }, block) }

    private suspend fun <T> backgroundUntilRun(label: String, block: suspend () -> T): T {
        while (true) {
            try {
                return scheduler.background(label = label, block = block)
            } catch (_: EngineCanceledException) {
                // Re-queue once the completion that preempted this is done, rather than polling while it runs.
                scheduler.awaitInteractiveIdle()
            }
        }
    }
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

    /**
     * Bumps [fileSystemEpoch] from the active engine's workspace event stream, so the tree and the open
     * tabs catch up after ANY write the UI did not drive: a plugin's resource write, a template scaffold,
     * an icon generated into `res/`, a checkout. The explicit `bumpFileSystemEpoch()` calls the backends
     * already make stay where they are (they also cover model-only changes that publish no file event);
     * a double bump costs nothing, because [MutableStateFlow] conflates. Editor saves are excluded at the
     * hub: they carry their text, and the UI already has it.
     */
    private var fsEpochSubscription: Disposable? = null

    /** Point [fsEpochSubscription] at [engine]. Called for the constructor's engine and on every swap, so an
     *  [IdeServicesBackend] built around an already-open project follows it too. */
    private fun followFileSystem(engine: IdeServices?) {
        runCatching { fsEpochSubscription?.dispose() }
        fsEpochSubscription = engine?.let {
            runCatching { it.addFileSystemListener { bumpFileSystemEpoch() } }.getOrNull()
        }
    }

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
    override fun recordPerf(event: String, ms: Long, dims: Map<String, String>, queuedMs: Long?) =
        perf.record(event, ms, dims, queuedMs)

    /** Frame durations summarized over a much longer window than the latency metrics: a busy UI draws dozens
     *  of frames a second, and one event per few hundred frames is enough to see jank trend by version. */
    private val frames = PerfSampler(windowSize = FRAME_WINDOW) { name, props -> track(name, props) }

    /** The app-side half of [recordFrame]; its own sampler so the two windows close independently. */
    private val frameCpu = PerfSampler(windowSize = FRAME_WINDOW) { name, props -> track(name, props) }

    /**
     * One drawn frame the host decided to count: [totalMs] end to end, of which [cpuMs] was the app's own
     * work; [missedDeadline] is whether the frame actually arrived late, and [cpuOverran] whether that work
     * alone was already over budget before anything was handed to the display.
     *
     * Two metrics rather than one because they answer different questions and can disagree. `frame_perf`
     * carries the whole frame, which is what the user feels but which includes waiting for a buffer: normal
     * pipelining that no amount of work on our side removes. `frame_cpu_perf` carries only what this app
     * spends, so a regression in our own code can neither hide behind a fast display nor be invented by a
     * slow one.
     */
    fun recordFrame(totalMs: Long, cpuMs: Long, missedDeadline: Boolean, cpuOverran: Boolean) {
        frames.record(dev.ide.analytics.Events.FRAME_PERF, totalMs, over = missedDeadline)
        frameCpu.record(dev.ide.analytics.Events.FRAME_CPU_PERF, cpuMs, over = cpuOverran)
    }

    // ---- critical-error surface (non-fatal dialog, fed by the logging facade) ----

    private val _errorEvents = MutableStateFlow<UiError?>(null)
    private val errorQueue = ArrayDeque<UiError>()
    private val errorIdSeq = AtomicInteger(0)
    private val errorLock = Any()

    /** Turns engine ERROR logs (caught failures) into the non-fatal dialog. Registered on [Log] in [init]. */
    private val errorDialogSink = LogSink { record ->
        val t = record.throwable
        if (record.level == LogLevel.ERROR && t != null && !PluginSandbox.isRelayed(record)) {
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
     * Carry a plugin's [dev.ide.platform.notify.UserMessages] posts into the notification center.
     *
     * A plugin's engine facet has no screen of its own, and what it has to say ("the toolchain finished
     * unpacking", "no toolchain for this device's ABI") is almost always about work the user was not
     * watching, which is exactly what the center is for. The center's own transient queue stays the surface
     * for a snackbar; this is what makes the capability visible in a shipped surface rather than only being
     * a state holder nothing reads.
     *
     * Disposed in [close]. The center outlives this backend (it is app-global, the plugins are too), so a
     * relay left subscribed would keep posting into a closed project's center, and every project switch
     * would add another copy of every message.
     */
    private val userMessageRelay: dev.ide.platform.Disposable? = manager?.userMessages?.onMessage { posted ->
        runCatching {
            val (title, body) = dev.ide.core.backend.splitMessage(posted.message.text)
            notificationCenter.post(
                kind = dev.ide.ui.backend.UiNotificationKind.SYSTEM,
                title = title,
                body = body,
                // The plugin whose code posted it, so the entry says who is talking.
                source = runCatching { blamePlugin(posted.origin)?.name }.getOrNull(),
                foldRepeats = true,
                level = when (posted.message.severity) {
                    dev.ide.platform.notify.MessageSeverity.ERROR -> dev.ide.ui.backend.UiNotificationLevel.ERROR
                    dev.ide.platform.notify.MessageSeverity.WARNING -> dev.ide.ui.backend.UiNotificationLevel.WARNING
                    else -> dev.ide.ui.backend.UiNotificationLevel.INFO
                },
            )
        }
    }

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
    // Read when a delete finishes, by which time [store] below exists.
    override val projects: ProjectService =
        ProjectBackend(this, onProjectDeleted = { (store as? StoreBackend)?.forgetDeletedInstalls() })
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

    /** Ratings and reviews, resolved like the rest of the store ports. */
    private val storeReviewService: dev.ide.store.StoreReviewService =
        manager?.applicationContainer?.getServiceOrNull(STORE_REVIEW_SERVICE)
            ?: dev.ide.store.StoreReviewService.Unsupported

    /** Moderation, resolved like the rest. Absent means this build shows no moderation surface at all. */
    private val storeModerationService: dev.ide.store.StoreModerationService =
        manager?.applicationContainer?.getServiceOrNull(STORE_MODERATION_SERVICE)
            ?: dev.ide.store.StoreModerationService.Unsupported

    override val store: StoreService = StoreBackend(
        this, storeCatalogSource, storeAccountService, storeSubmissionService, notificationCenter,
        storeModerationService, storeReviewService,
    )

    /** Daily challenges, resolved like the rest of the online ports. */
    private val dailyChallengeService: dev.ide.store.DailyChallengeService =
        manager?.applicationContainer?.getServiceOrNull(DAILY_CHALLENGE_SERVICE)
            ?: dev.ide.store.DailyChallengeService.Unsupported

    override val challenges: dev.ide.ui.backend.ChallengeService =
        dev.ide.core.backend.ChallengeBackend(this, dailyChallengeService)
    // Held as the concrete type so the Compose preview host can reach its ide-core-only lesson-lowering methods
    // ([lowerLessonComposePreview]) that return an ide-core type the [LearnService] UI interface can't name.
    private val learnBackend = LearnBackend(
        this,
        manager?.applicationContainer?.getServiceOrNull(LEARN_REMOTE_SERVICE) ?: dev.ide.store.LearnRemoteService.Unsupported,
    )
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
        followFileSystem(activeServices)

        // The index-in-flight crash breadcrumb follows the status edges: a native crash this session is then
        // attributed to concurrent index churn (the leading hypothesis for the residual SIGSEGV).
        analyticsScope.launch {
            projectEpoch.collectLatest {
                val svc = activeServices ?: return@collectLatest
                var building = false
                svc.indexStatus.collectLatest { st ->
                    if (st.building != building) {
                        building = st.building
                        EngineBreadcrumb.noteIndexBuilding(building)
                    }
                }
            }
        }
        // index_perf: one row per finished full index pass (open, resync, re-index), from the engine's own
        // pass reports, so a pass that finishes between two samples of the status flow is still counted. Carries
        // WHY it ran, the phase split, cache effectiveness, which kinds of artifact had to be rebuilt (and
        // whether one keeps being rebuilt), and a per-indexer breakdown. Times, counts and kinds only: no
        // names or paths. Re-subscribes per project (collectLatest on the epoch).
        analyticsScope.launch {
            // The flow replays its latest report to each new subscriber; never count one pass twice.
            var lastTracked: IndexPassReport? = null
            projectEpoch.collectLatest {
                val svc = activeServices ?: return@collectLatest
                svc.indexPasses.collect { pass ->
                    if (pass === lastTracked) return@collect
                    lastTracked = pass
                    track(dev.ide.analytics.Events.INDEX_PERF, indexPerfProps(pass))
                }
            }
        }
        // Tell background work (source-doc indexing, prebuilt downloads) when a build is compiling, wherever it runs:
        // the live buildState covers the in-process and the `:build`-process runner alike. A user program that is
        // running after its build doesn't count (the console's Running phase). See BuildActivity.
        // `build` here is this backend's BuildBackend, whose flows follow the ACTIVE runner (the `:build` daemon's
        // when builds run there); an engine's own BuildService never hears about a daemon build.
        analyticsScope.launch {
            val activityLog = dev.ide.platform.log.Log.logger("build.activity")
            try {
                kotlinx.coroutines.flow.combine(build.buildState, build.runConsole) { bs, console ->
                    bs.status == dev.ide.ui.backend.RunStatus.Running && console?.phase != dev.ide.ui.backend.RunPhase.Running
                }.distinctUntilChanged().collect {
                    dev.ide.platform.BuildActivity.set(it)
                    activityLog.info(if (it) "build compiling: background work waits" else "build idle: background work resumes")
                }
            } finally {
                dev.ide.platform.BuildActivity.set(false)
            }
        }
        // Emit build_result (the performance signal) when a build/run reaches a terminal status, watched off the
        // live buildState so it captures every trigger (Run button, task picker, android run). `build` is this
        // backend's BuildBackend, which follows the ACTIVE runner and re-points on a project swap: an engine's own
        // BuildService never hears about a build in the `:build` daemon (Android's default), so builds there went
        // unreported. track() no-ops while consent is absent, so this is harmless when analytics is off.
        analyticsScope.launch {
            var prev = dev.ide.ui.backend.RunStatus.Idle
            build.buildState.collect { bs ->
                val terminal = bs.status == dev.ide.ui.backend.RunStatus.Succeeded || bs.status == dev.ide.ui.backend.RunStatus.Failed
                if (terminal && prev == dev.ide.ui.backend.RunStatus.Running) {
                    // Attach this build's heap peak (Phase-0 build-isolation instrumentation) when it ran in this
                    // process: the in-process service measured it. A daemon build's peak is the daemon's, and the
                    // in-process value would be an earlier build's.
                    val svc = activeServices
                    val peak = svc?.takeIf { buildRunnerFor(it) === it.buildRunner }?.build?.lastBuildPeak
                    track(
                        dev.ide.analytics.Events.BUILD_RESULT,
                        mapOf(
                            "ok" to (bs.status == dev.ide.ui.backend.RunStatus.Succeeded).toString(),
                            "duration_ms" to bs.elapsedMs.toString(),
                            "steps" to bs.steps.size.toString(),
                            "process" to (if (peak != null || svc?.let { buildRunnerFor(it) === it.buildRunner } == true) "in-process" else "daemon"),
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

    override val project: ProjectInfo
        get() = ProjectInfo(
            name = services.projectDisplayName(),
            rootPath = services.workspaceRoot.toString(),
            moduleCount = services.modules().size,
            compatibility = runCatching { services.isCompatibilityMode() }.getOrDefault(false),
        )

    // ---- Compose preview (LayoutPreviewBackend; aggregator-level) ----
    // Lowering after an edit uses the preview lane. The small setup reads a first frame waits on (readiness,
    // libraries, resource roots, sandbox, isolation) use the background lane: each is a few milliseconds, and on
    // the preview lane every one of them queued behind the editor's whole run of passes on the opened file.

    /**
     * The lowered preview to render. Returns an ide-core type; the preview hosts call this.
     *
     * A re-lower after an edit is lowest-priority engine work: preempted by analysis and completion, it retries
     * until the engine is free, so it never competes with typing. An [urgent] one (the host has nothing on screen
     * yet: a preview just opened, or the user switched to another) queues with the editor's own passes instead and
     * is not preempted by them. On a cold open the lowering itself took ~60 ms but waited ~3 s behind the
     * highlighting and diagnostics of the same file (and a cold first attempt was thrown away when they arrived).
     */
    suspend fun lowerComposePreview(
        path: String, functionName: String, arity: Int, text: String, urgent: Boolean = false,
    ): LoweredComposePreview? {
        // A live-literal edit patches the last lowering directly: no lane wait behind the editor's analysis. Off the
        // caller's thread all the same (the host asks from the UI thread): the patch diffs the whole buffer.
        withContext(Dispatchers.Default) {
            runCatching { services.liveLiteralPreview(Paths.get(path), text, functionName, arity) }.getOrNull()
        }?.let { return it }
        return timedPass(if (urgent) "lowerFirst" else "lower", path, { it?.program?.size ?: 0 }) {
            if (urgent) background("lowerPreview") { services.lowerComposePreview(Paths.get(path), text, functionName, arity) }
            else preview { services.lowerComposePreview(Paths.get(path), text, functionName, arity) }
        }
    }

    /** Why [functionName] isn't interpretable yet (lowering diagnostics + offending source), for the preview
     *  panel's not-interpretable state. Lowest-priority engine work; preempted by analysis and completion. */
    suspend fun composePreviewDiagnostics(path: String, functionName: String, arity: Int, text: String): List<String> =
        preview { services.composePreviewDiagnostics(Paths.get(path), text, functionName, arity) }

    /** Whether [path]'s module can resolve library composables yet (see [IdeServices.composePreviewReady]).
     *  The preview host polls this to show a transient "Preparing" state (and retry) instead of latching a
     *  first-run (index still building) failure into a permanent "unresolved call" error. */
    suspend fun composePreviewReady(path: String): Boolean =
        background("previewSetup") { services.composePreviewReady(Paths.get(path)) }

    /** The project library inputs for the on-device Compose preview's `DexClassLoader` (see
     *  [IdeServices.composePreviewLibs]). Lowest-priority engine work; preempted by analysis and completion. */
    suspend fun composePreviewLibs(path: String): ComposePreviewLibs? =
        background("previewSetup") { services.composePreviewLibs(Paths.get(path)) }

    /** The preview-sandbox categories the open project restricts (`SandboxCategory.id` strings from the
     *  project-scoped Compose Preview settings); the preview host builds a `PreviewSandboxPolicy` from them.
     *  A pref read, but routed through the preview lane like its sibling calls so it never races a swap of
     *  the inner services on project switch. */
    suspend fun composePreviewSandbox(): Set<String> =
        background("previewSetup") { services.composePreviewSandbox() }

    /** Whether the `@Preview` should render in the `:preview` OS process (the isolation toggle, default OFF).
     *  The Android host reads this to choose the remote streaming path over the in-process renderer. */
    suspend fun composePreviewIsolated(): Boolean =
        background("previewSetup") { services.composePreviewIsolated() }

    /** The previewed module's res-dir paths + R namespace, so the `:preview` process can rebuild the resource
     *  repository (it can't receive the in-memory one). Null for a non-Android module / one with no res dirs. */
    suspend fun composePreviewResourceRoots(path: String): ComposePreviewResourceRoots? =
        background("previewSetup") { services.composePreviewResourceRoots(Paths.get(path)) }

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

    /**
     * Which wording the user's "granted" answered: [ANALYTICS_SCOPE_USAGE] once it named feature usage. A consent
     * from before that (the sheet then promised "never which features you use") keeps performance data flowing
     * but counts no usage, and the sheet asks again.
     */
    private fun consentCoversUsage(): Boolean =
        analyticsConsent() == true && manager?.preference(ANALYTICS_SCOPE_PREF) == ANALYTICS_SCOPE_USAGE

    override fun analyticsNeedsAsking(): Boolean =
        analyticsAvailable() && (analyticsConsent() == null || (analyticsConsent() == true && !consentCoversUsage()))

    override fun setAnalyticsConsent(granted: Boolean) {
        manager?.setPreference(ANALYTICS_CONSENT_PREF, if (granted) "granted" else "denied")
        // The sheet the user just answered is the current wording, so a yes covers usage and a no covers nothing.
        manager?.setPreference(ANALYTICS_SCOPE_PREF, if (granted) ANALYTICS_SCOPE_USAGE else "")
        analytics.enabled = granted
        if (!granted) usage.clear()
    }

    /** Feature-use counts, reported per feature when telemetry is flushed ([flushTelemetry], [close]). */
    private val usage = UsageCounter { feature, count ->
        track(dev.ide.analytics.Events.FEATURE_USED, mapOf("feature" to feature, "count" to count.toString()))
    }

    override fun countFeature(feature: String) {
        if (consentCoversUsage()) usage.count(feature)
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
     * covers every thread including the UI thread): report `app_crash`, then either keep the app alive or hand
     * the crash on to the handler that was installed before this one. Hosts call this once at startup.
     *
     * A crash on a thread the app can lose (a worker) is **swallowed** behind the non-fatal dialog. A crash on a
     * thread [isFatal] names (the UI thread) is chained to the previous handler, which on Android is the system
     * killer: the throw has already unwound that thread's looper, so the process cannot go on. Swallowing it
     * there does not end the process either: main returns, the VM waits forever on the non-daemon threads still
     * running, and the frozen window is reported as an ANR ("Input dispatching timed out") instead of a crash.
     */
    fun installCrashReporting(
        isFatal: (Thread) -> Boolean = { false },
        /** How long this process has run, for counting crashes that come right after a launch. */
        processUptimeMs: () -> Long = { Long.MAX_VALUE },
        /** Turn an installed plugin off when it crashes the process (see PluginCrashGuard). Off in the plugin
         *  sandbox, whose whole purpose is running a plugin that may crash. */
        guardAgainstPluginCrashes: Boolean = true,
    ) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            val fatal = runCatching { isFatal(thread) }.getOrDefault(false)
            // Whose code it was. An installed plugin runs in this process, so its throw lands here like ours.
            val plugin = runCatching { blamePlugin(t.stackTrace.asList(), t) }.getOrNull()
            runCatching { Log.logger("uncaught").warn("Uncaught exception on ${thread.name}", t) } // ring/console only
            // In the plugin's own log too, which its page in Plugins opens: where its author looks.
            if (plugin != null) runCatching { Log.logger("CodeAssist", plugin.id).error("Uncaught exception on ${thread.name}", t) }
            if (!fatal) runCatching {
                val title = if (plugin != null) "${plugin.name} (plugin) failed" else "Application error"
                showError(title, t.message ?: t.javaClass.simpleName, stackTraceString(t))
            }
            if (fatal && guardAgainstPluginCrashes) runCatching {
                manager?.pluginCrashGuard?.onFatalCrash(
                    plugin?.let { dev.ide.core.plugins.PluginCrashGuard.Blamed(it.id, it.version, it.name) },
                    processUptimeMs(),
                    manager.disabledPlugins(),
                )
            }
            runCatching {
                analytics.track(
                    dev.ide.analytics.AnalyticsEvent(
                        dev.ide.analytics.Events.APP_CRASH,
                        dev.ide.analytics.EventCategory.CRASH,
                        // Heap state at the crash: lets OOM-adjacent crashes (the 43 OutOfMemoryErrors, plus
                        // any that fail from memory pressure without saying so) be told apart from logic bugs.
                        // `in_plugin` keeps a plugin's crash out of the IDE's own crash-free rate; the plugin's
                        // id is not sent (an unpublished plugin's id is its developer's package name).
                        // `locale` (a language tag, e.g. "id-ID") because some crashes depend on it: Compose's
                        // plural lookup throws for a default locale with no language.
                        dev.ide.analytics.CrashScrub.scrub(t) + ("thread" to thread.name) + MemSample.now().props() +
                            ("locale" to runCatching { java.util.Locale.getDefault().toLanguageTag() }.getOrDefault("?")) +
                            (if (plugin != null) mapOf("in_plugin" to "1") else emptyMap()),
                    )
                )
                analytics.flush()
            }
            if (fatal) {
                // `track` has already persisted the event to disk, so the next launch sends it if this flush could not.
                if (previous != null) previous.uncaughtException(thread, t) else kotlin.system.exitProcess(10)
            }
        }
    }

    /** An installed plugin a stack was blamed on. */
    data class BlamedPlugin(val id: String, val name: String, val version: String)

    /** The blame built for one set of plugin loaders; rebuilt when a reload replaces the set (plugin sandbox). */
    @Volatile
    private var blameCache: Pair<Map<String, ClassLoader>, dev.ide.core.plugins.PluginBlame?>? = null

    private val pluginBlame: dev.ide.core.plugins.PluginBlame?
        get() {
            val loaders = manager?.env?.externalPluginLoaders ?: return null
            blameCache?.let { (forLoaders, blame) -> if (forLoaders === loaders) return blame }
            val blame = loaders.takeIf { it.isNotEmpty() }?.let { dev.ide.core.plugins.PluginBlame.ofLoaders(it) }
            blameCache = loaders to blame
            return blame
        }

    /** The installed plugin whose code is topmost in [frames] (or, given [t], anywhere in its cause chain). */
    fun blamePlugin(frames: List<StackTraceElement>, t: Throwable? = null): BlamedPlugin? {
        val blame = pluginBlame ?: return null
        val id = (if (t != null) blame.blame(t) else blame.blame(frames)) ?: return null
        val manifest = manager?.env?.installedPlugins?.firstOrNull { it.manifest.id == id }?.manifest
        return BlamedPlugin(id, manifest?.name ?: id, manifest?.version ?: "?")
    }

    /**
     * The UI process's half of the plugin crash safety net, called once at startup: settle what the load did
     * (an update lifted a crash suspension, safe mode ran), post what an earlier launch could not say before
     * it died, and after [dev.ide.core.plugins.PluginCrashGuard.STABLE_AFTER_MS] count this launch as a good one.
     */
    fun startPluginCrashSafety() {
        val m = manager ?: return
        val guard = m.pluginCrashGuard
        runCatching { guard.afterLoad(m.env.liftedCrashSuspensions, m.env.safeModeApplied) }
        runCatching {
            guard.takeNotice()?.let { n ->
                notificationCenter.post(
                    kind = dev.ide.ui.backend.UiNotificationKind.SYSTEM,
                    title = n.title,
                    body = n.body,
                    target = dev.ide.ui.backend.UiNotificationTarget.Screen(PLUGINS_ROUTE),
                    key = "plugin-crash:${n.pluginId ?: "safe-mode"}",
                )
            }
        }
        analyticsScope.launch {
            delay(dev.ide.core.plugins.PluginCrashGuard.STABLE_AFTER_MS)
            runCatching { guard.markStable() }
        }
    }

    /**
     * An installed plugin crashed one of the app's helper processes ([where], e.g. "the build process"), which
     * died of it and named it on the way down. Turn it off exactly as a crash of this process would (see
     * [dev.ide.core.plugins.PluginCrashGuard]), and say so now: unlike a crash of this process, the IDE is
     * still here to tell the user.
     */
    fun suspendPluginAfterProcessCrash(blamed: dev.ide.core.plugins.PluginCrashGuard.Blamed, where: String) {
        val m = manager ?: return
        if (blamed.id in m.disabledPlugins()) return
        runCatching {
            m.pluginCrashGuard.onFatalCrash(blamed, Long.MAX_VALUE, m.disabledPlugins())
            // Said below, now; the next launch has nothing left to say about it.
            m.pluginCrashGuard.takeNotice()
        }
        runCatching {
            Log.logger("CodeAssist", blamed.id).error("${blamed.name} ${blamed.version} crashed $where and was turned off")
        }
        runCatching {
            notificationCenter.post(
                kind = dev.ide.ui.backend.UiNotificationKind.SYSTEM,
                title = "${blamed.name} was turned off",
                body = "It crashed $where. It stays off until a new version is installed, or until you turn it back " +
                    "on in Plugins.",
                target = dev.ide.ui.backend.UiNotificationTarget.Screen(PLUGINS_ROUTE),
                key = "plugin-crash:${blamed.id}",
            )
        }
    }

    /** Per plugin id: how many times this session it stalled the main thread, and the longest stall. */
    private val pluginStalls = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * The main thread was blocked for [durationMs] and has recovered; [frames] is its stack sampled while it was
     * stuck. Sent as [dev.ide.analytics.Events.MAIN_STALL] (scrubbed like a crash), and when an installed plugin's
     * code was on that stack, said in the plugin's log and in one notification per plugin that keeps count,
     * so a plugin that freezes the IDE is named rather than leaving the IDE to look slow.
     */
    fun reportMainStall(frames: List<StackTraceElement>, durationMs: Long, uptimeMs: Long, foreground: Boolean) {
        val plugin = runCatching { blamePlugin(frames) }.getOrNull()
        runCatching {
            val sample = Throwable("main thread stalled").apply { stackTrace = frames.toTypedArray() }
            analytics.track(
                dev.ide.analytics.AnalyticsEvent(
                    dev.ide.analytics.Events.MAIN_STALL,
                    dev.ide.analytics.EventCategory.CRASH,
                    mapOf(
                        "duration_ms" to durationMs.toString(),
                        "uptime_ms" to uptimeMs.toString(),
                        "fg" to if (foreground) "1" else "0",
                        "frames" to dev.ide.analytics.CrashScrub.ownFrames(sample),
                    ) + (if (plugin != null) mapOf("in_plugin" to "1") else emptyMap()) + MemSample.now().props(),
                ),
            )
        }
        if (plugin == null) return
        runCatching {
            Log.logger("CodeAssist", plugin.id).warn(
                "Blocked the main thread for ${durationMs} ms; CodeAssist stopped responding meanwhile. " +
                    "Move this work off the main thread:\n" + frames.take(25).joinToString("\n") { "    at $it" },
            )
        }
        val (count, longest) = pluginStalls.merge(plugin.id, 1 to durationMs) { a, b -> (a.first + b.first) to maxOf(a.second, b.second) }!!
        runCatching {
            notificationCenter.post(
                kind = dev.ide.ui.backend.UiNotificationKind.SYSTEM,
                title = "${plugin.name} is slowing CodeAssist down",
                body = "It froze the app ${if (count == 1) "once" else "$count times"} this session " +
                    "(longest ${(longest + 500) / 1000} s). If it keeps happening, turn it off in Plugins.",
                target = dev.ide.ui.backend.UiNotificationTarget.Screen(PLUGINS_ROUTE),
                key = "plugin-stall:${plugin.id}",
            )
        }
    }

    override fun timeLabel(): String = runCatching { java.time.LocalTime.now().withNano(0).toString() }.getOrDefault("")

    /** Close the active engine — the host calls this on teardown (window close / activity destroy). */
    /**
     * Release the active engine's rebuildable caches under memory pressure (the host calls this from the
     * system's trim callback). Runs on the engine worker, so it never clears a cache out from under an
     * editor pass mid-flight. [collect] then asks the runtime to collect, for a process that has left the
     * screen: freed objects only shrink its footprint once they are actually collected.
     */
    fun releaseMemory(collect: Boolean) {
        val services = activeServices ?: return
        engineScope.launch(engineDispatcher) {
            runCatching { services.releaseMemory() }
            if (collect) Runtime.getRuntime().gc()
        }
    }

    /**
     * Ship what telemetry is pending: the partial perf and frame windows, then the analytics queue. The host calls
     * this when the app leaves the screen, which on Android is the last moment it reliably gets: the process is
     * usually killed in the background without [close] ever running, and a window that never fills would never
     * be sent. Off the caller's thread, because sending is a network call and the caller is the main thread.
     */
    fun flushTelemetry() {
        analyticsScope.launch(Dispatchers.IO) {
            runCatching { perf.flushAll() }
            runCatching { frames.flushAll() }
            runCatching { frameCpu.flushAll() }
            runCatching { usage.flushAll() }
            runCatching { analytics.flush() }
        }
    }

    fun close() {
        runCatching { Log.removeSink(errorDialogSink) }
        runCatching { analyticsScope.cancel() }
        runCatching { engineScope.cancel() }
        runCatching { perf.flushAll() } // drain partial latency windows so the last session's samples ship
        runCatching { frames.flushAll() }
        runCatching { frameCpu.flushAll() }
        runCatching { usage.flushAll() }
        runCatching { analytics.flush() }
        runCatching { analytics.close() }
        // The version-control backend holds an open repository handle and its own refresh coroutine.
        runCatching { (vcs as? VcsBackend)?.close() }
        runCatching { fsEpochSubscription?.dispose() }
        runCatching { userMessageRelay?.dispose() }
        activeServices?.close()
        runCatching { engineExecutor.shutdown() } // stop the dedicated ide-engine thread on teardown
        // Clean shutdown ⇒ drop the crash breadcrumb, so a file that survives to the next launch means the
        // process did NOT exit cleanly (crashed/killed) — the fallback signal where the OS exit reason is absent.
        runCatching { EngineBreadcrumb.clear() }
    }

    private companion object {
        const val ANALYTICS_CONSENT_PREF = "analytics.consent"
        const val ANALYTICS_SCOPE_PREF = "analytics.consent.scope"
        const val ANALYTICS_SCOPE_USAGE = "usage"
        /** The notification route that opens Settings > Plugins on its Installed tab. */
        const val PLUGINS_ROUTE = "plugins"
    }

    /** Make [next] the active project: swap it in, bump the epoch (re-keys UI state), and close the old one. */
    override fun swapEngine(next: IdeServices) {
        val prev = activeServices
        activeServices = next
        // Point the shared application environment at the now-active engine, for app-level extension callbacks
        // (command actions, synthetic-R, the XML resource host) that resolve the open project through it.
        manager?.env?.activeEngine = next
        followFileSystem(next)
        _projectEpoch.value = ++epochsIssued
        // Publish the project lifecycle for plugin subscribers, on the same app bus. Opened for the new engine;
        // Closed for the one being replaced (before it is disposed). Guarded so a subscriber can't break the swap.
        val bus = messageBus
        if (bus != null) {
            runCatching { bus.syncPublisher(ProjectTopics.LIFECYCLE).onProjectEvent(ProjectEvent.Opened(next.workspaceRoot.toString())) }
            if (prev != null && prev !== next) {
                runCatching { bus.syncPublisher(ProjectTopics.LIFECYCLE).onProjectEvent(ProjectEvent.Closed(prev.workspaceRoot.toString())) }
            }
        }
        if (prev !== next) runCatching { prev?.close() }
    }

    /** The last [projectEpoch] a swap handed out. Never reused, so a reopen after [closeEngine] (which sets the
     *  epoch to 0) still reads as a new project to everything keyed on it. */
    private var epochsIssued = 0

    override fun closeEngine() {
        val prev = activeServices ?: return
        // A build still running would go on writing outputs into the project. Only a runner already made:
        // asking for one now would start one, compiler warm-up included.
        val runner = synchronized(runnerCache) { runnerCache.remove(prev) }
        runCatching { (runner ?: prev.buildRunner).stopBuild() }
        activeServices = null
        manager?.env?.activeEngine = null
        followFileSystem(null)
        _projectEpoch.value = 0
        messageBus?.let { bus ->
            runCatching { bus.syncPublisher(ProjectTopics.LIFECYCLE).onProjectEvent(ProjectEvent.Closed(prev.workspaceRoot.toString())) }
        }
        runCatching { prev.close() }
    }

}

/**
 * Owned render of a self-contained layout [xml] against an EMPTY resource table + no theme — the Learn tab's
 * Android-lesson preview, which visualizes a taught layout with no project open. Built-in + Material renderers
 * handle the tags; unresolved project resources fall back (lesson XML is authored to be self-contained). Never
 * the real-view path (that needs the SDK + a built project). Returns null on any inflation failure.
 */
internal fun renderStandaloneLayout(
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

/**
 * Frames per [IdeServicesBackend.recordFrame] summary event. Was 600, which at 60 to 120 fps closed a window every
 * few seconds of drawing: the two frame metrics were 75% of every analytics event sent, and so most of the
 * requests and log volume the free Supabase plan meters. A window ten times larger keeps the percentiles (they
 * only get steadier) and the jank trend by version; partial windows ship when the app leaves the screen
 * ([IdeServicesBackend.flushTelemetry]).
 */
private const val FRAME_WINDOW = 6000

/**
 * How long the editor's engine must sit idle before the Kotlin compiler warm-up starts. Spans the gap between
 * the index finishing and the editor re-running its passes on the now-ready index, so the warm-up lands after
 * that run rather than in the middle of it.
 */
private val COMPILER_WARMUP_QUIET = 3.seconds

/** The `index_perf` event for one finished index pass. See [IndexPassReport] for what each part means. */
internal fun indexPerfProps(pass: IndexPassReport): Map<String, String> = buildMap {
    val st = pass.status
    put("duration_ms", pass.durationMs.toString())
    put("trigger", pass.trigger)
    put("scope_ms", pass.scopeMs.toString())
    put("queued_ms", pass.queuedMs.toString())
    st.stats?.let { s ->
        put("lib_ms", s.libMs.toString())
        put("src_ms", s.sourceMs.toString())
        put("artifacts", s.artifacts.toString())
        put("artifacts_built", s.artifactsBuilt.toString())
        put("artifacts_reused", s.artifactsReused.toString())
        put("src_files", s.sourceFiles.toString())
        put("src_parsed", s.sourceParsed.toString())
    }
    pass.libraryChanged?.let { put("lib_changed", if (it) "1" else "0") }
    if (pass.coalesced > 0) put("coalesced", pass.coalesced.toString())
    pass.ioConcurrency?.let { put("par_io", it.toString()) }
    pass.buildPermits?.let { put("par_build", it.toString()) }
    for ((kind, n) in pass.built) put("built.$kind", n.toString())
    for ((kind, n) in pass.rekeyed) put("rekeyed.$kind", n.toString())
    // Per-indexer time, keyed by the stable index id (e.g. idx.java.classNames.ms), never a file/artifact/
    // project name. Summed across the threads that ran it (compare with lib_ms and par_io, not as wall time).
    // Skip the trivially-fast ones (<1ms) to keep the row bounded.
    for (b in st.breakdown) if (b.indexMs >= 1) put("idx.${b.id}.ms", b.indexMs.toString())
    // Heap at index completion, comparable to a build's peak across the fleet.
    putAll(MemSample.now().props())
}

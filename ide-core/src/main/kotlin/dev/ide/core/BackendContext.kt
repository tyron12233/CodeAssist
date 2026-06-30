package dev.ide.core

import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/**
 * The shared engine-adapter core the per-service backend impl classes (Stage 2 of the [IdeBackend]
 * decomposition) run over. The aggregator ([IdeServicesBackend]) implements this, so the genuinely-shared
 * state — the swappable active engine, the serialized engine dispatcher + priority scheduler, the
 * epoch-keyed flow factory, the error/analytics surface — lives in one place and the service classes hold
 * only their own concern's methods.
 */
internal interface BackendContext {
    /** The active engine; throws when no project is open (only editor-reachable services use it). */
    val services: IdeServices

    /** The active engine, or null when no project is open (picker-safe services use this). */
    val servicesOrNull: IdeServices?

    /** The shared, APPLICATION-scoped SDK / toolchain download manager — resolved from the [manager] so it's
     *  reachable with no project open (the picker's Settings & Tools hub), falling back to the active engine
     *  on the manager-less (test) path. Null only when neither a manager nor an open project exists. */
    val sdkManager: dev.ide.core.SdkManagerService?

    /** The shared, APPLICATION-scoped signing-keystore registry — same resolution as [sdkManager], so keystore
     *  create/import/validate/delete work from the picker's hub before any project is open. */
    val keystoreRegistry: dev.ide.android.support.tools.KeystoreRegistry?


    /** The build/run engine for [services]: the in-process [IdeServices.buildRunner] by default, or a
     *  host-injected out-of-process runner (the `:build` daemon). The single seam the build service routes
     *  every build/run call through. See docs/build-process-isolation.md. */
    fun buildRunnerFor(services: IdeServices): BuildRunner

    /** The single serialized worker the editor's language calls run on. */
    val engineDispatcher: CoroutineDispatcher

    /** Highest-priority editor lane (completion): preempts background + preview. */
    suspend fun <T> interactive(block: () -> T): T

    /** Background editor lane (analysis/hints/semantic/folding): preempts preview, preempted by interactive. */
    suspend fun <T> background(block: () -> T): T

    /** Lowest-priority editor lane (preview rendering/lowering): preempted by both, retries. */
    suspend fun <T> preview(block: suspend () -> T): T

    /** A [StateFlow] that re-points to the live engine's flow on each project swap (yields [default] with no
     *  project open). */
    fun <T> engineFlow(default: T, select: (IdeServices) -> StateFlow<T>): StateFlow<T>

    /** The project manager (preferences, project list, swap), or null for a single-project host. */
    val manager: ProjectManager?

    /** Bumps when the active project changes (the project service re-exposes it). */
    val projectEpoch: StateFlow<Int>

    /** Make [next] the active engine: swap it in, bump [projectEpoch], close the old one (lifecycle-owned by
     *  the aggregator). Called by the project service's open/create. */
    fun swapEngine(next: IdeServices)

    /** Record an analytics event (gated on consent; no-op otherwise). */
    fun track(event: String, props: Map<String, String>)

    /** Surface an unexpected failure as the non-fatal error dialog. */
    fun showError(title: String, message: String, detail: String)

    /** The current non-fatal error to surface, or null (the diagnostics service re-exposes it). */
    val errorEvents: StateFlow<UiError?>

    /** Dismiss the shown error [id]; surfaces the next queued error. */
    fun dismissError(id: Int)

    /** The file-system epoch flow (bumped by [bumpFileSystemEpoch]); the file service re-exposes it. */
    val fileSystemEpoch: StateFlow<Int>

    /** Bump the file-system epoch so the UI re-reads the tree after a write. */
    fun bumpFileSystemEpoch()

    /** Host-formatted local time-of-day label (e.g. `14:03:21.482`). */
    fun timeLabel(): String

    // Analytics consent is cross-cutting (the settings Privacy page + the diagnostics service + the consent
    // sheet all touch it), so its manager-backed impl lives on the aggregator and is reached through here.
    fun analyticsAvailable(): Boolean
    fun analyticsConsent(): Boolean?
    fun setAnalyticsConsent(granted: Boolean)

    /** Record an editor-latency sample (completion/analysis) into the aggregated perf sampler (flushed by the
     *  aggregator on close). */
    fun recordPerf(event: String, ms: Long)
}

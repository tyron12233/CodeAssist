package dev.ide.analytics

/**
 * Coarse classification of an event. Mirrors the consent categories the user agreed to, and lets the
 * backend keep crash/performance telemetry distinguishable from plain feature usage. Stored as a column
 * so it's queryable without parsing the event name.
 */
enum class EventCategory { ENVIRONMENT, USAGE, PERFORMANCE, CRASH }

/**
 * A single analytics event: a stable [name] (snake_case, from [Events]), its [category], and string-only
 * [props]. Props are intentionally `Map<String, String>` — no nested objects, no numbers-as-objects, and
 * absolutely no user content (source, file/project names, paths). A perf metric goes in as `"duration_ms"
 * -> "1234"`. The hard "never collect" line is enforced at the call sites, not here.
 */
data class AnalyticsEvent(
    val name: String,
    val category: EventCategory,
    val props: Map<String, String> = emptyMap(),
)

/**
 * Device/environment facts attached to every shipped batch (stored as columns, not inside an event).
 * Gathered once per launch by the host. Carries nothing that identifies a person — no ad id, no IMEI,
 * no account, no precise location.
 */
data class DeviceInfo(
    val appVersion: String,
    val appBuild: Int,
    val osApi: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val abi: String,
    val locale: String,
)

/**
 * A batch of events ready to ship, with the shared identity/device context the backend stores as columns.
 * [installId] is a random per-install UUID (not tied to any account); [sessionId] is per-launch.
 */
data class EventBatch(
    val installId: String,
    val sessionId: String,
    val device: DeviceInfo,
    val events: List<AnalyticsEvent>,
)

/**
 * The transport seam: deliver one [batch] to wherever events go (Supabase, a generic HTTP endpoint, a
 * test double). Returns true when the batch was accepted (the service drops it); false → the service
 * keeps it and retries later. Implementations must be synchronous and must never block indefinitely
 * (use timeouts), and must not throw for a routine "couldn't reach the server" — return false instead.
 */
fun interface AnalyticsSink {
    fun send(batch: EventBatch): Boolean
}

/**
 * Records and ships usage analytics — but only while the user has granted consent ([enabled]). Every
 * entry point is a no-op when [enabled] is false, so call sites can [track] freely without guarding.
 * Implementations are thread-safe; [track] returns immediately (delivery is async/batched).
 */
interface AnalyticsService {
    /**
     * Whether collection is allowed (the user granted consent). The host flips this when the user answers
     * the consent prompt or toggles the setting. Setting it to false immediately discards any buffered,
     * not-yet-sent events so revoking consent leaves nothing behind.
     */
    var enabled: Boolean

    /** Record an event. No-op unless [enabled]. */
    fun track(event: AnalyticsEvent)

    /** Best-effort: ship anything buffered right now (e.g. before shutdown or on a crash). */
    fun flush()

    /** Stop background work and release resources. */
    fun close()
}

/** A do-nothing service for hosts without analytics (desktop) or when no transport is configured. */
object NoopAnalyticsService : AnalyticsService {
    override var enabled: Boolean = false
    override fun track(event: AnalyticsEvent) {}
    override fun flush() {}
    override fun close() {}
}

/**
 * Canonical event names, so call sites don't hand-spell strings. Keep these stable — renaming one splits
 * the metric in the backend. The device/environment payload rides on every row, so there's no separate
 * "environment" event; [SESSION_START] is the per-launch anchor.
 */
object Events {
    // performance — the only telemetry collected (plus the crash report below). No feature-usage events.
    const val COLD_START = "cold_start"          // app bootstrap time (per launch)
    const val INDEX_PERF = "index_perf"           // index build duration (per build/reindex)
    const val BUILD_RESULT = "build_result"       // build/run outcome + duration (per build)
    // Aggregated latency summaries (count + mean/p50/p95/max over a window), NOT one event per keystroke.
    // The `_perf` suffix is what [categoryOf] keys on, so new latency metrics classify as PERFORMANCE for free.
    const val COMPLETION_PERF = "completion_perf"
    const val ANALYSIS_PERF = "analysis_perf"

    // crash / stability
    const val APP_CRASH = "app_crash"       // uncaught exception (fatal path)
    const val ERROR_LOGGED = "error_logged"  // a caught ERROR-level failure (non-fatal, scrubbed)

    /** The category an event name belongs to. Everything we emit is PERFORMANCE except stability events. */
    fun categoryOf(name: String): EventCategory = when (name) {
        APP_CRASH, ERROR_LOGGED -> EventCategory.CRASH
        else -> EventCategory.PERFORMANCE
    }
}

package dev.ide.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The three editor "engine" priority lanes, all multiplexed onto one serialized worker. */
enum class EngineLane { INTERACTIVE, BACKGROUND, PREVIEW }

/** Lifecycle phases a lane call passes through, emitted to an [EngineSchedulerObserver]. */
enum class EnginePhase {
    /** The call was submitted (its higher-priority preemptions have already been signalled). */
    QUEUED,
    /** The block actually started running on the engine worker. */
    RUNNING,
    /** The block returned normally. */
    DONE,
    /** The block bailed via [EngineCanceledException] (a higher-priority lane preempted it). */
    PREEMPTED,
}

/** Observes lane lifecycle for diagnostics/tests (the headless contention harness records a timeline from it). */
fun interface EngineSchedulerObserver {
    fun on(lane: EngineLane, phase: EnginePhase, label: String)
}

/**
 * The editor engine's priority scheduler: three lanes over ONE serialized worker so the (thread-unsafe,
 * mutable) analyzers are never touched concurrently, while a latency-critical call can still cut ahead of a
 * heavy one already holding the worker.
 *
 *  1. [interactive] — completion: highest priority, preempts both background and preview; never preempted.
 *  2. [background] — analysis/hints/semantic/folding/signature: preempts preview, preempted by interactive
 *     (surfaces as [EngineCanceledException] — the caller maps that to a "skipped, retry next edit" result).
 *  3. [preview] — preview rendering/lowering: lowest priority, preempted by both; retries automatically.
 *
 * Each lower-priority lane runs its block under a [CancelToken] that a higher-priority caller flips from its
 * own thread BEFORE queuing; the in-flight block bails at its next [EngineCancellation.checkCanceled] poll
 * (between AST nodes — never mid-I/O) and frees the worker. The `*Pending` counters let a lower-priority lane
 * bail immediately at the start of its slot when a higher-priority call is already waiting in the queue.
 *
 * Extracted from the IDE backend so the scheduling/threading policy is testable in isolation (see
 * `EngineSchedulerTest`) and instrumentable as a whole (see the contention harness) — the individual engine
 * calls are each fast; the question is how they interleave on the shared worker.
 */
class EngineScheduler(
    /** The single serialized worker every lane multiplexes onto. Inject a test dispatcher to control it. */
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** Optional lifecycle telemetry (lane occupancy / preemptions). Null in production. */
    private val observer: EngineSchedulerObserver? = null,
    /** How long [preview] waits before retrying after being preempted. */
    private val previewRetryDelay: Duration = 150.milliseconds,
) {
    @Volatile private var backgroundToken: CancelToken? = null
    @Volatile private var previewToken: CancelToken? = null
    private val interactivePending = AtomicInteger(0)
    private val backgroundPending = AtomicInteger(0)

    /** Latency-critical engine work (completion): preempts both background and preview lanes. The block is
     *  `suspend` so a contributor can hop off the worker for I/O (the K2 completion IPC); the serialized
     *  worker frees at the suspension point and the block re-queues on it when the I/O completes. */
    suspend fun <T> interactive(label: String = "", block: suspend () -> T): T {
        observer?.on(EngineLane.INTERACTIVE, EnginePhase.QUEUED, label)
        interactivePending.incrementAndGet()
        backgroundToken?.cancel()
        previewToken?.cancel()
        try {
            return withContext(dispatcher) {
                observer?.on(EngineLane.INTERACTIVE, EnginePhase.RUNNING, label)
                block().also { observer?.on(EngineLane.INTERACTIVE, EnginePhase.DONE, label) }
            }
        } finally {
            interactivePending.decrementAndGet()
        }
    }

    /**
     * Preemptible per-keystroke engine work (analysis/hints/semantic/folding/signature). Preempts the preview
     * lane; throws [EngineCanceledException] when a completion request preempts it — callers map that to their
     * own "skipped" result (re-run on the next edit, or retried by the host). The block is `suspend` for the
     * same reason as [interactive]'s (the K2 diagnostics IPC); the [CancelToken] rides the coroutine context
     * (see [EngineCancellation.withToken]), so a preemption flipped during the off-worker wait is still seen
     * at the next [EngineCancellation.checkCanceled] poll after resumption.
     */
    suspend fun <T> background(label: String = "", block: suspend () -> T): T {
        observer?.on(EngineLane.BACKGROUND, EnginePhase.QUEUED, label)
        backgroundPending.incrementAndGet()
        previewToken?.cancel()
        return try {
            withContext(dispatcher) {
                val token = CancelToken()
                backgroundToken = token
                if (interactivePending.get() > 0) token.cancel()
                try {
                    EngineCancellation.withToken(token) {
                        observer?.on(EngineLane.BACKGROUND, EnginePhase.RUNNING, label)
                        block()
                    }.also { observer?.on(EngineLane.BACKGROUND, EnginePhase.DONE, label) }
                } catch (e: EngineCanceledException) {
                    observer?.on(EngineLane.BACKGROUND, EnginePhase.PREEMPTED, label)
                    throw e
                } finally {
                    if (backgroundToken === token) backgroundToken = null
                }
            }
        } finally {
            backgroundPending.decrementAndGet()
        }
    }

    /**
     * Lowest-priority engine work (preview rendering/lowering). Preempted by both [interactive] and
     * [background]; retries automatically after [previewRetryDelay] so the caller suspends until the engine is
     * free rather than getting a one-shot cancellation. Only [kotlinx.coroutines.CancellationException] (outer
     * coroutine cancelled) escapes — [EngineCanceledException] is handled internally by the retry loop.
     */
    suspend fun <T> preview(label: String = "", block: suspend () -> T): T {
        while (true) {
            observer?.on(EngineLane.PREVIEW, EnginePhase.QUEUED, label)
            try {
                return withContext(dispatcher) {
                    val token = CancelToken()
                    previewToken = token
                    if (interactivePending.get() > 0 || backgroundPending.get() > 0) token.cancel()
                    try {
                        EngineCancellation.withToken(token) {
                            observer?.on(EngineLane.PREVIEW, EnginePhase.RUNNING, label)
                            block()
                        }.also { observer?.on(EngineLane.PREVIEW, EnginePhase.DONE, label) }
                    } finally {
                        if (previewToken === token) previewToken = null
                    }
                }
            } catch (e: EngineCanceledException) {
                observer?.on(EngineLane.PREVIEW, EnginePhase.PREEMPTED, label)
                delay(previewRetryDelay)
            }
        }
    }
}

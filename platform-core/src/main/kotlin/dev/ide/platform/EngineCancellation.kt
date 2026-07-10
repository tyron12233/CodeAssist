package dev.ide.platform

import kotlinx.coroutines.asContextElement

/**
 * Cooperative cancellation for editor "engine" work that runs synchronously on the single engine thread.
 *
 * The editor serializes parse/complete/analyze on one worker so the (thread-unsafe, mutable) analyzers are
 * never touched concurrently. The catch: a heavy per-keystroke pass (full-file resolution/inference) holds
 * that worker, so a latency-critical call queued behind it (code completion) can't run until it finishes.
 *
 * These calls are driven synchronously across a boundary that discards the coroutine context, so coroutine
 * cancellation can't reach them. This primitive fills that gap with a plain thread-local flag: the host
 * installs a [CancelToken] around a preemptible call ([withToken]) on the engine thread, the heavy code polls
 * [checkCanceled] at safe points (between AST nodes — NEVER mid-I/O, which could close a jar channel), and a
 * higher-priority call flips the token from another thread so the in-flight pass bails at its next poll.
 */
object EngineCancellation {
    private val current = ThreadLocal<CancelToken?>()

    /**
     * Run [block] under [token]. Installed as a coroutine [kotlinx.coroutines.asContextElement] rather than
     * a plain thread-local write: a lane block may genuinely suspend mid-run (the K2 IPC hops to
     * `Dispatchers.IO`) and resume on a DIFFERENT worker thread — a raw `set`/restore pair would then leak
     * the token on the original thread (poisoning an unrelated later call scheduled there) and lose it on
     * the resuming one. The context element re-installs/restores the token around every suspension, so
     * synchronous [checkCanceled] polls deep inside the block keep working wherever the coroutine lands.
     */
    suspend fun <T> withToken(token: CancelToken, block: suspend () -> T): T =
        kotlinx.coroutines.withContext(current.asContextElement(token)) { block() }

    /** Throw [EngineCanceledException] if this thread's current call has been preempted; no-op when none. */
    fun checkCanceled() {
        if (current.get()?.canceled == true) throw EngineCanceledException()
    }
}

/** A single engine call's cancellation flag: flipped from another thread, polled on the engine thread. */
class CancelToken {
    @Volatile
    var canceled: Boolean = false
        private set

    fun cancel() {
        canceled = true
    }
}

/** Thrown by [EngineCancellation.checkCanceled] when the current engine call has been preempted. */
class EngineCanceledException : RuntimeException("engine work preempted")

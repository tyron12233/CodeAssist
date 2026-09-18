// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.concurrent.Volatile

/**
 * Cooperative cancellation for editor "engine" work that runs synchronously on the single engine thread.
 *
 * The editor serializes parse/complete/analyze on one worker so the (thread-unsafe, mutable) analyzers are
 * never touched concurrently. The catch: a heavy per-keystroke pass (full-file resolution/inference) holds
 * that worker, so a latency-critical call queued behind it (code completion) cannot run until it finishes.
 *
 * These calls are driven synchronously across a boundary that discards the coroutine context, so coroutine
 * cancellation cannot reach them. This primitive fills that gap: the host installs a [CancelToken] around a
 * preemptible call ([withToken]), the heavy code polls [checkCanceled] at safe points (between AST nodes,
 * NEVER mid-I/O, which could close a jar channel), and a higher-priority call flips the token from another
 * thread so the in-flight pass bails at its next poll.
 *
 * The STORAGE is per-platform, because "the current thread's token" is: see [currentCancelToken].
 */
object EngineCancellation {

    /** Run [block] under [token]. */
    suspend fun <T> withToken(token: CancelToken, block: suspend () -> T): T =
        withCancelToken(token, block)

    /** Throw [EngineCanceledException] if this thread's current call has been preempted; no-op when none. */
    fun checkCanceled() {
        if (currentCancelToken()?.canceled == true) throw EngineCanceledException()
    }
}

/**
 * The token installed for the calling thread, or null.
 *
 * Expect/actual because the two platforms disagree about more than spelling. On the JVM the token rides a
 * coroutine context element over a `ThreadLocal`, which is what makes it survive a block that genuinely
 * SUSPENDS and resumes on a different worker (the K2 IPC hops to `Dispatchers.IO`); a raw set/restore pair
 * would leak the token on the original thread and lose it on the resuming one. Kotlin/Native has no
 * `ThreadLocal` class and no `asContextElement`, so its actual is a `@ThreadLocal` object with set/restore,
 * which is correct for a call that does not hop threads and is all the iOS host can do today.
 */
internal expect fun currentCancelToken(): CancelToken?

internal expect suspend fun <T> withCancelToken(token: CancelToken, block: suspend () -> T): T

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

package dev.codeassist.dart

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets a coroutine that is cancelled stop the process it is waiting on, instead of letting it run on.
 *
 * Stop on a run cancels the coroutine that drives it. Without this the coroutine was cancelled but the
 * compiler or the program it had launched kept going, with nothing left to read its output.
 *
 * Blocking code (a pub, compiler or program run) [attach]es the process it starts; the coroutine side
 * [cancel]s it. Both orders are handled: a cancel that arrives before the process exists kills it as soon as
 * it is attached. The same helper as the NDK plugin's: plugins load on classloaders of their own and cannot
 * share code.
 */
class ToolCancellation {
    private var process: Process? = null
    private var cancelled = false
    private var finished = false

    /** True once the request was cancelled; its output is then incomplete and must not be used. */
    val isCancelled: Boolean
        @Synchronized get() = cancelled

    /** Record [process] as the one to kill. Kills it at once when the request was already cancelled. */
    @Synchronized
    fun attach(process: Process) {
        if (cancelled) process.destroyForcibly() else this.process = process
    }

    /** Kill the attached process, if any. A no-op once the request has [finish]ed. */
    @Synchronized
    fun cancel() {
        if (finished) return
        cancelled = true
        process?.destroyForcibly()
    }

    /** The request is over: nothing is attached any more, and a late cancel must not mark it cancelled. */
    @Synchronized
    fun finish() {
        finished = true
        process = null
    }
}

/**
 * Run the blocking [block] on the IO dispatcher, killing whatever process it [ToolCancellation.attach]es
 * when the calling coroutine is cancelled. Throws [kotlinx.coroutines.CancellationException] in that case,
 * like any other cancelled suspend call, so a cancelled result never reaches a cache.
 */
internal suspend fun <T> killableTool(block: (ToolCancellation) -> T): T = coroutineScope {
    val cancellation = ToolCancellation()
    // UNDISPATCHED so the watcher is registered before the block starts: a cancel that lands in between
    // must still reach the process.
    val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            cancellation.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { block(cancellation) }
    } finally {
        cancellation.finish()
        watcher.cancel()
    }
}

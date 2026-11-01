package dev.codeassist.ndk

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets a coroutine that is cancelled stop the compiler it is waiting on, instead of letting it run on.
 *
 * The analysis engine cancels a file's pass the moment the user types again, and the completion engine
 * does the same to a superseded popup. Without this the coroutine was cancelled but the clang it had
 * launched kept going to the end, on a buffer nobody would see the answer for, competing for the CPU with
 * the request that replaced it. A check on a large file is hundreds of milliseconds on a phone, which is a
 * lot of heat for a result that is thrown away.
 *
 * Blocking code ([NdkToolchain.run]) [attach]es the process it starts; the coroutine side [cancel]s it. Both
 * orders are handled: a cancel that arrives before the process exists kills it as soon as it is attached.
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

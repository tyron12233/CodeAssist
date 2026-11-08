package dev.ide.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Threads for work nobody is waiting on this frame: indexing, usage sweeps, history writes.
 *
 * Each one lowers its own CPU priority when it starts, so on a phone with a few slow cores the UI thread and the
 * garbage collector win the CPU whenever they want it. Under no contention a lowered thread runs exactly as fast,
 * so indexing is not slowed on an idle device. The editor engine (`ide-engine`) is NOT one of these: completion
 * and highlighting run there and someone is waiting on them.
 *
 * How to lower a thread is the host's business ([lowerCurrentThread]). The default does nothing: on Android,
 * `Thread.setPriority` maps to a nice value that, at 10 or above, also moves the thread into the background
 * cpuset (little cores, a small CPU share), which would starve indexing outright. The Android host installs a
 * moderate nice value instead.
 */
object BackgroundThreads {

    /** Lowers the CPU priority of the calling thread. Installed once by the host before the engine starts. */
    @Volatile
    var lowerCurrentThread: () -> Unit = {}

    /** Daemon threads named `<name>-N` that lower their own priority before running anything. */
    fun factory(name: String, numbered: Boolean = true): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r ->
            Thread({
                runCatching { lowerCurrentThread() }
                r.run()
            }, if (numbered) "$name-${n.incrementAndGet()}" else name).apply { isDaemon = true }
        }
    }

    /**
     * A pool of up to [threads] lowered threads, idle ones exiting after [keepAliveSeconds]. Every thread is a
     * core thread (with core timeout), since a [ThreadPoolExecutor] with fewer core threads and an unbounded
     * queue never grows past its core size.
     */
    fun pool(name: String, threads: Int, keepAliveSeconds: Long = 30): ExecutorService =
        ThreadPoolExecutor(
            threads, threads, keepAliveSeconds, TimeUnit.SECONDS, LinkedBlockingQueue(), factory(name),
        ).apply { allowCoreThreadTimeOut(true) }

    /** One lowered thread named exactly [name]. */
    fun single(name: String): ExecutorService = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), factory(name, numbered = false),
    ).apply { allowCoreThreadTimeOut(true) }
}

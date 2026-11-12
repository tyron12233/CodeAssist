package dev.ide.jvm

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Java 21 thread API for a runtime that has none of it: `Thread.ofVirtual()`, `Thread.ofPlatform()`,
 * `Thread.startVirtualThread` and the thread-per-task executors. Android has no virtual threads at any API
 * level, so a program written against them could not start one on a phone.
 *
 * A "virtual" thread here is a daemon PLATFORM thread. That keeps every program that uses the API for what it
 * is usually used for (a task per thread, an executor in a try-with-resources) correct, and gives up the one
 * thing only a real implementation has: being cheap enough to start a hundred thousand of. Each one reserves a
 * real stack, so no more than [maxLive] may be alive at once; past that, starting one fails with a message that
 * says why, where the alternative is the process running out of memory.
 *
 * These are real host objects, not interpreted classes: [HostBackports] returns them from the missing static
 * factories, and the bridge then finds their methods by name like any other host object's, which is why the
 * builder's method names and parameter types are exactly `Thread.Builder`'s.
 */
internal object EmulatedThreads {

    /** How many emulated virtual threads may be alive at once. */
    @Volatile var maxLive: Int = 2_000

    /** The stack each one reserves. Interpreted code recurses on the host stack, so this is deliberately more
     *  than a native program's worker would need, and far less than the main thread's. */
    private const val STACK_BYTES = 2L * 1024 * 1024

    private val live = AtomicInteger()
    private val virtual: MutableSet<Thread> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun isVirtual(thread: Thread): Boolean = thread in virtual

    /** An unstarted emulated virtual thread running [task]. The slot it takes is counted from `start()`. */
    fun newVirtual(task: Runnable, name: String?): Thread {
        val thread = object : Thread(null, null, name ?: "", STACK_BYTES) {
            private var counted = false

            override fun start() {
                if (live.incrementAndGet() > maxLive) {
                    live.decrementAndGet()
                    throw tooMany()
                }
                counted = true
                try {
                    super.start()
                } catch (e: Throwable) {
                    counted = false
                    live.decrementAndGet()
                    // The runtime refusing a thread (`pthread_create` failing) is the same limit, reached sooner.
                    if (e is OutOfMemoryError) throw tooMany() else throw e
                }
            }

            override fun run() {
                try {
                    task.run()
                } finally {
                    if (counted) live.decrementAndGet()
                }
            }
        }
        thread.isDaemon = true
        virtual += thread
        return thread
    }

    private fun tooMany() = UnsupportedOperationException(
        "Virtual threads are emulated with platform threads on this runtime, and no more than $maxLive can be " +
            "alive at once. Run the tasks on a bounded pool (Executors.newFixedThreadPool) instead.",
    )

    /** `Thread.Builder`, for both `ofVirtual()` and `ofPlatform()`. */
    class Builder(private val isVirtual: Boolean) {
        private var prefix: String? = null
        private var counter: AtomicLong? = null
        private var daemon = false
        private var priority = 0
        private var group: ThreadGroup? = null
        private var stack = 0L
        private var handler: Thread.UncaughtExceptionHandler? = null

        fun name(name: String): Builder = apply { prefix = name; counter = null }
        fun name(prefix: String, start: Long): Builder = apply { this.prefix = prefix; counter = AtomicLong(start) }
        fun inheritInheritableThreadLocals(inherit: Boolean): Builder = this
        fun uncaughtExceptionHandler(ueh: Thread.UncaughtExceptionHandler): Builder = apply { handler = ueh }
        fun group(group: ThreadGroup): Builder = apply { this.group = group }
        fun daemon(): Builder = daemon(true)
        fun daemon(on: Boolean): Builder = apply { daemon = on }
        fun priority(priority: Int): Builder = apply { this.priority = priority }
        fun stackSize(stackSize: Long): Builder = apply { stack = stackSize }

        fun unstarted(task: Runnable): Thread {
            val name = prefix?.let { p -> counter?.let { p + it.getAndIncrement() } ?: p }
            val thread = if (isVirtual) newVirtual(task, name) else {
                val made = if (name != null) Thread(group, task, name, stack) else Thread(group, task)
                made.also { it.isDaemon = daemon; if (priority != 0) it.priority = priority }
            }
            handler?.let { thread.uncaughtExceptionHandler = it }
            return thread
        }

        fun start(task: Runnable): Thread = unstarted(task).also { it.start() }

        fun factory(): ThreadFactory = ThreadFactory { unstarted(it) }
    }

    /** `Executors.newThreadPerTaskExecutor(factory)`: a new thread for every task, none reused, no queue. */
    class PerTaskExecutor(private val factory: ThreadFactory) : AbstractExecutorService() {
        private val threads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
        @Volatile private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("the executor has been shut down")
            lateinit var thread: Thread
            thread = factory.newThread {
                try {
                    command.run()
                } finally {
                    threads.remove(thread)
                }
            } ?: throw RejectedExecutionException("the thread factory returned no thread")
            threads += thread
            try {
                thread.start()
            } catch (e: Throwable) {
                threads.remove(thread)
                throw e
            }
        }

        override fun shutdown() { shutdown = true }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            threads.forEach { it.interrupt() }
            return emptyList()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && threads.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (true) {
                val next = threads.firstOrNull() ?: return true
                val left = deadline - System.nanoTime()
                if (left <= 0) return false
                TimeUnit.NANOSECONDS.timedJoin(next, left)
                if (!next.isAlive) threads.remove(next)
            }
        }

        /** `ExecutorService.close()` (Java 19): no new tasks, then wait for the running ones. */
        fun close() = closeExecutor(this)
    }

    /** What `ExecutorService.close()` does, for any executor on a runtime whose interface has no such method:
     *  shut down, wait for the tasks, and on an interrupt cancel them and keep the interrupt. */
    fun closeExecutor(executor: java.util.concurrent.ExecutorService) {
        executor.shutdown()
        var interrupted = false
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS)
            } catch (e: InterruptedException) {
                if (!interrupted) { executor.shutdownNow(); interrupted = true }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

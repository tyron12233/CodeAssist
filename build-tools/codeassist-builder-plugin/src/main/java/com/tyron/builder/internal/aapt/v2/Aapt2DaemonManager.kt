package com.tyron.builder.internal.aapt.v2

import com.android.utils.ILogger
import com.google.common.base.Preconditions
import com.google.common.base.Ticker
import com.android.ide.common.resources.CompileResourceRequest
import com.tyron.builder.internal.aapt.AaptConvertConfig
import com.tyron.builder.internal.aapt.AaptPackageConfig
import java.io.Closeable
import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.GuardedBy
import javax.annotation.concurrent.NotThreadSafe
import javax.annotation.concurrent.ThreadSafe

/**
 * Maintains a pool of AAPT2 daemon processes.
 *
 * The pool is expanded if all processes are busy when a request is received.
 * No attempt is made to limit the size of the pool.
 * Users of this class are expected to manage the desired concurrency level through the use of
 * gradle workers, a thread pool or similar.
 *
 * Users of this class are expected to call leaseProcess on a worker thread in a
 * try-with-resources/use block. The operations on the [LeasedAaptDaemon] are then blocking on that
 * thread.
 */
@ThreadSafe
class Aapt2DaemonManager(
    val logger: ILogger,
    private val daemonFactory: (Int) -> Aapt2Daemon,
    expiryTime: Long,
    expiryTimeUnit: TimeUnit,
    private val listener: Listener = NoOpListener(),
    private val timeSource: Ticker = Ticker.systemTicker()) {

    private val expiryTimeNanoSeconds = expiryTimeUnit.toNanos(expiryTime)

    @GuardedBy("this")
    private var latestDisplayId: Int = 0

    @GuardedBy("this")
    private val pool: MutableList<LeasableAaptDaemon> = mutableListOf()

    internal class LeasableAaptDaemon(
            val daemon: Aapt2Daemon,
            var lastUsedNanoSeconds: Long,
            var busy: Boolean = false) {
        fun shutdown() = daemon.shutDown()
    }

    /**
     * Returns a [LeasedAaptDaemon], either from a pool of started daemons,
     * Or starting a new process if all the existing daemons in the pool are in use
     *
     * Blocks until the daemon is ready to receive commands.
     */
    @Synchronized
    fun leaseDaemon(): LeasedAaptDaemon {
        val daemon =
                pool.find { !it.busy } ?: newAaptDaemon()
        daemon.busy = true
        return LeasedAaptDaemon(daemon, this::returnProcess)
    }

    /**
     * Checks if any processes are not needed in the pool any more and blocks while they shut down.
     */
    // Not synchronized so that the daemons shutting down does not block other requests.
    fun maintain() {
        val expiredDaemons = takeExpiredDaemonsFromPool()
        expiredDaemons.forEach { it.shutdown() }
    }

    /** Shuts down this AAPT process manager. */
    @Synchronized
    fun shutdown() {
        if (pool.any { it.busy }) {
            error("AAPT Process manager cannot be shut down while daemons are in use")
        }
        if (!pool.isEmpty()) {
            listener.lastDaemonStopped()
        }
        pool.forEach { it.shutdown() }
        pool.clear()
    }

    @GuardedBy("this")  // Only called from leaseDaemon, so already synchronized
    private fun newAaptDaemon(): LeasableAaptDaemon {
        val displayId = latestDisplayId++
        val process = daemonFactory.invoke(displayId)
        val daemon = LeasableAaptDaemon(process, timeSource.read())
        if (pool.isEmpty()) {
            listener.firstDaemonStarted(this)
        }
        pool.add(daemon)
        return daemon
    }

    /** Marks the process as returned, and updates when it was last used */
    @Synchronized
    private fun returnProcess(key: LeasableAaptDaemon) {
        if (key.daemon.state != Aapt2Daemon.State.RUNNING) {
            // If the daemon was not started or has stopped there's no point keeping it in the pool.
            // No need to shut it down either, that would have already happened if needed.
            pool.remove(key)
            if (pool.isEmpty()) {
                listener.lastDaemonStopped()
            }
            return
        }
        key.lastUsedNanoSeconds = timeSource.read()
        key.busy = false
    }

    @Synchronized
    private fun takeExpiredDaemonsFromPool(): List<LeasableAaptDaemon> {
        val expired = mutableListOf<LeasableAaptDaemon>()
        val expireHorizon = timeSource.read() - expiryTimeNanoSeconds
        val oldPool = ArrayList(pool)
        pool.clear()
        for (daemon in oldPool) {
            when {
            // Daemons that are in use, or have been used recently are kept
                daemon.busy || daemon.lastUsedNanoSeconds >= expireHorizon -> pool.add(daemon)
                else -> expired.add(daemon)
            }
        }
        if (pool.isEmpty()) {
            listener.lastDaemonStopped()
        }
        return expired
    }

    /**
     * A wrapper for an AAPT daemon that can return it to the pool of daemons once the processes
     * are finished.
     *
     * The underlying daemon is exclusively owned by the thread that called "leaseDaemon()" until
     * it is closed, after which it is returned to the pool and this lease object can no longer be
     * used.
     */
    @NotThreadSafe
    class LeasedAaptDaemon internal constructor(
            private val leasableDaemon: LeasableAaptDaemon,
            private val closeAction: (LeasableAaptDaemon) -> Unit) : Aapt2, Closeable {

        private var leaseValid = true

        @Throws(Aapt2Exception::class)
        override fun compile(request: CompileResourceRequest, logger: ILogger) {
            Preconditions.checkState(leaseValid, "Leased process is already closed")
            leasableDaemon.daemon.compile(request, logger)
        }

        @Throws(Aapt2Exception::class)
        override fun link(request: AaptPackageConfig, logger: ILogger) {
            Preconditions.checkState(leaseValid, "Leased process is already closed")
            leasableDaemon.daemon.link(request, logger)
        }

        @Throws(Aapt2Exception::class)
        override fun convert(request: AaptConvertConfig, logger: ILogger) {
            Preconditions.checkState(leaseValid, "Leased process is already closed")
            leasableDaemon.daemon.convert(request, logger)
        }

        override fun close() {
            Preconditions.checkState(leaseValid, "Leased process is already closed")
            closeAction(leasableDaemon)
            leaseValid = false
        }
    }

    /** An AAPT2 daemon that uses this manager to lease a daemon on each invocation */
    val leasingAapt2Daemon = object : Aapt2 {
        override fun compile(request: CompileResourceRequest, logger: ILogger) {
            leaseDaemon().use { it.compile(request, logger) }
        }

        override fun link(request: AaptPackageConfig, logger: ILogger) {
            leaseDaemon().use { it.link(request, logger) }
        }

        override fun convert(request: AaptConvertConfig, logger: ILogger) {
            leaseDaemon().use { it.convert(request, logger) }
        }
    }

    /**
     * This is intended to be used to schedule maintenance.
     *
     * The maintenance service should be started on [firstDaemonStarted] and stopped on [lastDaemonStopped].
     *
     * Events may come from arbitrary threads, so implementations must be thread safe.
     */
    interface Listener {
        fun firstDaemonStarted(manager: Aapt2DaemonManager)
        fun lastDaemonStopped()
    }

    class NoOpListener : Listener {
        override fun firstDaemonStarted(manager: Aapt2DaemonManager) {
        }

        override fun lastDaemonStopped() {
        }
    }

    @Synchronized
    fun stats(): Stats = Stats(poolSize = pool.size, busyCount = pool.count { it.busy })

    data class Stats(val poolSize: Int, val busyCount: Int)

}
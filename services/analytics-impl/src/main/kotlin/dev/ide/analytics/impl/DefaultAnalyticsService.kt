package dev.ide.analytics.impl

import dev.ide.analytics.AnalyticsEvent
import dev.ide.analytics.AnalyticsService
import dev.ide.analytics.AnalyticsSink
import dev.ide.analytics.DeviceInfo
import dev.ide.analytics.EventBatch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The default [AnalyticsService]: a bounded, durable event buffer drained to an [AnalyticsSink] by a single
 * daemon thread.
 *
 *  - **Consent-gated.** [track] and [flush] no-op unless [enabled]; flipping [enabled] to false discards the
 *    buffer (and rewrites the on-disk queue empty) so revoking consent leaves nothing pending.
 *  - **Durable.** Each enqueue/drain rewrites [queueFile] (NDJSON-ish, see [EventQueueCodec]) atomically, so
 *    events survive an app kill or an offline stretch and ship on the next run. Loaded on construction.
 *  - **Bounded.** The buffer is capped at [maxBuffer]; once full the oldest events are dropped (analytics is
 *    never allowed to grow without limit). A drop is silent — losing telemetry must never affect the IDE.
 *  - **Batched.** Events flush when the buffer reaches [batchSize] or every [flushIntervalMs]; a failed send
 *    is retried on the next tick (the batch is returned to the front of the buffer).
 *
 * Network I/O happens off the monitor lock, so [track] from the UI/engine threads never blocks on the wire.
 */
class DefaultAnalyticsService(
    private val installId: String,
    private val sessionId: String,
    private val device: DeviceInfo,
    private val sink: AnalyticsSink,
    initialConsent: Boolean,
    private val queueFile: Path? = null,
    private val batchSize: Int = 20,
    private val maxBuffer: Int = 500,
    private val flushIntervalMs: Long = 30_000,
) : AnalyticsService {

    private val lock = Any()
    private val buffer = ArrayDeque<AnalyticsEvent>()

    @Volatile
    private var enabledFlag: Boolean = initialConsent

    @Volatile
    private var closed = false

    private val wakeMonitor = Object()

    private val worker = Thread({ runLoop() }, "analytics-flush").apply { isDaemon = true }

    init {
        // Restore anything that didn't ship last run (best-effort; a corrupt buffer is dropped, not fatal).
        queueFile?.let { f ->
            runCatching {
                if (Files.exists(f)) {
                    Files.readAllLines(f).forEach { line -> EventQueueCodec.decode(line)?.let { buffer.addLast(it) } }
                }
            }
        }
        worker.start()
    }

    override var enabled: Boolean
        get() = enabledFlag
        set(value) {
            synchronized(lock) {
                enabledFlag = value
                if (!value) { buffer.clear(); persistLocked() } // revoke → drop everything pending
            }
        }

    override fun track(event: AnalyticsEvent) {
        if (!enabledFlag) return
        val shouldFlushNow = synchronized(lock) {
            buffer.addLast(event)
            while (buffer.size > maxBuffer) buffer.removeFirst() // drop oldest beyond the cap
            persistLocked()
            buffer.size >= batchSize
        }
        if (shouldFlushNow) wake()
    }

    override fun flush() {
        if (!enabledFlag || closed) return
        while (!closed) {
            val batch = synchronized(lock) {
                if (buffer.isEmpty()) return
                val n = minOf(buffer.size, batchSize)
                val take = ArrayList<AnalyticsEvent>(n)
                repeat(n) { take.add(buffer.removeFirst()) }
                persistLocked()
                take
            }
            val ok = runCatching { sink.send(EventBatch(installId, sessionId, device, batch)) }.getOrDefault(false)
            if (!ok) {
                // Couldn't ship: return the batch to the front (preserving order) and stop until the next tick.
                synchronized(lock) {
                    for (i in batch.indices.reversed()) buffer.addFirst(batch[i])
                    while (buffer.size > maxBuffer) buffer.removeLast()
                    persistLocked()
                }
                return
            }
        }
    }

    override fun close() {
        closed = true
        wake()
        runCatching { worker.join(2_000) }
    }

    private fun wake() = synchronized(wakeMonitor) { wakeMonitor.notifyAll() }

    private fun runLoop() {
        while (!closed) {
            synchronized(wakeMonitor) { runCatching { wakeMonitor.wait(flushIntervalMs) } }
            if (closed) break
            runCatching { flush() }
        }
    }

    /** Rewrite the queue file from the current buffer. Caller holds [lock]. Atomic via a temp + move. */
    private fun persistLocked() {
        val f = queueFile ?: return
        runCatching {
            Files.createDirectories(f.parent)
            val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
            Files.write(tmp, buffer.map { EventQueueCodec.encode(it) })
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

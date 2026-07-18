package dev.ide.android

import android.net.LocalServerSocket
import android.net.LocalSocket
import dev.ide.core.AppLogChannel
import dev.ide.core.AppLogEntry
import dev.ide.core.AppLogEvent
import dev.ide.core.AppLogSnapshot
import dev.ide.core.AppLogWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device [AppLogChannel]: hosts a [LocalServerSocket] in the abstract namespace ([AppLogWire.SOCKET_NAME])
 * that the log bridge injected into a running debug app connects to, decodes the wire frames with the pure
 * [AppLogWire], and publishes a live [AppLogSnapshot].
 *
 * A single server serves every project (the socket name is device-global). Only the connection whose HELLO
 * package matches the currently-launched app ([activePackage], set by [start]) contributes — stray or stale
 * connections are dropped. Emissions are coalesced (~10/s) so a chatty app can't thrash the UI or the flow.
 */
class AppLogChannelImpl : AppLogChannel {
    private val _logs = MutableStateFlow(AppLogSnapshot())
    override val logs: StateFlow<AppLogSnapshot> get() = _logs

    @Volatile private var activePackage: String? = null
    private val liveConnections = AtomicInteger(0)

    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>()
    private var totalAppended = 0L // guarded by lock; monotonic within a session, reset on start/clear
    @Volatile private var dirty = false

    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var flushThread: Thread? = null

    @Synchronized
    override fun start(packageName: String) {
        activePackage = packageName
        synchronized(lock) { entries.clear(); totalAppended = 0 }
        liveConnections.set(0)
        _logs.value = AppLogSnapshot(entries = emptyList(), connected = false, packageName = packageName, totalAppended = 0)
        ensureServer()
    }

    override fun clear() {
        synchronized(lock) { entries.clear(); totalAppended = 0 }
        _logs.value = _logs.value.copy(entries = emptyList(), totalAppended = 0)
    }

    @Synchronized
    override fun stop() {
        activePackage = null
        synchronized(lock) { entries.clear() }
        _logs.value = AppLogSnapshot()
        try { server?.close() } catch (_: Throwable) {}
        server = null
        acceptThread?.interrupt(); acceptThread = null
        flushThread?.interrupt(); flushThread = null
    }

    private fun ensureServer() {
        if (server != null) return
        val s = try {
            LocalServerSocket(AppLogWire.SOCKET_NAME)
        } catch (e: IOException) {
            // The abstract name is already bound (e.g. a lingering server from a prior run). Give up quietly;
            // logs stay empty for this session rather than crashing the Run.
            return
        }
        server = s
        acceptThread = Thread({ acceptLoop(s) }, "ide-applog-accept").apply { isDaemon = true; start() }
        flushThread = Thread({ flushLoop() }, "ide-applog-flush").apply { isDaemon = true; start() }
    }

    private fun acceptLoop(s: LocalServerSocket) {
        while (!Thread.currentThread().isInterrupted) {
            val socket = try { s.accept() } catch (e: IOException) { break }
            Thread({ readConnection(socket) }, "ide-applog-conn").apply { isDaemon = true; start() }
        }
    }

    private fun readConnection(socket: LocalSocket) {
        var counted = false
        try {
            val input = socket.inputStream.buffered()
            val hello = AppLogWire.parse(AppLogWire.readFrame(input) ?: return) as? AppLogEvent.Hello ?: return
            if (hello.packageName != activePackage) return // not the app we launched
            liveConnections.incrementAndGet(); counted = true
            _logs.value = _logs.value.copy(connected = true, packageName = hello.packageName)
            while (true) {
                val ev = AppLogWire.parse(AppLogWire.readFrame(input) ?: break)
                if (ev is AppLogEvent.Record && hello.packageName == activePackage) append(ev.entry)
            }
        } catch (_: Throwable) {
            // dropped / malformed connection — fall through to cleanup
        } finally {
            try { socket.close() } catch (_: Throwable) {}
            if (counted && liveConnections.decrementAndGet() <= 0) {
                _logs.value = _logs.value.copy(connected = false)
            }
        }
    }

    private fun append(entry: AppLogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            totalAppended++
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        dirty = true
    }

    private fun flushLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try { Thread.sleep(FLUSH_MS) } catch (e: InterruptedException) { return }
            if (!dirty) continue
            dirty = false
            val snapshot: List<AppLogEntry>
            val total: Long
            synchronized(lock) { snapshot = entries.toList(); total = totalAppended }
            _logs.value = _logs.value.copy(entries = snapshot, totalAppended = total)
        }
    }

    companion object {
        private const val MAX_ENTRIES = 5000
        private const val FLUSH_MS = 100L
    }
}

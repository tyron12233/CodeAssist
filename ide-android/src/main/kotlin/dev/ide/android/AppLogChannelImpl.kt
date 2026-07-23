package dev.ide.android

import dev.ide.core.AppLogChannel
import dev.ide.core.AppLogEntry
import dev.ide.core.AppLogEvent
import dev.ide.core.AppLogSnapshot
import dev.ide.core.AppLogWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * On-device [AppLogChannel]: receives the log frames the bridge injected into a running debug app pushes over
 * Binder (through the exported [dev.ide.android.applog.AppLogSinkService]), decodes them with the pure
 * [AppLogWire], and publishes a live [AppLogSnapshot]. A raw `LocalSocket` can't be used — SELinux denies one
 * untrusted app connecting to another's abstract socket — so the transport is Binder; this channel is the sink
 * the (system-instantiated) service routes to via [AppLogSinkRegistry] (both live in the IDE process).
 *
 * Only frames whose HELLO package matches the currently-launched app ([activePackage], set by [start])
 * contribute — stray or stale connections are dropped. Emissions are coalesced (~10/s) so a chatty app can't
 * thrash the UI or the flow.
 */
class AppLogChannelImpl : AppLogChannel {
    private val _logs = MutableStateFlow(AppLogSnapshot())
    override val logs: StateFlow<AppLogSnapshot> get() = _logs

    @Volatile private var activePackage: String? = null
    /** The package whose HELLO we accepted for the current connection; gates records against [activePackage]. */
    @Volatile private var connectedPackage: String? = null

    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>()
    private var totalAppended = 0L // guarded by lock; monotonic within a session, reset on start/clear
    @Volatile private var dirty = false

    @Volatile private var flushThread: Thread? = null

    @Synchronized
    override fun start(packageName: String) {
        activePackage = packageName
        connectedPackage = null
        synchronized(lock) { entries.clear(); totalAppended = 0 }
        _logs.value = AppLogSnapshot(entries = emptyList(), connected = false, packageName = packageName, totalAppended = 0)
        AppLogSinkRegistry.active = this
        ensureFlush()
    }

    override fun clear() {
        synchronized(lock) { entries.clear(); totalAppended = 0 }
        _logs.value = _logs.value.copy(entries = emptyList(), totalAppended = 0)
    }

    @Synchronized
    override fun stop() {
        if (AppLogSinkRegistry.active === this) AppLogSinkRegistry.active = null
        activePackage = null
        connectedPackage = null
        synchronized(lock) { entries.clear() }
        _logs.value = AppLogSnapshot()
        flushThread?.interrupt(); flushThread = null
    }

    /**
     * A batch of wire payloads pushed by the bound bridge (via [dev.ide.android.applog.AppLogSinkService]). A
     * HELLO for the active package marks the connection live; subsequent records append while the connection's
     * package still matches the launched app (a stale app from a prior run is dropped).
     */
    fun acceptFrames(frames: List<String>) {
        for (payload in frames) {
            when (val ev = AppLogWire.parse(payload)) {
                is AppLogEvent.Hello ->
                    if (ev.packageName == activePackage) {
                        connectedPackage = ev.packageName
                        _logs.value = _logs.value.copy(connected = true, packageName = ev.packageName)
                    }
                is AppLogEvent.Record ->
                    if (connectedPackage != null && connectedPackage == activePackage) append(ev.entry)
                null -> {} // malformed / unrecognized frame — ignore
            }
        }
    }

    /** The bound bridge went away (its process died or it unbound). Mark the stream not-connected. */
    fun onClientDisconnected() {
        connectedPackage = null
        _logs.value = _logs.value.copy(connected = false)
    }

    private fun append(entry: AppLogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            totalAppended++
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        dirty = true
    }

    private fun ensureFlush() {
        if (flushThread != null) return
        flushThread = Thread({ flushLoop() }, "ide-applog-flush").apply { isDaemon = true; start() }
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

/**
 * Process-global handle to the active [AppLogChannelImpl] so the system-instantiated
 * [dev.ide.android.applog.AppLogSinkService] can route Binder submits to it. The IDE and the service run in
 * the same process, so a plain singleton suffices; it is set for the duration of a run ([AppLogChannelImpl.start])
 * and cleared on [AppLogChannelImpl.stop].
 */
object AppLogSinkRegistry {
    @Volatile var active: AppLogChannelImpl? = null
}

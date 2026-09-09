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
 * Only frames whose HELLO package is one of the [accepted] applicationIds (set by [watch] from the open
 * project's Android apps) contribute — stray binds from other apps are dropped. A HELLO from a NEW app process
 * (a pid not seen for the current session) begins a fresh session (clears the buffer); a reconnect of the same
 * process keeps it. Records are gated by that session's pid, so only the connected app's lines append.
 * Emissions are coalesced (~10/s) so a chatty app can't thrash the UI or the flow.
 */
class AppLogChannelImpl : AppLogChannel {
    private val _logs = MutableStateFlow(AppLogSnapshot())
    override val logs: StateFlow<AppLogSnapshot> get() = _logs

    /** applicationIds this channel accepts a HELLO from (the open project's Android apps, all variants). */
    @Volatile private var accepted: Set<String> = emptySet()
    /** The package + process of the current live session; records are gated on this pid. */
    @Volatile private var sessionPackage: String? = null
    @Volatile private var sessionPid: Int = -1

    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>()
    private var totalAppended = 0L // guarded by lock; monotonic within a session, reset on new session/clear
    @Volatile private var dirty = false

    @Volatile private var flushThread: Thread? = null

    @Synchronized
    override fun watch(packages: Set<String>) {
        accepted = packages
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
        accepted = emptySet()
        sessionPackage = null
        sessionPid = -1
        synchronized(lock) { entries.clear() }
        _logs.value = AppLogSnapshot()
        flushThread?.interrupt(); flushThread = null
    }

    /**
     * A batch of wire payloads pushed by the bound bridge (via [dev.ide.android.applog.AppLogSinkService]). A
     * HELLO from a watched applicationId marks the connection live — a new process (fresh pid) resets the buffer
     * to that session; records then append while their pid matches the session (a stray app's frames are dropped).
     */
    fun acceptFrames(frames: List<String>) {
        for (payload in frames) {
            when (val ev = AppLogWire.parse(payload)) {
                is AppLogEvent.Hello ->
                    if (ev.packageName in accepted) {
                        val newSession = ev.packageName != sessionPackage || ev.pid != sessionPid
                        sessionPackage = ev.packageName
                        sessionPid = ev.pid
                        if (newSession) {
                            synchronized(lock) { entries.clear(); totalAppended = 0 }
                            _logs.value = AppLogSnapshot(connected = true, packageName = ev.packageName, totalAppended = 0)
                        } else {
                            _logs.value = _logs.value.copy(connected = true, packageName = ev.packageName)
                        }
                    }
                is AppLogEvent.Record ->
                    if (sessionPackage != null && ev.entry.pid == sessionPid) append(ev.entry)
                null -> {} // malformed / unrecognized frame — ignore
            }
        }
    }

    /** The bound bridge went away (its process died or it unbound). Mark the stream not-connected; keep the
     *  session identity so a same-process reconnect resumes (only a new pid starts a fresh session). */
    fun onClientDisconnected() {
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
 * Process-global handle to the UI-process [AppLogChannelImpl] the exported sink service routes frames to. Set
 * by [AppLogChannelImpl.watch] (the engine configures it on project open, independent of any build/run) and
 * cleared on [AppLogChannelImpl.stop]. App-log capture is a device-global, UI-process concern — the sink
 * ([dev.ide.android.applog.AppLogSinkService]) always runs in the UI process and feeds this channel directly,
 * so capture no longer depends on the build daemon (the old `:build` relay is gone).
 */
object AppLogSinkRegistry {
    @Volatile var active: AppLogChannelImpl? = null
}

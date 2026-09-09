package dev.ide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A log priority, mapped from a logcat priority letter / a captured stream. */
enum class AppLogLevel(val letter: Char) {
    VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E');

    companion object {
        /** The level for a logcat priority letter (V/D/I/W/E/F/S…); unknowns → [INFO]. */
        fun of(letter: Char): AppLogLevel = when (letter) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARN
            'E', 'F' -> ERROR // F(atal) shows as an error
            else -> INFO
        }
    }
}

/** One forwarded log line from a running (debug) app. [tag] is the logcat tag (or `System.out`/`AndroidRuntime`). */
data class AppLogEntry(
    val timestampMs: Long,
    val pid: Int,
    val tid: Int,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
)

/** The current app-log state: the recent [entries] ring buffer + whether an app is [connected]. */
data class AppLogSnapshot(
    val entries: List<AppLogEntry> = emptyList(),
    val connected: Boolean = false,
    /** The package of the app whose logs are being shown (the last-launched debug app), or null. */
    val packageName: String? = null,
    /** Monotonic count of ALL entries ever appended in this session, BEFORE ring-buffer trimming — so a
     *  cross-process consumer can compute which [entries] are new even after the buffer drops old ones
     *  (the held entries span global indices `[totalAppended - entries.size, totalAppended)`). Resets to 0
     *  on a new session ([AppLogChannel.start]) / [AppLogChannel.clear], which the consumer reads as a reset. */
    val totalAppended: Long = 0,
)

/**
 * The on-device channel that receives a running debug app's logs (forwarded by the injected
 * [dev.ide.android.support.tools.AndroidAppLogRuntime] bridge over Binder) and exposes them as a live [logs]
 * snapshot. A platform port, supplied by `:ide-android`; absent on the desktop, where there is no app to run.
 *
 * **Capture is decoupled from the Run button.** The IDE calls [watch] with the open project's Android app
 * applicationIds (all variants) on project open and whenever the model changes; the channel then starts/continues
 * a live session for whichever of those packages connects. So logs show whether the app was launched from the
 * IDE's Run button OR straight from the device launcher — a new app process (fresh pid) begins a fresh session
 * (clears the buffer); a reconnect of the same process keeps it. [clear]/[stop] back the Logcat UI + project close.
 */
interface AppLogChannel {
    /** The live buffer + connection state, rendered by the "Logcat" console tab. */
    val logs: StateFlow<AppLogSnapshot>

    /** Set the app applicationIds whose forwarded logs this channel accepts (the open project's Android apps).
     *  Idempotent; registers this channel as the active sink. Does NOT clear the buffer — a session begins when
     *  a watched app connects (its HELLO), so a passive re-watch on a model change never drops live logs. */
    fun watch(packages: Set<String>)

    /** Clear the current buffer (keeps listening). */
    fun clear()

    /** Stop listening and clear (e.g. the IDE closed the project). */
    fun stop()
}

/** The no-op channel used when no host supplied one (desktop / tests): always empty, controls are inert. */
internal object NoopAppLogChannel : AppLogChannel {
    override val logs: StateFlow<AppLogSnapshot> = MutableStateFlow(AppLogSnapshot())
    override fun watch(packages: Set<String>) {}
    override fun clear() {}
    override fun stop() {}
}

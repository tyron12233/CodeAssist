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
 * [dev.ide.android.support.tools.AndroidAppLogRuntime] bridge over an abstract-namespace `LocalSocket`) and
 * exposes them as a live [logs] snapshot. A platform port, supplied by `:ide-android` (it needs Android's
 * `LocalServerSocket`); absent on the desktop, where there is no app to run.
 *
 * The IDE calls [start] with the package it is about to launch (which resets the buffer to that session), and
 * [clear]/[stop] from the Logcat UI. The bridge inside the app connects to the server socket this port hosts.
 */
interface AppLogChannel {
    /** The live buffer + connection state, rendered by the "Logcat" console tab. */
    val logs: StateFlow<AppLogSnapshot>

    /** Begin a fresh capture session for [packageName] (clears the buffer, ensures the server socket is up). */
    fun start(packageName: String)

    /** Clear the current buffer (keeps listening). */
    fun clear()

    /** Stop listening and clear (e.g. the IDE closed the project). */
    fun stop()
}

/** The no-op channel used when no host supplied one (desktop / tests): always empty, controls are inert. */
internal object NoopAppLogChannel : AppLogChannel {
    override val logs: StateFlow<AppLogSnapshot> = MutableStateFlow(AppLogSnapshot())
    override fun start(packageName: String) {}
    override fun clear() {}
    override fun stop() {}
}

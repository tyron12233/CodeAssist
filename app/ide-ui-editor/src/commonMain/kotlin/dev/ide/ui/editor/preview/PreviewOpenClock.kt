package dev.ide.ui.editor.preview

import dev.ide.ui.concurrent.UiLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When each step of opening a file's preview first happened, in milliseconds since its pane opened. The pane
 * marks its own steps (variants detected, index ready) and the platform host marks the rest (lowered, session
 * open, first frame), so one log line can show where the time to a first picture went.
 *
 * Keyed by file path. Only the FIRST occurrence of a mark is kept, so a re-render after an edit doesn't move it.
 */
object PreviewOpenClock {
    private class Entry(val opened: TimeMark) {
        val marks = LinkedHashMap<String, Long>()
    }

    private val entries = HashMap<String, Entry>()

    // Not `synchronized`: that is JVM-only, and this is common code the iOS host compiles too.
    private val lock = UiLock()

    /** The pane for [path] opened: starts its clock over, dropping any marks from an earlier open. */
    fun paneOpened(path: String) {
        lock.withLock { entries[path] = Entry(TimeSource.Monotonic.markNow()) }
    }

    /** Record [name] for [path] at the current time, unless it was already recorded since the pane opened. */
    fun mark(path: String, name: String) {
        lock.withLock {
            val e = entries[path] ?: return@withLock
            if (name !in e.marks) e.marks[name] = e.opened.elapsedNow().inWholeMilliseconds
        }
    }

    /** Milliseconds since the pane for [path] opened, or null when it never did. */
    fun sincePaneOpened(path: String): Long? =
        lock.withLock { entries[path]?.opened?.elapsedNow()?.inWholeMilliseconds }

    /** The marks recorded for [path] so far, in the order they happened (`name=+ms`). */
    fun describe(path: String): String =
        lock.withLock { entries[path]?.marks?.entries?.joinToString(" ") { "${it.key}=+${it.value}" } ?: "" }
}

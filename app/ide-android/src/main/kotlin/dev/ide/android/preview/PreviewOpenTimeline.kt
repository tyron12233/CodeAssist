package dev.ide.android.preview

import android.os.SystemClock
import android.util.Log
import dev.ide.ui.editor.preview.PreviewOpenClock

/** Logcat tag for preview timing lines from both the IDE and the `:preview` process (the app's own log facade
 *  doesn't reach logcat, and a device benchmark reads these lines with `adb logcat -s`). */
internal const val PREVIEW_TIMING_TAG = "CAPreviewTiming"

/**
 * The steps of one Compose preview host mount (a file's preview opening, or a switch to another variant) up to
 * its first frame. Finished once: the first frame, a fallback, or a not-interpretable answer ends it, writes one
 * logcat line with every step, and reports the split to analytics through [record].
 *
 * Marks are milliseconds since this mount; the pane's own steps (variants detected, index ready) come from
 * [PreviewOpenClock], measured from the pane opening, which is what the user waits on for a first open.
 */
internal class PreviewOpenTimeline(
    private val path: String,
    private val variant: String,
    /** The `:preview` process was already running when this mount began. */
    private val procWarm: Boolean,
    private val record: (event: String, ms: Long, dims: Map<String, String>) -> Unit,
) {
    private val t0 = SystemClock.elapsedRealtime()
    private val marks = LinkedHashMap<String, Long>()
    private var finished = false

    fun mark(name: String) {
        synchronized(marks) { if (!finished && name !in marks) marks[name] = SystemClock.elapsedRealtime() - t0 }
    }

    /** End the timeline: [outcome] is `remote`, `inprocess`, `fallback` or `notInterpretable`. */
    fun finish(outcome: String) {
        val snapshot: Map<String, Long>
        synchronized(marks) {
            if (finished) return
            finished = true
            marks["done"] = SystemClock.elapsedRealtime() - t0
            snapshot = LinkedHashMap(marks)
        }
        val first = synchronized(rendered) { rendered.add(path) }
        val sincePane = PreviewOpenClock.sincePaneOpened(path)
        val done = snapshot.getValue("done")
        Log.i(
            PREVIEW_TIMING_TAG,
            "open file=${path.substringAfterLast('/')} variant=$variant outcome=$outcome " +
                "kind=${if (first) "first" else "again"} proc=${if (procWarm) "warm" else "cold"} " +
                "host=[${snapshot.entries.joinToString(" ") { "${it.key}=+${it.value}" }}] " +
                "pane=[${PreviewOpenClock.describe(path)}] sincePane=${sincePane ?: -1}",
        )
        if (outcome != "remote" && outcome != "inprocess") return
        val dims = mapOf(
            "kind" to if (first) "first" else "again",
            "proc" to if (procWarm) "warm" else "cold",
            "renderer" to outcome,
        )
        val total = if (first) sincePane ?: done else done
        record(EVENT, total, dims + ("phase" to "total"))
        val lowered = snapshot["lowered"]
        val openStart = snapshot["openStart"]
        val openDone = snapshot["openDone"]
        if (lowered != null) record(EVENT, lowered, dims + ("phase" to "lower"))
        if (openStart != null && openDone != null) record(EVENT, openDone - openStart, dims + ("phase" to "session"))
        if (openDone != null) record(EVENT, done - openDone, dims + ("phase" to "frame"))
    }

    private companion object {
        const val EVENT = dev.ide.analytics.Events.PREVIEW_OPEN_PERF

        /** Files that already reached a first frame in this process: a later open of one is `again`, not `first`. */
        val rendered = HashSet<String>()
    }
}

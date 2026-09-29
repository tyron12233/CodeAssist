package dev.ide.android

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import dev.ide.core.IdeServicesBackend

/**
 * Feeds the window's frame durations to the backend's frame-time summary ([IdeServicesBackend.recordFrame]),
 * so jank while editing is visible in the field and not only in a profiler.
 *
 * Only frames that arrive in a BURST are counted: one drawn within [BURST_GAP_NS] of the previous frame, as
 * typing, scrolling and animations produce. The editor draws nothing while idle except the caret blink, twice
 * a second, and those isolated cheap frames would otherwise bury the frames a user actually feels.
 *
 * **A frame is janky when it misses its deadline, not when it takes longer than one vsync interval.**
 * [FrameMetrics.TOTAL_DURATION] runs from the intended vsync to the frame being done, and that span includes
 * blocking in `swapBuffers` for a free buffer: ordinary back-pressure in a pipelined renderer, not a dropped
 * frame. Judging it against `1000/refreshRate` therefore calls almost everything janky: on a two-core
 * emulator a smooth editor scroll scored 96-100% "janky" that way against 2-12% by the platform's own test,
 * and on a 120 Hz panel an 8 ms budget condemns frames no user could tell from perfect.
 * [FrameMetrics.DEADLINE] is the time the system actually allocated for this frame, and the platform states
 * the rule outright: below it, the app hit its deadline and there was no jank visible to the user. It arrives
 * in API 31; older devices keep the refresh-rate budget, which is the best they can offer.
 *
 * Two durations are reported per frame. The whole frame is what the user feels. The other (everything except
 * the wait for the display) is the part this app is responsible for, and is the one to read when asking
 * whether a change made the IDE slower, since it cannot be flattered by a fast panel or blamed by a slow one.
 */
object AndroidFrameMetrics {
    /** Longest gap between two frames that still counts them as one burst of drawing. */
    private const val BURST_GAP_NS = 100_000_000L

    /**
     * Frames reporting a longer duration than this are dropped as nonsense rather than averaged in.
     *
     * Not hypothetical: 2.6% of shipped frame windows (3 installs over 30 days) carried a `max_ms` of about
     * 9.2e12 (`Long.MAX_VALUE` nanoseconds expressed in milliseconds), which is some devices' way of saying
     * they have no number for this frame. One such sample ruins the window it lands in: it poisons the mean,
     * takes the max, and counts as a miss.
     */
    private const val MAX_PLAUSIBLE_FRAME_NS = 10_000_000_000L

    private val thread by lazy { HandlerThread("frame-metrics").apply { start() } }

    fun register(activity: Activity, backend: IdeServicesBackend) {
        val window: Window = activity.window ?: return
        val refreshHz = runCatching {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }.getOrDefault(60f).takeIf { it >= 30f } ?: 60f
        // Pre-31 fallback only: one vsync interval, the closest thing to a deadline those devices expose.
        val fallbackDeadlineNs = (1_000_000_000.0 / refreshHz).toLong().coerceAtLeast(1L)
        var lastVsync = 0L
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val inBurst = lastVsync != 0L && vsync - lastVsync in 0..BURST_GAP_NS
            lastVsync = vsync
            if (!inBurst) return@addOnFrameMetricsAvailableListener
            val totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalNs <= 0L || totalNs > MAX_PLAUSIBLE_FRAME_NS) return@addOnFrameMetricsAvailableListener
            val deadlineNs = deadlineOf(metrics, fallbackDeadlineNs)
            // Waiting for the display is the one span we neither cause nor can shorten; the rest is ours.
            val swapNs = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION).coerceAtLeast(0L)
            val cpuNs = (totalNs - swapNs).coerceAtLeast(0L)
            backend.recordFrame(
                totalMs = totalNs / 1_000_000,
                cpuMs = cpuNs / 1_000_000,
                missedDeadline = totalNs > deadlineNs,
                cpuOverran = cpuNs > deadlineNs,
            )
        }, Handler(thread.looper))
    }

    /** The frame's own deadline where the platform reports one, else one vsync interval. */
    private fun deadlineOf(metrics: FrameMetrics, fallbackNs: Long): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallbackNs
        val deadline = runCatching { metrics.getMetric(FrameMetrics.DEADLINE) }.getOrDefault(0L)
        return if (deadline > 0L) deadline else fallbackNs
    }
}

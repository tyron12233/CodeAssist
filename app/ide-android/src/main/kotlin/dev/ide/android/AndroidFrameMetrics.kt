package dev.ide.android

import android.app.Activity
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
 * a second, and those isolated cheap frames would otherwise bury the frames a user actually feels. The frame
 * budget comes from the display's refresh rate, so a 90 or 120 Hz device is judged against its own vsync.
 */
object AndroidFrameMetrics {
    /** Longest gap between two frames that still counts them as one burst of drawing. */
    private const val BURST_GAP_NS = 100_000_000L

    private val thread by lazy { HandlerThread("frame-metrics").apply { start() } }

    fun register(activity: Activity, backend: IdeServicesBackend) {
        val window: Window = activity.window ?: return
        val refreshHz = runCatching {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }.getOrDefault(60f).takeIf { it >= 30f } ?: 60f
        val budgetMs = (1000f / refreshHz).toLong().coerceAtLeast(1)
        var lastVsync = 0L
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val inBurst = lastVsync != 0L && vsync - lastVsync in 0..BURST_GAP_NS
            lastVsync = vsync
            if (inBurst) {
                val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
                backend.recordFrame(totalMs, budgetMs)
            }
        }, Handler(thread.looper))
    }
}

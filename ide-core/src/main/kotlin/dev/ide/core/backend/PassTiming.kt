package dev.ide.core.backend

import dev.ide.platform.log.Log
import dev.ide.platform.log.PerfTrace

private val editorPerfLog = Log.logger("editor-perf")

/**
 * Per-pass wall-clock logging for the editor highlighting-daemon passes (diagnostics / semantic / folds /
 * inlay / previews), so the *culprit pass* on a slow file is visible before drilling into the per-stage
 * [dev.ide.lang.kotlin.KotlinPerf] spans. One line per pass: `pass=semantic file=MainActivity.kt took=920ms
 * items=143`. A pass cut short by preemption logs `(preempted/incomplete)` with the partial time — itself a
 * useful signal that completion keeps starving it.
 *
 * Gated on [PerfTrace.enabled] (the "Log analysis timings" setting / `-Dide.editor.perf=true`); the only cost
 * when off is a volatile read plus the already-allocated body lambda, both negligible at the debounced
 * per-pass cadence. Logged via the platform [Log] facade → desktop console + on-device ring (Settings →
 * Privacy → View logs).
 */
internal suspend fun <T> timedPass(pass: String, path: String, count: (T) -> Int, body: suspend () -> T): T {
    if (!PerfTrace.enabled) return body()
    val file = path.substringAfterLast('/')
    // Log the START too, not just the end: during a pathological hang the end line never arrives, so the last
    // `STARTED` with no matching `took=` at a given (e.g. GC) timestamp names the operation that is stuck.
    editorPerfLog.info("pass=$pass file=$file STARTED")
    val t0 = System.nanoTime()
    var n = -1
    try {
        val r = body()
        n = count(r)
        return r
    } finally {
        editorPerfLog.info(
            "pass=$pass file=$file " +
                "took=${(System.nanoTime() - t0) / 1_000_000}ms " +
                (if (n >= 0) "items=$n" else "(preempted/incomplete)")
        )
    }
}

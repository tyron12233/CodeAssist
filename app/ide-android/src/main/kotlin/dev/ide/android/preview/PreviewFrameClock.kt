package dev.ide.android.preview

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred

/**
 * The off-screen preview's frame clock: [base] (the main thread's Choreographer clock) with two controls on top.
 *
 * - **A rate cap.** A vsync closer than [minIntervalNanos] to the last frame is skipped, so the composition, its
 *   animations and therefore its draws run at ~30 fps instead of the display rate. That halves the `:preview`
 *   process' work for a preview that animates (shown scaled down in a pane, 30 fps reads as smooth). Animations keep
 *   their speed: they advance by frame TIME, not frame count. [boostUntilNanos] (a [System.nanoTime] deadline) lifts
 *   the cap while the user interacts, so a drag or scroll in the preview tracks the finger.
 * - **Pause.** Paused, the clock still lets a small budget of frames through ([allowFrames]: after a pause, an edit,
 *   a resize, input) and then stops handing out frames, so an endless animation stops recomposing and drawing. The
 *   composition stays as it is (its lifecycle is untouched, so lifecycle-aware user code sees nothing).
 *
 * The Recomposer is the only caller of [withFrameNanos] and it runs on the main thread, as do [setPaused] and
 * [allowFrames] (post them there).
 */
internal class PreviewFrameClock(
    private val base: MonotonicFrameClock,
    private val minIntervalNanos: Long = DEFAULT_MIN_INTERVAL_NANOS,
) : MonotonicFrameClock {

    @Volatile var boostUntilNanos = 0L

    // Main thread only.
    private var lastFrameNanos = 0L
    private var paused = false
    private var budget = 0
    private var gate: CompletableDeferred<Unit>? = null

    /** Main thread. Pausing still lets [FRAMES_AFTER_PAUSE] frames through, so a preview paused before its first
     *  frame (or mid-edit) still shows it. */
    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) budget = maxOf(budget, FRAMES_AFTER_PAUSE) else open()
    }

    /** Main thread. While paused, run [frames] more frames (an edit, a resize, input), then stop again. */
    fun allowFrames(frames: Int = FRAMES_AFTER_PAUSE) {
        if (!paused) return
        budget = maxOf(budget, frames)
        open()
    }

    private fun open() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        while (true) {
            if (paused && budget <= 0) {
                (gate ?: CompletableDeferred<Unit>().also { gate = it }).await()
                continue
            }
            var result: Any? = Skipped
            base.withFrameNanos { t ->
                if (takes(t)) {
                    lastFrameNanos = t
                    if (paused) budget--
                    result = onFrame(t)
                }
            }
            if (result !== Skipped) {
                @Suppress("UNCHECKED_CAST")
                return result as R
            }
        }
    }

    /** Whether the vsync at [t] runs a frame: the first one, any while boosted, else one per interval. A small
     *  slack absorbs vsync jitter, so a 60 Hz display runs every other vsync rather than every third. */
    private fun takes(t: Long): Boolean =
        lastFrameNanos == 0L || System.nanoTime() < boostUntilNanos || t - lastFrameNanos >= minIntervalNanos - SLACK_NANOS

    private object Skipped

    companion object {
        /** ~30 fps. */
        const val DEFAULT_MIN_INTERVAL_NANOS = 33_333_333L
        private const val SLACK_NANOS = 4_000_000L
        /** Frames let through after a pause / edit: the change recomposes in one and settles (a layout reacting to
         *  a measured size, an effect starting) in the next. */
        const val FRAMES_AFTER_PAUSE = 2
    }
}

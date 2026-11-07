package dev.ide.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.concurrent.Volatile

/**
 * How a preview pane controls the animations of the preview its [ComposePreviewHost] renders.
 *
 * A preview that animates forever (material3's wavy progress indicators, an infinite transition) keeps the host
 * rendering and the IDE redrawing for as long as it is on screen, which pins a low-end phone. The pane's Stop
 * button sets [paused]: the host then freezes the animation on its current frame (an edit still renders one
 * frame). A host that sees frames stream nonstop for a while with no edits or input calls [onEndlessAnimation],
 * and the pane pauses itself the same way the Stop button does, so the user sees it paused and can resume.
 *
 * Defaults to never paused and no auto-pause, for callers (lessons, tests) that don't provide one.
 */
class PreviewAnimationControl(
    val paused: State<Boolean>,
    val onEndlessAnimation: () -> Unit,
)

val LocalPreviewAnimation = staticCompositionLocalOf { PreviewAnimationControl(NeverPaused) {} }

private object NeverPaused : State<Boolean> {
    override val value: Boolean get() = false
}

/**
 * Tells an endless animation from a finite one, for a host deciding when to call
 * [PreviewAnimationControl.onEndlessAnimation]: frames keep arriving with no gap longer than [STREAK_GAP_MS], and
 * neither an edit nor input happened, for [PAUSE_AFTER_MS]. A one-shot transition stops producing frames (its streak
 * ends), and a user editing or interacting restarts the clock. Times are any monotonic millisecond clock; [onFrame]
 * may be called from another thread than [isEndless].
 */
class EndlessAnimationWatch {
    @Volatile private var streakStart = 0L
    @Volatile private var lastFrame = 0L
    @Volatile private var lastActivity = 0L

    fun onFrame(now: Long) {
        if (lastFrame == 0L || now - lastFrame > STREAK_GAP_MS) streakStart = now
        lastFrame = now
    }

    /** An edit, a resize, input, or a resume: whatever is animating is being looked at, so start over. */
    fun onActivity(now: Long) { lastActivity = now }

    fun isEndless(now: Long): Boolean =
        lastFrame != 0L && now - lastFrame <= STREAK_GAP_MS &&
            now - maxOf(streakStart, lastActivity) >= PAUSE_AFTER_MS

    companion object {
        /** How long a preview may animate nonstop, untouched, before it's paused. */
        const val PAUSE_AFTER_MS = 10_000L
        /** Frames further apart than this end a streak: the animation stopped on its own. */
        const val STREAK_GAP_MS = 1_000L
    }
}

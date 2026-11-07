package dev.ide.ui

import dev.ide.ui.EndlessAnimationWatch.Companion.PAUSE_AFTER_MS
import dev.ide.ui.EndlessAnimationWatch.Companion.STREAK_GAP_MS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndlessAnimationWatchTest {

    /** Frames every 33 ms (the remote preview's delivery cap) from [from] to [to]. */
    private fun EndlessAnimationWatch.frames(from: Long, to: Long) {
        var t = from
        while (t <= to) { onFrame(t); t += 33 }
    }

    @Test
    fun aNonstopAnimationIsEndlessAfterThePauseDelay() {
        val w = EndlessAnimationWatch()
        w.onActivity(1_000)
        w.frames(1_000, 1_000 + PAUSE_AFTER_MS - 100)
        assertFalse(w.isEndless(1_000 + PAUSE_AFTER_MS - 100), "not yet")
        w.frames(1_000 + PAUSE_AFTER_MS - 67, 1_000 + PAUSE_AFTER_MS + 50)
        assertTrue(w.isEndless(1_000 + PAUSE_AFTER_MS + 50))
    }

    @Test
    fun aStaticPreviewNeverIs() {
        val w = EndlessAnimationWatch()
        assertFalse(w.isEndless(50_000), "no frames at all")
        w.onFrame(1_000) // its one frame
        assertFalse(w.isEndless(1_000 + PAUSE_AFTER_MS + 5_000))
    }

    @Test
    fun anAnimationThatStoppedOnItsOwnIsNot() {
        val w = EndlessAnimationWatch()
        w.frames(1_000, 1_000 + PAUSE_AFTER_MS + 500)
        // Long enough to count, but the last frame is older than the streak gap: it has settled.
        assertFalse(w.isEndless(1_000 + PAUSE_AFTER_MS + 500 + STREAK_GAP_MS + 100))
    }

    @Test
    fun aGapRestartsTheStreak() {
        val w = EndlessAnimationWatch()
        w.frames(1_000, 6_000)
        val resumed = 6_000 + STREAK_GAP_MS + 500 // a repeating one-shot with a pause between runs
        w.frames(resumed, resumed + PAUSE_AFTER_MS - 1_000)
        assertFalse(w.isEndless(resumed + PAUSE_AFTER_MS - 1_000))
    }

    @Test
    fun editsAndInputRestartTheClock() {
        val w = EndlessAnimationWatch()
        w.frames(1_000, 1_000 + PAUSE_AFTER_MS + 5_000)
        w.onActivity(1_000 + PAUSE_AFTER_MS) // the user edited or touched the preview
        assertFalse(w.isEndless(1_000 + PAUSE_AFTER_MS + 5_000))
        w.frames(1_000 + PAUSE_AFTER_MS + 5_033, 1_000 + 2 * PAUSE_AFTER_MS + 100)
        assertTrue(w.isEndless(1_000 + 2 * PAUSE_AFTER_MS + 100))
    }
}

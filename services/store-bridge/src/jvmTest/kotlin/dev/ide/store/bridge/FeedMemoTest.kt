package dev.ide.store.bridge

import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * When the Explore feed is reused and when it is asked for again.
 *
 * The clock is injected, so these are the actual windows rather than a sleep: the feed used to be fetched
 * once per visit to the Store tab, and the two windows exist because a feed that came off the disk cache
 * is worth much less than a live one.
 */
class FeedMemoTest {

    private var now = 1_000L
    private val memo = FeedMemo(liveMs = 60_000, cachedMs = 5_000, now = { now })

    private fun feed(fromCache: Boolean = false) =
        UiStoreFeed(mode = UiStoreMode.POPULATED, fromCache = fromCache)

    @Test
    fun anEmptyMemoHasNothingToGive() {
        assertNull(memo.get(seed = null))
    }

    @Test
    fun aLiveFeedIsReusedInsideItsWindowAndDroppedAfterIt() {
        val held = feed()
        memo.put(seed = "snake", feed = held)

        now += 59_000
        assertSame(held, memo.get("snake"), "still inside the window")

        now += 2_000
        assertNull(memo.get("snake"), "past it, the store is asked again")
    }

    @Test
    fun aCachedFeedIsHeldForMuchLessTime() {
        memo.put(seed = null, feed = feed(fromCache = true))

        now += 4_000
        assertEquals(true, memo.get(null)?.fromCache)

        // The disk copy is on screen because the network was down. The moment it may not be, ask.
        now += 2_000
        assertNull(memo.get(null))
    }

    @Test
    fun aFeedReadForOneSeedIsNotAnAnswerForAnother() {
        // The personalized shelf is computed from the device's most recent install, so the seed is part
        // of what was asked for, not just context.
        memo.put(seed = "snake", feed = feed())
        assertNull(memo.get("2048"))
        assertNull(memo.get(null))
    }

    @Test
    fun clearingSendsTheNextReadBackToTheStore() {
        memo.put(seed = "snake", feed = feed())
        memo.clear()
        assertNull(memo.get("snake"))
    }
}

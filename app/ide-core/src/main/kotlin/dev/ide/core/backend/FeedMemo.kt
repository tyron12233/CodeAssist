package dev.ide.core.backend

import dev.ide.ui.backend.UiStoreFeed

/**
 * The last Explore feed read, held for long enough that moving around the app is free.
 *
 * The Store tab is a tab: its screen leaves composition whenever the reader looks at their projects, so
 * "fetch when the screen appears" was a full request every visit, and the deep-link lookup made another.
 *
 * Two windows, because the two kinds of feed are worth different amounts. A live feed is ranked content
 * that moves slowly, so it is held for minutes. A feed that came off the disk cache is on screen only
 * because the network was unreachable a moment ago, and the moment that stops being true the reader
 * should get the live one rather than yesterday's ranking, so it is held for seconds.
 *
 * Not a cache of its own: the disk cache still holds the last good document. This only decides whether to
 * ask again.
 */
internal class FeedMemo(
    private val liveMs: Long,
    private val cachedMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var held: UiStoreFeed? = null
    private var heldSeed: String? = null
    private var heldAt = 0L

    /**
     * The feed worth reusing for [seed], or null to go and ask.
     *
     * Keyed on the seed as well as the clock: the personalized shelf is computed from the device's most
     * recent install, so a feed read for one seed is not an answer for another.
     */
    fun get(seed: String?): UiStoreFeed? {
        val feed = held ?: return null
        if (seed != heldSeed) return null
        val ttl = if (feed.fromCache) cachedMs else liveMs
        return feed.takeIf { now() - heldAt < ttl }
    }

    fun put(seed: String?, feed: UiStoreFeed) {
        held = feed
        heldSeed = seed
        heldAt = now()
    }

    /** Forget what is held, so the next read goes back to the store. */
    fun clear() {
        held = null
        heldSeed = null
        heldAt = 0L
    }
}

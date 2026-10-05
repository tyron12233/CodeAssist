package dev.ide.ui.ads

import dev.ide.ui.backend.AdPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A fake ad network: requests stay pending until the test answers them. */
private class FakeNetwork {
    var clock = 0L
    val pending = mutableListOf<Pair<AdPlacement, NativeAdPool.Callbacks<String>>>()
    val destroyed = mutableListOf<String>()
    private var next = 0

    val pool = NativeAdPool<String>(
        load = { placement, callbacks -> pending += placement to callbacks; true },
        destroy = { destroyed += it },
        now = { clock },
    )

    val requestCount get() = pending.size + answered
    private var answered = 0

    /** Answers the oldest pending request with a new ad and returns that ad and its callbacks. */
    fun fill(): Pair<String, NativeAdPool.Callbacks<String>> {
        val (placement, callbacks) = pending.removeAt(0)
        answered++
        val ad = "${placement.name.lowercase()}-${next++}"
        callbacks.loaded(ad)
        return ad to callbacks
    }

    fun failOldest() {
        pending.removeAt(0).second.failed()
        answered++
    }
}

class NativeAdPoolTest {

    @Test
    fun aSlotThatComesBackReusesItsAdWithoutANewRequest() {
        val net = FakeNetwork()
        val first = net.pool.lease(AdPlacement.SIDEBAR)
        net.pool.acquire(first)
        val (ad, callbacks) = net.fill()
        callbacks.impression()
        net.pool.release(first)

        // The sidebar is opened again 10s later.
        net.clock += 10_000
        val second = net.pool.lease(AdPlacement.SIDEBAR)
        net.pool.acquire(second)

        assertEquals(ad, second.ad)
        assertEquals(1, net.requestCount)
        assertTrue(net.destroyed.isEmpty())
    }

    @Test
    fun anAdThatLoadsAfterItsSlotLeftIsShownToTheNextSlot() {
        val net = FakeNetwork()
        val first = net.pool.lease(AdPlacement.BUILD_CONSOLE)
        net.pool.acquire(first)
        net.pool.release(first) // the next build started before the ad arrived
        val (ad, _) = net.fill()

        val second = net.pool.lease(AdPlacement.BUILD_CONSOLE)
        net.pool.acquire(second)

        assertEquals(ad, second.ad)
        assertEquals(1, net.requestCount)
    }

    @Test
    fun aSlotAdoptsARequestStillInFlightInsteadOfAskingAgain() {
        val net = FakeNetwork()
        val first = net.pool.lease(AdPlacement.STORE)
        net.pool.acquire(first)
        net.pool.release(first)

        val second = net.pool.lease(AdPlacement.STORE)
        var redraws = 0
        second.onChange = { redraws++ }
        net.pool.acquire(second)
        assertEquals(1, net.requestCount)

        val (ad, _) = net.fill()
        assertEquals(ad, second.ad)
        assertEquals(1, redraws)
    }

    @Test
    fun aSeenAdIsShownAgainButReplacedOnceItIsDueForRefresh() {
        val net = FakeNetwork()
        val first = net.pool.lease(AdPlacement.PROJECTS)
        net.pool.acquire(first)
        val (old, callbacks) = net.fill()
        callbacks.impression()
        net.pool.release(first)

        net.clock += NATIVE_AD_REFRESH_AFTER_MS
        val second = net.pool.lease(AdPlacement.PROJECTS)
        net.pool.acquire(second)
        assertEquals(old, second.ad, "the slot is never blank while the replacement loads")
        assertEquals(2, net.requestCount)

        val (fresh, _) = net.fill()
        assertEquals(fresh, second.ad)
        assertTrue(net.destroyed.isEmpty(), "the old ad stays until the slot has redrawn")
        net.pool.bound(second)
        assertEquals(listOf(old), net.destroyed)
    }

    @Test
    fun twoSlotsOfOnePlacementNeverShareAnAd() {
        val net = FakeNetwork()
        val top = net.pool.lease(AdPlacement.STORE)
        val bottom = net.pool.lease(AdPlacement.STORE)
        net.pool.acquire(top)
        net.pool.acquire(bottom)
        assertEquals(2, net.requestCount)

        net.fill()
        net.fill()
        assertTrue(top.ad != null && bottom.ad != null && top.ad != bottom.ad)
    }

    @Test
    fun placementsDoNotShareAds() {
        val net = FakeNetwork()
        val learn = net.pool.lease(AdPlacement.LEARN)
        net.pool.acquire(learn)
        net.fill()
        net.pool.release(learn)

        val store = net.pool.lease(AdPlacement.STORE)
        net.pool.acquire(store)
        assertNull(store.ad)
        assertEquals(AdPlacement.STORE, net.pending.single().first)
    }

    @Test
    fun expiredIdleAdsAreDestroyedNotShown() {
        val net = FakeNetwork()
        val first = net.pool.lease(AdPlacement.SETTINGS)
        net.pool.acquire(first)
        val (ad, _) = net.fill()
        net.pool.release(first)

        net.clock += NATIVE_AD_MAX_AGE_MS
        val second = net.pool.lease(AdPlacement.SETTINGS)
        net.pool.acquire(second)

        assertNull(second.ad)
        assertEquals(listOf(ad), net.destroyed)
        assertEquals(2, net.requestCount)
    }

    @Test
    fun aFailedRequestBacksOffBeforeAskingAgain() {
        val net = FakeNetwork()
        val first = net.pool.lease(AdPlacement.CHALLENGE)
        net.pool.acquire(first)
        net.failOldest()
        net.pool.release(first)

        net.clock += NATIVE_AD_FAILURE_BACKOFF_MS - 1
        val second = net.pool.lease(AdPlacement.CHALLENGE)
        net.pool.acquire(second)
        assertEquals(1, net.requestCount)
        net.pool.release(second)

        net.clock += 1
        val third = net.pool.lease(AdPlacement.CHALLENGE)
        net.pool.acquire(third)
        assertEquals(2, net.requestCount)
    }

    @Test
    fun idleAdsBeyondTheCapAreDestroyedKeepingUnseenOnes() {
        val net = FakeNetwork()
        val leases = List(NATIVE_AD_MAX_IDLE_PER_PLACEMENT + 1) { net.pool.lease(AdPlacement.STORE) }
        leases.forEach { net.pool.acquire(it) }
        val loaded = leases.map { net.fill() }
        // Only the first one was seen.
        loaded.first().second.impression()
        leases.forEach { net.pool.release(it) }

        assertEquals(listOf(loaded.first().first), net.destroyed)
    }

    @Test
    fun closeDestroysIdleAdsAndAnythingThatArrivesLater() {
        val net = FakeNetwork()
        val shown = net.pool.lease(AdPlacement.LEARN)
        net.pool.acquire(shown)
        val (ad, _) = net.fill()
        net.pool.release(shown)
        val waiting = net.pool.lease(AdPlacement.STORE)
        net.pool.acquire(waiting)

        net.pool.close()
        val (late, _) = net.fill()

        assertEquals(listOf(ad, late), net.destroyed)
        assertNull(waiting.ad)
    }

    @Test
    fun releasingASlotKeepsItsAdForTheNextOne() {
        val net = FakeNetwork()
        val first = net.pool.lease(AdPlacement.RUN_RESULT)
        net.pool.acquire(first)
        val (ad, _) = net.fill()
        net.pool.release(first)

        val second = net.pool.lease(AdPlacement.RUN_RESULT)
        net.pool.acquire(second)
        assertSame(ad, second.ad)
    }
}

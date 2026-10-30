package dev.ide.ui.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.AdPlacement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a full-screen ad may show over a long build (see [BuildAdPacing]). */
class BuildAdPacingTest {
    private var now = 0L
    private val persisted = mutableListOf<Int>()

    private fun pacing(seen: Int = FREE_LONG_BUILDS, firstSession: Boolean = false) =
        BuildAdPacing(seen, firstSession, persist = { persisted += it }, nowMs = { now })

    @Test
    fun theFirstLongBuildsOfAnInstallAreFree() {
        val p = pacing(seen = 0)
        repeat(FREE_LONG_BUILDS) { assertEquals("grace", p.skipReason(lowMemory = false)) }
        assertNull(p.skipReason(lowMemory = false))
        // Persisted while counting, then never again.
        assertEquals((1..FREE_LONG_BUILDS).toList(), persisted)
    }

    @Test
    fun theFirstSessionShowsNone() {
        val p = pacing(seen = FREE_LONG_BUILDS, firstSession = true)
        assertEquals("first_session", p.skipReason(lowMemory = false))
        assertFalse(p.mayShowLater(lowMemory = false))
    }

    @Test
    fun aLowMemoryDeviceShowsNoneAndLoadsNone() {
        val p = pacing()
        assertEquals("low_memory", p.skipReason(lowMemory = true))
        assertFalse(p.mayShowLater(lowMemory = true))
    }

    @Test
    fun aFailedBuildSkipsTheNextAdUntilABuildSucceeds() {
        val p = pacing()
        p.lastBuildFailed = true
        assertEquals("after_failure", p.skipReason(lowMemory = false))
        p.lastBuildFailed = false
        assertNull(p.skipReason(lowMemory = false))
    }

    @Test
    fun aShownAdStartsACooldown() {
        val p = pacing()
        assertNull(p.skipReason(lowMemory = false))
        p.shown()
        now += 60_000
        assertEquals("cooldown", p.skipReason(lowMemory = false))
        now += 4 * 60_000
        assertNull(p.skipReason(lowMemory = false))
    }

    @Test
    fun loadingWaitsUntilAnAdCouldShow() {
        val p = pacing(seen = 0)
        assertFalse(p.mayShowLater(lowMemory = false))
        repeat(FREE_LONG_BUILDS) { p.skipReason(lowMemory = false) }
        assertTrue(p.mayShowLater(lowMemory = false))
    }
}

/** When the next interstitial is loaded while the IDE sits idle (see [preloadWhileIdle]). */
@OptIn(ExperimentalCoroutinesApi::class)
class IdlePreloadTest {

    @Test
    fun loadsAfterTheIdleDelayThenRetriesWithBackoff() = runTest {
        var loads = 0
        launch { preloadWhileIdle(firstDelayMs = 15_000, active = { true }, preload = { loads++ }) }
        advanceTimeBy(14_999); runCurrent()
        assertEquals(0, loads)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, loads)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, loads)
        advanceTimeBy(2 * 60_000); runCurrent()
        assertEquals(3, loads)
        advanceTimeBy(4 * 60_000); runCurrent()
        assertEquals(4, loads)
        advanceTimeBy(60 * 60_000); runCurrent()
        assertEquals(4, loads)
    }

    @Test
    fun atLaunchItLoadsAtOnce() = runTest {
        var loads = 0
        launch { preloadWhileIdle(firstDelayMs = 0, active = { true }, preload = { loads++ }) }
        runCurrent()
        assertEquals(1, loads)
    }

    @Test
    fun stopsOnceAdsCanNoLongerShow() = runTest {
        var loads = 0
        var active = true
        launch { preloadWhileIdle(firstDelayMs = 0, active = { active }, preload = { loads++ }) }
        runCurrent()
        active = false
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(1, loads)
    }
}

/** A backend that records analytics events and keeps preferences in a map. */
private class TrackingBackend : StubBackend() {
    val prefs = mutableMapOf<String, String>()
    val events = mutableListOf<Pair<String, Map<String, String>>>()
    override fun preference(key: String): String? = prefs[key]
    override fun setPreference(key: String, value: String) { prefs[key] = value }
    override fun track(event: String, props: Map<String, String>) { events += event to props }
}

private class ReadyAdHost(override val installStamp: String?) : AdHost {
    override val available: Boolean = true
    var ready = true
    override fun showInterstitial(): Boolean = ready

    @Composable
    override fun NativeAd(placement: AdPlacement, modifier: Modifier) = Unit
}

/** The ad events [AdController] records. */
class AdControllerEventsTest {

    @Test
    fun aShownAndClosedAdIsRecorded() {
        val backend = TrackingBackend()
        val ads = AdController(backend, ReadyAdHost("build-1"))
        assertTrue(ads.showInterstitial("build"))
        assertEquals("build", ads.interstitialTrigger)
        ads.interstitialClosed(mapOf("build_done" to "1"))
        assertNull(ads.interstitialTrigger)
        assertEquals(listOf("ad_shown", "ad_closed"), backend.events.map { it.first })
        val closed = backend.events.last().second
        assertEquals("build", closed["trigger"])
        assertEquals("1", closed["build_done"])
        assertTrue(closed.getValue("duration_ms").toLong() >= 0)
    }

    @Test
    fun anAdThatWasNotReadyRecordsNothing() {
        val backend = TrackingBackend()
        val host = ReadyAdHost("build-1").apply { ready = false }
        val ads = AdController(backend, host)
        assertFalse(ads.showInterstitial("lesson"))
        ads.interstitialClosed()
        assertTrue(backend.events.isEmpty())
    }

    @Test
    fun aSkipCarriesTheHostStateOnlyWhenGiven() {
        val backend = TrackingBackend()
        val ads = AdController(backend, ReadyAdHost("build-1"))
        ads.interstitialSkipped("cooldown")
        ads.interstitialSkipped("not_ready", "failed_3")
        assertEquals(
            listOf(
                mapOf("trigger" to "build", "reason" to "cooldown"),
                mapOf("trigger" to "build", "reason" to "not_ready", "state" to "failed_3"),
            ),
            backend.events.map { it.second },
        )
    }

    @Test
    fun onlyRealSettingChangesAreRecorded() {
        val backend = TrackingBackend()
        val ads = AdController(backend, ReadyAdHost("build-1"))
        ads.updateAdsEnabled(true)
        ads.updateInterstitialsEnabled(false, via = "prompt")
        ads.updateAdsEnabled(false)
        assertEquals(
            listOf(
                mapOf("setting" to "fullscreen", "on" to "0", "via" to "prompt"),
                mapOf("setting" to "all", "on" to "0", "via" to "settings"),
            ),
            backend.events.map { it.second },
        )
        assertTrue(backend.events.all { it.first == "ads_setting" })
    }

    @Test
    fun aFreshInstallStartsInItsFirstSessionAndAnUpdateDoesNot() {
        val fresh = AdController(TrackingBackend(), ReadyAdHost("build-1"))
        assertEquals("first_session", fresh.buildPacing.skipReason(lowMemory = false))

        val updated = TrackingBackend().apply { prefs[ADS_ENABLED_STAMP_PREF] = "build-0" }
        // An existing install has no long-build count yet: it starts past the grace, not back at zero.
        assertNull(AdController(updated, ReadyAdHost("build-1")).buildPacing.skipReason(lowMemory = false))
    }
}

package dev.ide.ui.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.AdPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A backend whose app-global preferences live in a map, so a test can outlive several controllers. */
private class PrefBackend : StubBackend() {
    val prefs = mutableMapOf<String, String>()
    override fun preference(key: String): String? = prefs[key]
    override fun setPreference(key: String, value: String) { prefs[key] = value }
}

/** An ads-capable host reporting a caller-chosen install identity. */
private class StampedAdHost(override val installStamp: String?) : AdHost {
    override val available: Boolean = true

    @Composable
    override fun NativeAd(placement: AdPlacement, modifier: Modifier) = Unit
}

/**
 * The "show ads" choice is kept for every launch of one installation and reset to on by an install or update
 * (see [AdController]).
 */
class AdControllerInstallResetTest {

    /** The wall clock every controller in a test reads. Starts at the epoch's first day so "before" is valid. */
    private var now = 1_000_000L

    private fun ads(backend: PrefBackend, stamp: String?) = AdController(backend, StampedAdHost(stamp)) { now }

    /** Move past the turn-off lock an install or update starts. */
    private fun afterTheLock() { now += ADS_OFF_LOCK_MS }

    @Test
    fun choiceSurvivesRelaunchesOfTheSameInstall() {
        val backend = PrefBackend()
        ads(backend, "build-1")
        afterTheLock()
        assertTrue(ads(backend, "build-1").updateAdsEnabled(false))

        repeat(3) { assertFalse(ads(backend, "build-1").adsEnabled) }
    }

    @Test
    fun updateTurnsAdsBackOnOnce() {
        val backend = PrefBackend()
        ads(backend, "build-1")
        afterTheLock()
        ads(backend, "build-1").updateAdsEnabled(false)

        assertTrue(ads(backend, "build-2").adsEnabled, "update should re-enable ads")
        // ...and the user's next choice, once allowed, sticks until the NEXT update, not just the next launch.
        afterTheLock()
        ads(backend, "build-2").updateAdsEnabled(false)
        assertFalse(ads(backend, "build-2").adsEnabled)
    }

    @Test
    fun firstRunRecordsTheStampWithoutTouchingTheDefault() {
        val backend = PrefBackend()

        assertTrue(ads(backend, "build-1").adsEnabled)
        assertEquals("build-1", backend.prefs[ADS_ENABLED_STAMP_PREF])
    }

    @Test
    fun hostWithoutAnInstallIdentityNeverResets() {
        val backend = PrefBackend()
        assertTrue(ads(backend, null).updateAdsEnabled(false), "no install identity, no lock")

        assertFalse(ads(backend, null).adsEnabled)
        assertFalse(backend.prefs.containsKey(ADS_ENABLED_STAMP_PREF))
    }

    @Test
    fun fullScreenAdsTurnOffOnTheirOwn() {
        val backend = PrefBackend()
        ads(backend, "build-1")
        afterTheLock()
        ads(backend, "build-1").updateInterstitialsEnabled(false)

        val ads = ads(backend, "build-1")
        assertTrue(ads.adsActive, "native ads stay on")
        assertFalse(ads.interstitialsActive, "full-screen ads stay off across launches")
        assertFalse(ads.shouldShowLessonInterstitial())
        assertFalse(ads.shouldShowLessonInterstitial())
    }

    @Test
    fun turningAllAdsOffAlsoStopsFullScreenAds() {
        val backend = PrefBackend()
        ads(backend, "build-1")
        afterTheLock()
        val ads = ads(backend, "build-1")
        ads.updateAdsEnabled(false)

        assertTrue(ads.interstitialsEnabled, "the separate choice is untouched")
        assertFalse(ads.interstitialsActive)
    }

    @Test
    fun updateTurnsFullScreenAdsBackOnWithTheRest() {
        val backend = PrefBackend()
        ads(backend, "build-1")
        afterTheLock()
        ads(backend, "build-1").updateInterstitialsEnabled(false)

        assertTrue(ads(backend, "build-2").interstitialsEnabled)
    }

    // ---- the turn-off lock ----

    @Test
    fun anUpdateKeepsBothKindsOfAdsOnForADay() {
        val backend = PrefBackend()
        ads(backend, "build-1")
        afterTheLock()
        ads(backend, "build-1").updateAdsEnabled(false)

        // The update's first launch turns ads back on and starts the lock; a relaunch the same day is still in it.
        ads(backend, "build-2")
        now += ADS_OFF_LOCK_MS - 60_000
        val ads = ads(backend, "build-2")
        assertTrue(ads.offLocked)
        assertEquals(60_000, ads.offLockRemainingMs())
        assertFalse(ads.updateAdsEnabled(false), "turning ads off was allowed during the lock")
        assertFalse(ads.updateInterstitialsEnabled(false), "turning full-screen ads off was allowed during the lock")
        assertTrue(ads.adsEnabled)
        assertTrue(ads.interstitialsEnabled)
        assertEquals(true.toString(), backend.prefs[ADS_ENABLED_PREF])

        now += 60_000
        assertFalse(ads.offLocked)
        assertTrue(ads.updateAdsEnabled(false))
        assertFalse(ads(backend, "build-2").adsEnabled, "the choice made after the lock sticks")
    }

    @Test
    fun aFreshInstallIsLockedToo() {
        val ads = ads(PrefBackend(), "build-1")
        assertTrue(ads.offLocked)
        assertFalse(ads.updateAdsEnabled(false))
    }

    @Test
    fun turningAdsOnIsNeverLocked() {
        val ads = ads(PrefBackend(), "build-1")
        assertTrue(ads.updateAdsEnabled(true))
        assertTrue(ads.updateInterstitialsEnabled(true))
    }

    @Test
    fun anInstallFromBeforeTheLockShippedIsNotLocked() {
        // Its stamp already matches, so no reset runs this launch and no lock was ever started.
        val backend = PrefBackend().apply { prefs[ADS_ENABLED_STAMP_PREF] = "build-1" }
        assertTrue(ads(backend, "build-1").updateAdsEnabled(false))
    }

    @Test
    fun aClockSetBackBeforeTheLockStartedDoesNotStretchIt() {
        val backend = PrefBackend()
        ads(backend, "build-1")
        now -= 7 * ADS_OFF_LOCK_MS
        assertFalse(ads(backend, "build-1").offLocked)
    }
}

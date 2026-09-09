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

    @Test
    fun choiceSurvivesRelaunchesOfTheSameInstall() {
        val backend = PrefBackend()
        AdController(backend, StampedAdHost("build-1")).updateAdsEnabled(false)

        repeat(3) { assertFalse(AdController(backend, StampedAdHost("build-1")).adsEnabled) }
    }

    @Test
    fun updateTurnsAdsBackOnOnce() {
        val backend = PrefBackend()
        AdController(backend, StampedAdHost("build-1")).updateAdsEnabled(false)

        assertTrue(AdController(backend, StampedAdHost("build-2")).adsEnabled, "update should re-enable ads")
        // ...and the user's next choice sticks until the NEXT update, not just until the next launch.
        AdController(backend, StampedAdHost("build-2")).updateAdsEnabled(false)
        assertFalse(AdController(backend, StampedAdHost("build-2")).adsEnabled)
    }

    @Test
    fun firstRunRecordsTheStampWithoutTouchingTheDefault() {
        val backend = PrefBackend()

        assertTrue(AdController(backend, StampedAdHost("build-1")).adsEnabled)
        assertEquals("build-1", backend.prefs[ADS_ENABLED_STAMP_PREF])
    }

    @Test
    fun hostWithoutAnInstallIdentityNeverResets() {
        val backend = PrefBackend()
        AdController(backend, StampedAdHost(null)).updateAdsEnabled(false)

        assertFalse(AdController(backend, StampedAdHost(null)).adsEnabled)
        assertFalse(backend.prefs.containsKey(ADS_ENABLED_STAMP_PREF))
    }
}

package dev.ide.ui.screens

import dev.ide.ui.StubBackend
import dev.ide.ui.fakeAdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Turning "Show ads" off in Settings first offers to turn off only the full-screen ads (see
 * [SettingsScreenState.set]).
 */
class AdsOffPromptTest {

    private fun screen(): Pair<SettingsScreenState, dev.ide.ui.ads.AdController> {
        val ads = fakeAdController(StubBackend())
        return SettingsScreenState(StubBackend(), ads, CoroutineScope(Dispatchers.Unconfined)) to ads
    }

    @Test
    fun turningAdsOffAsksFirstAndChangesNothingYet() {
        val (state, ads) = screen()
        state.set(PRIVACY_PAGE_ID, SHOW_ADS_KEY, "false") {}

        assertTrue(state.adsOffPrompt)
        assertTrue(ads.adsEnabled)
        assertEquals("true", state.values["$PRIVACY_PAGE_ID.$SHOW_ADS_KEY"], "the switch stays on while asking")
    }

    @Test
    fun fullScreenOnlyKeepsTheOtherAds() {
        val (state, ads) = screen()
        state.set(PRIVACY_PAGE_ID, SHOW_ADS_KEY, "false") {}
        state.turnOffFullScreenAdsOnly()

        assertFalse(state.adsOffPrompt)
        assertTrue(ads.adsActive)
        assertFalse(ads.interstitialsActive)
        assertEquals("false", state.values["$PRIVACY_PAGE_ID.$SHOW_INTERSTITIALS_KEY"])
    }

    @Test
    fun allAdsTurnsEverythingOff() {
        val (state, ads) = screen()
        state.set(PRIVACY_PAGE_ID, SHOW_ADS_KEY, "false") {}
        state.turnOffAllAds()

        assertFalse(ads.adsActive)
        assertEquals("false", state.values["$PRIVACY_PAGE_ID.$SHOW_ADS_KEY"])
    }

    @Test
    fun noQuestionOnceFullScreenAdsAreAlreadyOff() {
        val (state, ads) = screen()
        state.set(PRIVACY_PAGE_ID, SHOW_INTERSTITIALS_KEY, "false") {}
        state.set(PRIVACY_PAGE_ID, SHOW_ADS_KEY, "false") {}

        assertFalse(state.adsOffPrompt)
        assertFalse(ads.adsEnabled)
    }

    /** Right after an install or update the switch can't go off: it stays on, says why, and asks nothing. */
    @Test
    fun theSwitchSpringsBackDuringTheTurnOffLock() {
        val backend = object : StubBackend() {
            val prefs = mutableMapOf<String, String>()
            override fun preference(key: String): String? = prefs[key]
            override fun setPreference(key: String, value: String) { prefs[key] = value }
        }
        val host = object : dev.ide.ui.backend.AdHost {
            override val available = true
            override val installStamp = "build-2"
            @androidx.compose.runtime.Composable
            override fun NativeAd(placement: dev.ide.ui.backend.AdPlacement, modifier: androidx.compose.ui.Modifier) = Unit
        }
        val ads = dev.ide.ui.ads.AdController(backend, host) { 1_000_000L }
        val state = SettingsScreenState(backend, ads, CoroutineScope(Dispatchers.Unconfined))

        for (key in listOf(SHOW_ADS_KEY, SHOW_INTERSTITIALS_KEY)) {
            state.set(PRIVACY_PAGE_ID, key, "false") {}
            assertEquals("true", state.values["$PRIVACY_PAGE_ID.$key"], "$key went off during the lock")
            assertEquals(SettingsToast.AdsOffLocked(dev.ide.ui.ads.ADS_OFF_LOCK_MS), state.toast)
        }
        assertFalse(state.adsOffPrompt)
        assertTrue(ads.adsEnabled)
        assertTrue(ads.interstitialsEnabled)
        assertEquals(24, lockHours(dev.ide.ui.ads.ADS_OFF_LOCK_MS))
        assertEquals(1, lockHours(1))
    }
}

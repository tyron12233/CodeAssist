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
}

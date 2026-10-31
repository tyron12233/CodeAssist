package dev.ide.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SeasonTest {

    @Test
    fun halloweenRunsFromTheWeekBeforeThroughTheMorningAfter() {
        assertNull(Season.at(1023))
        assertEquals(Season.Halloween, Season.at(1024))
        assertEquals(Season.Halloween, Season.at(1031))
        assertEquals(Season.Halloween, Season.at(1101))
        assertNull(Season.at(1102))
        assertNull(Season.at(101))
    }

    @Test
    fun pumpkinIsAFullSchemeOnItsOwnNeutrals() {
        // Like lime, the night it sits on is part of the palette: it must not fall back to the shared cool ramp.
        val pumpkin = expressiveColorScheme(CaAccent.Pumpkin, dark = true)
        val orange = expressiveColorScheme(CaAccent.Orange, dark = true)
        assertNotEquals(orange.background, pumpkin.background)
        assertNotEquals(orange.primary, pumpkin.primary)
    }
}

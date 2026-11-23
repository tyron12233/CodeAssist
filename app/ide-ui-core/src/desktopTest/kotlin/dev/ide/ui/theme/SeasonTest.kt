package dev.ide.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SeasonTest {

    @Test
    fun halloweenRunsFromTheWeekBeforeThroughTheWeekAfter() {
        assertNull(Season.at(1023))
        assertEquals(Season.Halloween, Season.at(1024))
        assertEquals(Season.Halloween, Season.at(1031))
        assertEquals(Season.Halloween, Season.at(1101))
        assertEquals(Season.Halloween, Season.at(1107))
        assertNull(Season.at(1108))
        assertNull(Season.at(201))
    }

    @Test
    fun winterRunsAcrossTheNewYear() {
        assertNull(Season.at(1122))
        assertEquals(Season.Winter, Season.at(1123))
        assertEquals(Season.Winter, Season.at(1128))
        assertEquals(Season.Winter, Season.at(1201))
        assertEquals(Season.Winter, Season.at(1225))
        assertEquals(Season.Winter, Season.at(1231))
        assertEquals(Season.Winter, Season.at(101))
        assertEquals(Season.Winter, Season.at(106))
        assertNull(Season.at(107))
        assertNull(Season.at(615))
    }

    @Test
    fun namedIgnoresCase() {
        assertEquals(Season.Winter, Season.named(" winter "))
        assertNull(Season.named("easter"))
        assertNull(Season.named(null))
    }

    @Test
    fun pumpkinIsAFullSchemeOnItsOwnNeutrals() {
        // Like lime, the night it sits on is part of the palette: it must not fall back to the shared cool ramp.
        val pumpkin = expressiveColorScheme(CaAccent.Pumpkin, dark = true)
        val orange = expressiveColorScheme(CaAccent.Orange, dark = true)
        assertNotEquals(orange.background, pumpkin.background)
        assertNotEquals(orange.primary, pumpkin.primary)
    }

    @Test
    fun auroraIsAFullSchemeOnItsOwnNeutrals() {
        for (dark in listOf(true, false)) {
            val aurora = expressiveColorScheme(CaAccent.Aurora, dark)
            val teal = expressiveColorScheme(CaAccent.Teal, dark)
            assertNotEquals(teal.background, aurora.background)
            assertNotEquals(teal.primary, aurora.primary)
        }
    }
}

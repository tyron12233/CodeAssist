package dev.ide.ui.holiday

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidayLedgerTest {

    private val prefs = HashMap<String, String>()
    private var today = 20261205
    private val ledger = HolidayLedger(prefs::get, { k, v -> prefs[k] = v }, { today })

    @Test
    fun daysUnlockOneByOneAndStayOpenAfterNewYear() {
        assertEquals(0, ledger.unlockedThrough(20261122))
        assertEquals(0, ledger.daysUntilFirstDoor(20261122))
        assertEquals(0, ledger.unlockedThrough(20261123))
        assertEquals(8, ledger.daysUntilFirstDoor(20261123))
        assertEquals(0, ledger.unlockedThrough(20261128))
        assertEquals(3, ledger.daysUntilFirstDoor(20261128))
        assertEquals(1, ledger.daysUntilFirstDoor(20261130))
        assertEquals(1, ledger.unlockedThrough(20261201))
        assertEquals(17, ledger.unlockedThrough(20261217))
        assertEquals(25, ledger.unlockedThrough(20261225))
        assertEquals(31, ledger.unlockedThrough(20261231))
        assertEquals(31, ledger.unlockedThrough(20270101))
        assertEquals(31, ledger.unlockedThrough(20270106))
        assertEquals(0, ledger.unlockedThrough(20270107))
        assertEquals(0, ledger.unlockedThrough(20260615))
    }

    @Test
    fun theDaysToTheNewYearCountDownThroughDecember() {
        assertEquals(31, ledger.daysToNewYear(20261201))
        assertEquals(1, ledger.daysToNewYear(20261231))
        assertNull(ledger.daysToNewYear(20261130))
        assertNull(ledger.daysToNewYear(20270101))
    }

    @Test
    fun theSeasonYearIsTheYearOfItsDecember() {
        assertEquals(2026, ledger.seasonYear(20261128))
        assertEquals(2026, ledger.seasonYear(20270106))
        assertEquals(2027, ledger.seasonYear(20271201))
    }

    @Test
    fun aDoorOpensOnlyOnceItsDayHasCome() {
        assertFalse(ledger.open(6))
        assertEquals(DoorState.Locked, ledger.state(6))
        assertEquals(DoorState.Ready, ledger.state(5))
        assertTrue(ledger.open(5))
        assertTrue(ledger.open(2))
        assertEquals(DoorState.Opened, ledger.state(5))
        assertEquals(setOf(2, 5), ledger.opened())
        // Opening again is harmless.
        assertTrue(ledger.open(5))
        assertEquals(setOf(2, 5), ledger.opened())
    }

    @Test
    fun eachActivityAddsAtMostOneStarADay() {
        ledger.open(1)
        ledger.open(2) // same day: no second Door star
        ledger.record(HolidayActivity.Build)
        assertNull(ledger.record(HolidayActivity.Build))
        today = 20261206
        ledger.record(HolidayActivity.Build)
        assertEquals(
            listOf(
                SkyStar(HolidayActivity.Door, 20261205),
                SkyStar(HolidayActivity.Build, 20261205),
                SkyStar(HolidayActivity.Build, 20261206),
            ),
            ledger.stars(),
        )
    }

    @Test
    fun nothingIsRecordedOutOfSeasonAndTheSkyHasRoom() {
        today = 20260701
        assertNull(ledger.record(HolidayActivity.Lesson))
        assertTrue(ledger.stars(2026).isEmpty())
        today = 20261128
        var day = 20261128
        repeat(60) {
            for (a in HolidayActivity.entries) ledger.record(a, day)
            day = if (day % 100 == 30 && day % 10_000 / 100 == 11) 20261201 else day + 1
        }
        assertEquals(HolidayLedger.SKY_CAPACITY, ledger.stars(2026).size)
    }

    @Test
    fun eachSeasonKeepsItsOwnCountdown() {
        ledger.open(3)
        today = 20271203
        assertEquals(emptySet(), ledger.opened())
        assertEquals(setOf(3), ledger.opened(2026))
    }

    @Test
    fun aDamagedPreferenceReadsAsNothing() {
        prefs["holiday.2026.opened"] = "3,x,,99,-1,4"
        prefs["holiday.2026.stars"] = "door:20261203,bogus,unknown:20261204,build:notaday"
        assertEquals(setOf(3, 4), ledger.opened())
        assertEquals(listOf(SkyStar(HolidayActivity.Door, 20261203)), ledger.stars())
    }
}

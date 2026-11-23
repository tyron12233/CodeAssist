package dev.ide.ui.holiday

import dev.ide.ui.platform.localMonthDay
import dev.ide.ui.platform.localYear
import dev.ide.ui.theme.Season

/** The things that put a star in the sky. Each earns at most one star a day. */
enum class HolidayActivity(val wire: String) {
    /** A countdown day opened. */
    Door("door"),
    /** A daily challenge solved. */
    Challenge("challenge"),
    /** A lesson exercise passed. */
    Lesson("lesson"),
    /** The first successful build of the day. */
    Build("build"),
    /** A project sent to the Store. */
    Publish("publish"),
    ;

    companion object {
        fun of(wire: String): HolidayActivity? = entries.firstOrNull { it.wire == wire }
    }
}

/** One star in the sky: what earned it, and the day it was earned as `yyyyMMdd`. */
data class SkyStar(val activity: HolidayActivity, val day: Int)

/** Where a door stands today. */
enum class DoorState {
    /** Its day has not come yet. */
    Locked,
    /** Its day has come and it has not been opened. */
    Ready,
    /** Opened. */
    Opened,
}

/**
 * The winter event's state: which days of the New Year countdown have been opened, and the stars in the sky.
 *
 * Kept on the flat app preference store, like the color schemes, rather than behind a backend service: it is a
 * few dozen bytes per season, every host already implements `preference`/`setPreference` (desktop, Android and
 * iOS alike), and it works signed out and offline, which is the point of a calendar on a coding app.
 *
 * Everything is keyed by the season's year (the year its December falls in), so next year's countdown starts
 * fresh and this year's sky is still there to look back on. [today] is the local date as `yyyyMMdd`,
 * injectable for tests.
 */
class HolidayLedger(
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
    private val today: () -> Int = { localYear() * 10_000 + localMonthDay() },
) {
    /** The year whose December the season around [day] belongs to: Jan 6 2027 is still the 2026 season. */
    fun seasonYear(day: Int = today()): Int {
        val year = day / 10_000
        return if (day % 10_000 < 200) year - 1 else year
    }

    /** True while the winter season is on the calendar (Nov 23 through Jan 6). */
    fun inSeason(day: Int = today()): Boolean = Season.Winter.contains(day % 10_000)

    /**
     * The last countdown day that has come: 0 before December 1, the day of the month through December 31, and
     * 31 from New Year's Day until the season ends, so a day missed can still be opened afterwards. Outside the
     * season, 0.
     */
    fun unlockedThrough(day: Int = today()): Int {
        if (!inSeason(day)) return 0
        val md = day % 10_000
        return when {
            md / 100 == 11 -> 0
            md in 1201..1231 -> md - 1200
            else -> DOORS
        }
    }

    /** Days until the first door, while the season is open and it is still November; otherwise 0. */
    fun daysUntilFirstDoor(day: Int = today()): Int {
        val md = day % 10_000
        // November has 30 days, so from Nov 28 it is 3 days to December 1.
        return if (inSeason(day) && md / 100 == 11) 31 - md % 100 else 0
    }

    /** Days left until New Year's Day during December (1 on New Year's Eve); null on any other day. */
    fun daysToNewYear(day: Int = today()): Int? {
        val md = day % 10_000
        return if (md in 1201..1231) 32 - (md - 1200) else null
    }

    fun opened(year: Int = seasonYear()): Set<Int> =
        get(openedKey(year))?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 1..DOORS }?.toSet() ?: emptySet()

    fun state(door: Int, day: Int = today()): DoorState = when {
        door in opened(seasonYear(day)) -> DoorState.Opened
        door <= unlockedThrough(day) -> DoorState.Ready
        else -> DoorState.Locked
    }

    /**
     * Open [door], if its day has come. Returns whether it is open now (true for a door opened before, too).
     * The first opening of a day puts a Door star in the sky, subject to the once-a-day rule.
     */
    fun open(door: Int, day: Int = today()): Boolean {
        if (door !in 1..DOORS) return false
        val year = seasonYear(day)
        val opened = opened(year)
        if (door in opened) return true
        if (door > unlockedThrough(day)) return false
        set(openedKey(year), (opened + door).sorted().joinToString(","))
        record(HolidayActivity.Door, day)
        return true
    }

    /** The stars earned this season, oldest first. */
    fun stars(year: Int = seasonYear()): List<SkyStar> =
        get(starsKey(year)).orEmpty().split(',').mapNotNull { entry ->
            val (wire, date) = entry.trim().split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val activity = HolidayActivity.of(wire) ?: return@mapNotNull null
            date.toIntOrNull()?.let { SkyStar(activity, it) }
        }

    /**
     * Add a star for [activity], if the season is on, the sky has room, and [activity] has not already earned
     * one today. Returns the star added, or null when nothing changed.
     */
    fun record(activity: HolidayActivity, day: Int = today()): SkyStar? {
        if (!inSeason(day)) return null
        val year = seasonYear(day)
        val current = stars(year)
        if (current.size >= SKY_CAPACITY) return null
        if (current.any { it.activity == activity && it.day == day }) return null
        val star = SkyStar(activity, day)
        set(starsKey(year), (current + star).joinToString(",") { "${it.activity.wire}:${it.day}" })
        return star
    }

    private fun openedKey(year: Int) = "holiday.$year.opened"
    private fun starsKey(year: Int) = "holiday.$year.stars"

    companion object {
        /** December 1 through New Year's Eve. */
        const val DOORS = 31
        /** How many stars the sky holds: every day plus a busy month of everything else. */
        const val SKY_CAPACITY = 60
    }
}

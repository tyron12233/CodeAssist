package dev.ide.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ide.ui.platform.localMonthDay

/**
 * The seasonal events the UI dresses up for. Each one is a window of local calendar days that recurs every
 * year, so the touches come back on their own without a release; the user can turn all of them off with
 * Settings → Appearance → Seasonal touches.
 */
enum class Season(
    /** First day, as `month * 100 + day`. */
    private val from: Int,
    /** Last day, inclusive, same encoding. */
    private val through: Int,
) {
    /** The week before Halloween through the morning after (Nov 1), when people are still finishing their builds. */
    Halloween(from = 1024, through = 1101),
    ;

    fun contains(monthDay: Int): Boolean = monthDay in from..through

    companion object {
        /** The season [monthDay] falls in, or null for an ordinary day. */
        fun at(monthDay: Int): Season? = entries.firstOrNull { it.contains(monthDay) }

        /** The season today falls in, by the device's local calendar. */
        fun current(): Season? = at(localMonthDay())
    }
}

/**
 * The season the UI is dressed for, or null: provided once at the app root, already combined with the user's
 * Seasonal touches switch, so a component only asks `LocalSeason.current == Season.Halloween` and never has
 * to know about the setting or the calendar. Defaults to null, so a snapshot or a preview with no root
 * renders the ordinary UI.
 */
val LocalSeason = staticCompositionLocalOf<Season?> { null }


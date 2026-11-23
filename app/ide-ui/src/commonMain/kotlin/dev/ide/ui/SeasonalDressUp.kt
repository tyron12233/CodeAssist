package dev.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.ui.components.SeasonalCardMode
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.Season

/**
 * What one season's "dress up" puts on: an accent, an editor color scheme and a caret, each by the id the
 * Settings screen writes for it.
 */
internal data class SeasonalOutfit(
    val season: Season,
    val accent: String,
    val scheme: String,
    val caret: String,
) {
    companion object {
        val Halloween = SeasonalOutfit(Season.Halloween, accent = "pumpkin", scheme = "pumpkin", caret = "candle")
        val Winter = SeasonalOutfit(Season.Winter, accent = "aurora", scheme = "polar-night", caret = "candle")

        fun of(season: Season): SeasonalOutfit = when (season) {
            Season.Halloween -> Halloween
            Season.Winter -> Winter
        }
    }
}

/**
 * A seasonal card's state and its one-tap "dress up": the season's accent, editor scheme and caret, all through
 * the same settings writes the Settings screen makes, so nothing here is a second source of truth and every
 * piece can still be changed on its own afterwards.
 *
 * What the user had before is kept in one preference, which is what makes both Undo and the after-season
 * "put it back" possible. Answering the card (either way) records the time rather than a flag, so next year
 * the offer comes back on its own. Each season keeps its own keys, so answering one says nothing about the
 * next.
 */
internal class SeasonalDressUp(private val app: CodeAssistAppState, private val outfit: SeasonalOutfit) {

    private val settings get() = app.backend.settings
    private val key = outfit.season.name.lowercase()
    private val answeredKey = "seasonal.$key.answeredAt"
    private val previousKey = "seasonal.$key.previous"

    /** Set for the rest of this visit after dressing up, so the card can confirm it and offer Undo. */
    private var justApplied by mutableStateOf(false)

    /** Bumped whenever the card is answered, so [mode] is re-read. */
    private var answered by mutableStateOf(0)

    private fun inSeason(): Boolean = app.calendarSeason() == outfit.season

    /** What the card should say right now, or null for no card. */
    fun mode(): SeasonalCardMode? {
        answered // read for invalidation
        if (!app.settings.seasonalTouches) return null
        val inSeason = inSeason()
        return when {
            justApplied -> SeasonalCardMode.Applied
            inSeason && !answeredRecently() -> SeasonalCardMode.Offer
            // Out of season and still wearing it: the user said yes and has not changed all three since.
            !inSeason && settings.preference(previousKey).isNullOrEmpty().not() && stillDressed() -> SeasonalCardMode.SeasonOver
            else -> null
        }
    }

    fun dressUp() {
        val previous = listOf(
            settings.preference(ACCENT_KEY) ?: "",
            settings.preference(CARET_KEY) ?: "",
            app.colorSchemes.activeId(),
        ).joinToString(SEPARATOR)
        settings.setPreference(previousKey, previous)
        // Dressing up for this season takes over from any earlier one still worn: its "before" is now part of
        // what this season will put back, so its own offer to put things back would undo this one.
        for (other in Season.entries) if (other != outfit.season) settings.setPreference("seasonal.${other.name.lowercase()}.previous", "")
        settings.setSetting("appearance", "accent", outfit.accent)
        settings.setSetting("editor", "caretStyle", outfit.caret)
        app.colorSchemes.setActiveId(outfit.scheme)
        markAnswered()
        justApplied = true
        app.reloadSettings()
        app.reloadColorScheme()
    }

    /** Put back what [dressUp] replaced. Values the user had never set go back to their defaults. */
    fun putBack() {
        val parts = settings.preference(previousKey).orEmpty().split(SEPARATOR)
        if (parts.size == 3) {
            settings.setSetting("appearance", "accent", parts[0].ifEmpty { DEFAULT_ACCENT })
            settings.setSetting("editor", "caretStyle", parts[1].ifEmpty { DEFAULT_CARET })
            app.colorSchemes.setActiveId(parts[2])
        }
        settings.setPreference(previousKey, "")
        justApplied = false
        markAnswered()
        app.reloadSettings()
        app.reloadColorScheme()
    }

    /** "Not now" in season, or "Keep it" after: either way the card goes until next year. */
    fun dismiss() {
        if (!inSeason()) settings.setPreference(previousKey, "")
        justApplied = false
        markAnswered()
    }

    private fun markAnswered() {
        settings.setPreference(answeredKey, nowMillis().toString())
        answered++
    }

    // Within the last 300 days is "this season": long enough to cover the whole window, short enough that
    // next year counts as new.
    private fun answeredRecently(): Boolean {
        val at = settings.preference(answeredKey)?.toLongOrNull() ?: return false
        return nowMillis() - at < 300L * 24 * 60 * 60 * 1000
    }

    private fun stillDressed(): Boolean =
        settings.preference(ACCENT_KEY) == outfit.accent ||
            settings.preference(CARET_KEY) == outfit.caret ||
            app.colorSchemes.activeId() == outfit.scheme

    private companion object {
        // The generic settings keys (`settings.<page>.<control>`) the Settings screen writes.
        const val ACCENT_KEY = "settings.appearance.accent"
        const val CARET_KEY = "settings.editor.caretStyle"
        const val DEFAULT_ACCENT = "lime"
        const val DEFAULT_CARET = "line"
        const val SEPARATOR = "|"
    }
}

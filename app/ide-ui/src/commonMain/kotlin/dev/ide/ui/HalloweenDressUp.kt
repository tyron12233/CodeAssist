package dev.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.ui.components.HalloweenCardMode
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.Season

/**
 * The Halloween card's state and its one-tap "dress up": the Pumpkin accent, the Pumpkin Patch editor scheme
 * and the candle caret, all through the same settings writes the Settings screen makes, so nothing here is a
 * second source of truth and every piece can still be changed on its own afterwards.
 *
 * What the user had before is kept in one preference, which is what makes both Undo and the after-season
 * "put it back" possible. Answering the card (either way) records the time rather than a flag, so next
 * October the offer comes back on its own.
 */
internal class HalloweenDressUp(private val app: CodeAssistAppState) {

    private val settings get() = app.backend.settings

    /** Set for the rest of this visit after dressing up, so the card can confirm it and offer Undo. */
    private var justApplied by mutableStateOf(false)

    /** Bumped whenever the card is answered, so [mode] is re-read. */
    private var answered by mutableStateOf(0)

    /** What the card should say right now, or null for no card. */
    fun mode(): HalloweenCardMode? {
        answered // read for invalidation
        if (!app.settings.seasonalTouches) return null
        val inSeason = Season.current() == Season.Halloween
        return when {
            justApplied -> HalloweenCardMode.Applied
            inSeason && !answeredRecently() -> HalloweenCardMode.Offer
            // Out of season and still wearing it: the user said yes and has not changed all three since.
            !inSeason && settings.preference(PREVIOUS_KEY).isNullOrEmpty().not() && stillDressed() -> HalloweenCardMode.SeasonOver
            else -> null
        }
    }

    fun dressUp() {
        val previous = listOf(
            settings.preference(ACCENT_KEY) ?: "",
            settings.preference(CARET_KEY) ?: "",
            app.colorSchemes.activeId(),
        ).joinToString(SEPARATOR)
        settings.setPreference(PREVIOUS_KEY, previous)
        settings.setSetting("appearance", "accent", PUMPKIN_ACCENT)
        settings.setSetting("editor", "caretStyle", CANDLE_CARET)
        app.colorSchemes.setActiveId(PUMPKIN_SCHEME)
        markAnswered()
        justApplied = true
        app.reloadSettings()
        app.reloadColorScheme()
    }

    /** Put back what [dressUp] replaced. Values the user had never set go back to their defaults. */
    fun putBack() {
        val parts = settings.preference(PREVIOUS_KEY).orEmpty().split(SEPARATOR)
        if (parts.size == 3) {
            settings.setSetting("appearance", "accent", parts[0].ifEmpty { DEFAULT_ACCENT })
            settings.setSetting("editor", "caretStyle", parts[1].ifEmpty { DEFAULT_CARET })
            app.colorSchemes.setActiveId(parts[2])
        }
        settings.setPreference(PREVIOUS_KEY, "")
        justApplied = false
        markAnswered()
        app.reloadSettings()
        app.reloadColorScheme()
    }

    /** "Not now" in season, or "Keep it" after: either way the card goes until next year. */
    fun dismiss() {
        if (Season.current() != Season.Halloween) settings.setPreference(PREVIOUS_KEY, "")
        justApplied = false
        markAnswered()
    }

    private fun markAnswered() {
        settings.setPreference(ANSWERED_KEY, nowMillis().toString())
        answered++
    }

    // Within the last 300 days is "this season": long enough to cover the whole window, short enough that
    // next October counts as new.
    private fun answeredRecently(): Boolean {
        val at = settings.preference(ANSWERED_KEY)?.toLongOrNull() ?: return false
        return nowMillis() - at < 300L * 24 * 60 * 60 * 1000
    }

    private fun stillDressed(): Boolean =
        settings.preference(ACCENT_KEY) == PUMPKIN_ACCENT ||
            settings.preference(CARET_KEY) == CANDLE_CARET ||
            app.colorSchemes.activeId() == PUMPKIN_SCHEME

    private companion object {
        const val ANSWERED_KEY = "seasonal.halloween.answeredAt"
        const val PREVIOUS_KEY = "seasonal.halloween.previous"
        // The generic settings keys (`settings.<page>.<control>`) the Settings screen writes.
        const val ACCENT_KEY = "settings.appearance.accent"
        const val CARET_KEY = "settings.editor.caretStyle"
        const val PUMPKIN_ACCENT = "pumpkin"
        const val CANDLE_CARET = "candle"
        const val PUMPKIN_SCHEME = "pumpkin"
        const val DEFAULT_ACCENT = "lime"
        const val DEFAULT_CARET = "line"
        const val SEPARATOR = "|"
    }
}

package dev.ide.vcs.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.ide.ui.backend.UiVcsText
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.allStringResources
import dev.ide.vcs.ui.generated.resources.vcs_age_days
import dev.ide.vcs.ui.generated.resources.vcs_age_hours
import dev.ide.vcs.ui.generated.resources.vcs_age_minutes
import dev.ide.vcs.ui.generated.resources.vcs_age_now
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * [this] in the user's language: the string resource its key names, with each argument resolved the same way.
 * Falls back to the English the backend rendered when this build has no such resource, or when the text is
 * one the IDE did not write (a server's own error), which has no key at all.
 */
internal suspend fun UiVcsText.localized(): String {
    if (key.isEmpty()) return text
    val resource = Res.allStringResources[key] ?: return text
    return getString(resource, *args.map { it.localized() }.toTypedArray())
}

/** [text] localized for display, showing [fallback] until the resource has been read (and when [text] is null). */
@Composable
internal fun localizedText(text: UiVcsText?, fallback: String): String {
    val resolved by produceState(fallback, text, fallback) { value = text?.localized() ?: fallback }
    return resolved
}

/**
 * How long ago [timeMs] was, in the user's language: minutes and hours today, days inside a week. Older than
 * that, [hostLabel] is used, which the backend formats as a date in the device locale.
 */
@Composable
internal fun ageText(timeMs: Long, hostLabel: String): String {
    if (timeMs <= 0L) return hostLabel
    val elapsed = Clock.System.now().toEpochMilliseconds() - timeMs
    return when {
        elapsed < MINUTE_MS -> stringResource(Res.string.vcs_age_now)
        elapsed < HOUR_MS -> stringResource(Res.string.vcs_age_minutes, elapsed / MINUTE_MS)
        elapsed < DAY_MS -> stringResource(Res.string.vcs_age_hours, elapsed / HOUR_MS)
        elapsed < WEEK_MS -> stringResource(Res.string.vcs_age_days, elapsed / DAY_MS)
        else -> hostLabel
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val WEEK_MS = 7 * DAY_MS

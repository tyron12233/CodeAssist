package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.ide.ui.IdeUiState
import dev.ide.ui.LocalSettingsPageOpener
import dev.ide.ui.backend.UiAutoDisabledInspection
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.insp_keep_off
import dev.ide.ui.generated.resources.insp_off_again_failing
import dev.ide.ui.generated.resources.insp_off_again_slow
import dev.ide.ui.generated.resources.insp_off_failing
import dev.ide.ui.generated.resources.insp_off_many
import dev.ide.ui.generated.resources.insp_off_slow
import dev.ide.ui.generated.resources.insp_turn_back_on
import dev.ide.ui.generated.resources.settings_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The Settings page that lists the inspections (the built-in Analysis page). */
private const val INSPECTIONS_PAGE = "analysis"

/**
 * The notice for an installed plugin's inspection that CodeAssist switched off for slowing the editor down or
 * failing. Without it the only trace was a line in the logs, and the user just saw a check stop reporting.
 *
 * One such inspection gets its own notice: what happened and to which plugin's check, with Turn back on and a way
 * to its Settings row, which has the numbers. The second time the same one is dropped, turning it back on
 * evidently did not help, so the notice leads with keeping it off instead. Several at once become a count that
 * opens Settings.
 */
@Composable
internal fun autoDisabledInspectionsNotice(state: IdeUiState): EditorNotice? {
    val off by state.backend.settings.autoDisabledInspections.collectAsState()
    val shown = off.filter { it.noticeKey() !in state.dismissedInspectionNotices }
    if (shown.isEmpty()) return null
    val openSettings = LocalSettingsPageOpener.current
    val settings = stringResource(Res.string.settings_title)
    val dismiss = { state.dismissedInspectionNotices += shown.map { it.noticeKey() } }

    if (shown.size > 1) {
        return EditorNotice(
            id = "inspections-off",
            level = NoticeLevel.Warning,
            summary = pluralStringResource(Res.plurals.insp_off_many, shown.size, shown.size),
            actionLabel = settings,
            onAction = { openSettings(INSPECTIONS_PAGE) },
            onDismiss = dismiss,
        )
    }
    val one = shown.single()
    val turnBackOn = stringResource(Res.string.insp_turn_back_on)
    val reenable = { state.backend.settings.reenableInspection(one.id) }
    val again = one.times > 1
    // The reason first and the names last: the strip shows two lines, and on a phone it is the end that is cut.
    // The measured time and the limit are on the inspection's Settings row, which has room for them.
    val summary = stringResource(
        when {
            again && one.slow -> Res.string.insp_off_again_slow
            again -> Res.string.insp_off_again_failing
            one.slow -> Res.string.insp_off_slow
            else -> Res.string.insp_off_failing
        },
        one.displayName, one.pluginName,
    )
    return if (again) EditorNotice(
        id = "inspections-off",
        level = NoticeLevel.Warning,
        summary = summary,
        actionLabel = stringResource(Res.string.insp_keep_off),
        onAction = dismiss,
        secondaryLabel = turnBackOn,
        onSecondary = reenable,
        onDismiss = dismiss,
    ) else EditorNotice(
        id = "inspections-off",
        level = NoticeLevel.Warning,
        summary = summary,
        actionLabel = turnBackOn,
        onAction = reenable,
        secondaryLabel = settings,
        onSecondary = { openSettings(INSPECTIONS_PAGE) },
        onDismiss = dismiss,
    )
}

/** Dismissal key: a later drop of the same inspection is a new notice. */
private fun UiAutoDisabledInspection.noticeKey() = "$id#$times"

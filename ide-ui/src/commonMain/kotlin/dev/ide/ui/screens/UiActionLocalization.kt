package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import dev.ide.ui.ext.UiHostAction
import dev.ide.ui.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A localization overlay for the built-in UI actions (the "More" menu rows and the command palette's
 * UI-navigation commands). Their titles/descriptions are declared in `ide-ui-api`
 * ([dev.ide.ui.ext.BuiltInUiPlugin]) as plain English — the plugin API carries plain strings (third-party
 * plugins have no Compose-resource access), so rather than translate there, the UI maps a built-in action id
 * to a string resource here and resolves it at render time.
 *
 * Same OVERRIDE pattern as [SettingsLocalization]: where a resource exists it wins, otherwise the
 * plugin-supplied string is used verbatim, keeping third-party plugin actions working (unknown ids fall
 * through).
 *
 * Keys mirror the [dev.ide.ui.ext.SimpleUiAction] ids.
 */

// Action title, by action id.
private val ACTION_TITLE: Map<String, StringResource> = mapOf(
    "ui.hub" to Res.string.action_hub,
    "ui.modules" to Res.string.action_modules,
    "ui.icons" to Res.string.action_icons,
    "ui.dependencies" to Res.string.action_dependencies,
    "ui.reindex" to Res.string.action_reindex,
    "ui.logs" to Res.string.action_logs,
    "ui.toggleTheme" to Res.string.action_toggle_theme,
    "ui.closeProject" to Res.string.action_close_project,
)

// Action description, by action id (only the built-ins that carry one).
private val ACTION_DESC: Map<String, StringResource> = mapOf(
    "ui.hub" to Res.string.action_hub_desc,
    "ui.modules" to Res.string.action_modules_desc,
    "ui.icons" to Res.string.action_icons_desc,
    "ui.reindex" to Res.string.action_reindex_desc,
    "ui.logs" to Res.string.action_logs_desc,
    "ui.toggleTheme" to Res.string.action_toggle_theme_desc,
    "ui.closeProject" to Res.string.action_close_project_desc,
)

/** The localized title for a built-in UI action; the plugin-supplied title for a third-party action. */
@Composable
fun localizedUiActionText(action: UiHostAction): String =
    ACTION_TITLE[action.id]?.let { stringResource(it) } ?: action.text

/** The localized description for a built-in UI action, falling back to the plugin-supplied one (may be null). */
@Composable
fun localizedUiActionDescription(action: UiHostAction): String? =
    ACTION_DESC[action.id]?.let { stringResource(it) } ?: action.description

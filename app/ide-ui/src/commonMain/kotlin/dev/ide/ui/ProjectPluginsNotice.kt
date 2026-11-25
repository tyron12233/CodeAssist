package dev.ide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiProjectPlugin
import dev.ide.ui.backend.UiProjectPluginCheck
import dev.ide.ui.backend.UiProjectPluginState
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.home_get_plugins
import dev.ide.ui.generated.resources.project_plugins_failed
import dev.ide.ui.generated.resources.project_plugins_needs_permission
import dev.ide.ui.generated.resources.project_plugins_not_installed
import dev.ide.ui.generated.resources.project_plugins_not_loaded
import dev.ide.ui.generated.resources.project_plugins_open_anyway
import dev.ide.ui.generated.resources.project_plugins_open_plugins
import dev.ide.ui.generated.resources.project_plugins_reopen
import dev.ide.ui.generated.resources.project_plugins_title
import dev.ide.ui.generated.resources.project_plugins_title_unknown
import dev.ide.ui.generated.resources.project_plugins_turn_on
import dev.ide.ui.generated.resources.project_plugins_turned_off
import dev.ide.ui.generated.resources.project_plugins_unknown
import dev.ide.ui.generated.resources.store_get_plugin
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/*
 * What a project says when a plugin it was made with is missing: a question before it opens (from the picker),
 * and a notice in its editor when it was opened anyway (or resumed, or opened from somewhere that does not ask).
 * Both lead with the first missing plugin and offer the one step that fixes it: get it, turn it on, review it
 * under Plugins, or reopen the project once it is running.
 */

@Composable
private fun titleOf(check: UiProjectPluginCheck): String =
    if (check.plugins.isEmpty()) stringResource(Res.string.project_plugins_title_unknown)
    else stringResource(Res.string.project_plugins_title, check.plugins.joinToString(", ") { it.name })

@Composable
private fun messageOf(check: UiProjectPluginCheck): String {
    val p = check.plugins.firstOrNull()
        ?: return stringResource(Res.string.project_plugins_unknown, check.unknownModuleTypes.joinToString(", "))
    return when (p.state) {
        UiProjectPluginState.NOT_INSTALLED -> stringResource(Res.string.project_plugins_not_installed, p.name)
        UiProjectPluginState.TURNED_OFF -> stringResource(Res.string.project_plugins_turned_off, p.name)
        UiProjectPluginState.NEEDS_PERMISSION -> stringResource(Res.string.project_plugins_needs_permission, p.name)
        UiProjectPluginState.FAILED -> stringResource(Res.string.project_plugins_failed, p.name)
        UiProjectPluginState.NOT_LOADED -> stringResource(Res.string.project_plugins_not_loaded, p.name)
    }
}

@Composable
private fun actionOf(check: UiProjectPluginCheck): String {
    val p = check.plugins.firstOrNull() ?: return stringResource(Res.string.home_get_plugins)
    return when (p.state) {
        UiProjectPluginState.NOT_INSTALLED -> stringResource(Res.string.store_get_plugin, p.name)
        UiProjectPluginState.TURNED_OFF -> stringResource(Res.string.project_plugins_turn_on)
        UiProjectPluginState.NEEDS_PERMISSION, UiProjectPluginState.FAILED -> stringResource(Res.string.project_plugins_open_plugins)
        UiProjectPluginState.NOT_LOADED -> stringResource(Res.string.project_plugins_reopen)
    }
}

/**
 * Asked before a project opens from the picker when a plugin it needs is not working here. [onResolve] takes the
 * plugin to fix (null when the project only names module types nothing provides: the Get plugins page).
 */
@Composable
internal fun MissingPluginsDialog(
    check: UiProjectPluginCheck,
    onResolve: (UiProjectPlugin?) -> Unit,
    onOpenAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    dev.ide.ui.dialog.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Symbol(CaSymbols.extension, contentDescription = null, size = 28.dp, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(titleOf(check)) },
        text = { Text(messageOf(check)) },
        confirmButton = {
            Button(onClick = { onResolve(check.plugins.firstOrNull()) }) { Text(actionOf(check)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                TextButton(onClick = onOpenAnyway) { Text(stringResource(Res.string.project_plugins_open_anyway)) }
            }
        },
    )
}

/**
 * The open project's notice for a plugin it needs that is not working for it: under the top bar, re-read when the
 * window has its focus back (the user returning from installing the plugin), so it turns into "Reopen project"
 * once the plugin runs and goes away once the project has it. Turning a plugin on happens here; [onResolve]
 * handles the steps that leave the editor, [onReopen] the reopen.
 */
@Composable
internal fun MissingPluginsBanner(
    backend: IdeBackend,
    rootPath: String,
    epoch: Int,
    onResolve: (UiProjectPlugin?) -> Unit,
    onReopen: () -> Unit,
) {
    var check by remember(rootPath, epoch) { mutableStateOf(UiProjectPluginCheck()) }
    var dismissed by remember(rootPath, epoch) { mutableStateOf(false) }
    var reads by remember(rootPath, epoch) { mutableIntStateOf(0) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(rootPath, epoch, focused, reads) {
        if (!focused) return@LaunchedEffect
        // The app takes a newly installed plugin up off the main thread, so look again shortly after as well.
        for (wait in longArrayOf(0L, 800L, 2500L)) {
            delay(wait)
            check = runCatching { backend.projects.projectPluginCheck(rootPath) }.getOrDefault(UiProjectPluginCheck())
            if (check.ok) break
        }
    }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(!check.ok && !dismissed) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), material = GlassMaterial.Regular) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Ide.colors.warning.copy(alpha = 0.10f))
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ide.colors.warning)
                Column(Modifier.weight(1f)) {
                    Text(
                        titleOf(check),
                        color = Ide.colors.warning,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        messageOf(check),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = {
                    val p = check.plugins.firstOrNull()
                    when (p?.state) {
                        UiProjectPluginState.NOT_LOADED -> onReopen()
                        UiProjectPluginState.TURNED_OFF -> scope.launch {
                            // Turning a plugin on loads it, off the main thread; the notice then offers the reopen.
                            withContext(Dispatchers.Default) { backend.settings.setPluginEnabled(p.id, true) }
                            reads++
                        }
                        else -> onResolve(p)
                    }
                }) { Text(actionOf(check), maxLines = 1) }
                IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), { dismissed = true }, boxSize = 24, iconSize = 14)
            }
        }
    }
}

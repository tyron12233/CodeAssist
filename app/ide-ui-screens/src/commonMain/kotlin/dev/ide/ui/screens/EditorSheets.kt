package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.CommandPalette
import dev.ide.ui.actions.applyActionEffects
import dev.ide.ui.components.PaletteEditorTarget
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.LocalPluginNavigator
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.ext.UiDestinations
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.more
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PaletteOverlay(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenIconManager: () -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onCloseProject: () -> Unit,
) {
    val pluginNavigator = LocalPluginNavigator.current
    val paletteHost = editorActionHost(
        state, onToggleTheme, onOpenHub, onOpenIconManager, onOpenDependencies, onOpenModuleConfig, onCloseProject,
        onDone = { state.paletteOpen = false },
    )
    DropdownOverlay(
        visible = state.paletteOpen,
        onDismiss = { state.paletteOpen = false },
    ) {
        CommandPalette(
            files = openableFiles(state.tree),
            backend = state.backend,
            uiHost = paletteHost,
            onOpenFile = { node -> node.filePath?.let { state.open(it, node.name); state.paletteOpen = false } },
            onOpenAt = { path, offset -> state.openAt(path, offset); state.paletteOpen = false },
            onClose = { state.paletteOpen = false },
            // The focused editor, so a caret-aware command can list, enable, and act on the real code.
            // Read-only tabs are excluded: an action that edits has nothing to edit there.
            editorTarget = state.active?.takeIf { !it.readOnly }?.let {
                PaletteEditorTarget(it.path, it.session.doc.text, it.session.selection.min, it.session.selection.max)
            },
            onEffects = { effects ->
                state.paletteOpen = false
                state.applyActionEffects(effects, navigate = pluginNavigator)
            },
        )
    }
}

/**
 * Bridges the built-in UI actions (palette commands, the phone's More sheet, the wide layout's stripe buttons)
 * to the editor's navigation callbacks. Global settings and the SDK/keystore managers all live behind the
 * Settings & Tools hub, so they route through one HUB destination. [onDone] closes the surface the action was
 * picked from.
 */
internal fun editorActionHost(
    state: IdeUiState,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenIconManager: () -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onOpenModuleConfig: (String?) -> Unit,
    onCloseProject: () -> Unit,
    onDone: () -> Unit,
): UiActionHost = object : UiActionHost {
    override val backend: IdeBackend = state.backend
    override fun navigate(destination: String) {
        onDone()
        when (destination) {
            UiDestinations.HUB -> onOpenHub()
            UiDestinations.MODULES -> onOpenModuleConfig(null)
            UiDestinations.DEPENDENCIES -> onOpenDependencies(null)
            UiDestinations.ICONS -> onOpenIconManager()
            UiDestinations.LOGS -> state.logsOpen = true
            UiDestinations.PROJECTS -> onCloseProject()
        }
    }
    override fun toggleTheme() { onDone(); onToggleTheme() }
    override fun openFile(path: String, offset: Int) { onDone(); state.openAt(path, offset) }
}

/**
 * The destinations that present as sheets rather than panes: the phone's More menu and Logs viewer. (The
 * wide layout puts the More rows on its stripe and docks the Logs viewer in the bottom pane.)
 */
@Composable
internal fun DestinationSheets(
    state: IdeUiState,
    onOpenModuleConfig: (String?) -> Unit,
    onOpenDependencies: (String?) -> Unit,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenIconManager: () -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
    /** The phone shows the Logs viewer as a sheet; the wide layout docks it in the bottom pane instead. */
    logsAsSheet: Boolean,
) {
    BottomSheet(visible = state.moreOpen, onDismiss = { state.moreOpen = false }, heightFraction = 0.45f) {
        // The rows are UI-side actions resolved from the registry; the host bridges them to the app's
        // navigation callbacks. Adding a row is a registration (see BuiltInUiActions), not an edit here.
        val moreHost = remember(state) {
            editorActionHost(
                state, onToggleTheme, onOpenHub, onOpenIconManager, onOpenDependencies, onOpenModuleConfig, onCloseProject,
                onDone = { state.moreOpen = false },
            )
        }
        MoreSheetContent(host = moreHost, modifier = Modifier.fillMaxWidth().weight(1f))
    }
    // The phone's Logs viewer: opened from the More menu or the palette; a tall sheet so a stack trace has room.
    BottomSheet(
        visible = logsAsSheet && state.logsOpen,
        onDismiss = { state.closeLogs() },
        heightFraction = 0.9f,
    ) {
        LogsScreen(
            backend = state.backend,
            fileActions = fileActions,
            modifier = Modifier.fillMaxWidth().weight(1f),
            initialSource = state.logsSource,
        )
    }
}

/** The phone's "More" menu: the screens a project is configured from, which the wide layout keeps on its left
 *  stripe. Rows are UI-side actions resolved from [UiActionRegistry] (the built-ins, plus anything an in-UI
 *  plugin contributes). */
@Composable
internal fun MoreSheetContent(
    host: UiActionHost,
    modifier: Modifier = Modifier,
) {
    UiPluginHost.ensureLoaded()
    val actions = UiActionRegistry.forPlace(UiActionPlaces.MORE_MENU, host)
    // Scrollable so every row is reachable when the sheet is short, e.g. the soft keyboard is up and the sheet
    // has been lifted above it (issue #994).
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(stringResource(Res.string.more), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 10.dp))
        actions.forEach { a ->
            MoreRow(actionIcon(a.iconId), localizedUiActionText(a), localizedUiActionDescription(a) ?: "") { a.perform(host) }
        }
    }
}

@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.md))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.sm)),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.material3.Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

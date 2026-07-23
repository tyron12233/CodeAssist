package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.RailDestination
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.components.AnalyticsToggleRow
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.ComingSoon
import dev.ide.ui.components.CommandPalette
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.ext.UiDestinations
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.edsheet_source_control
import dev.ide.ui.generated.resources.edsheet_source_control_desc
import dev.ide.ui.generated.resources.more
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PaletteOverlay(state: IdeUiState, onToggleTheme: () -> Unit, onOpenHub: () -> Unit, onOpenDependencies: (String?) -> Unit) {
    // The palette's UI-navigation commands come from UiActionRegistry; this host bridges them to the app's
    // navigation callbacks (the same pattern as the More menu). Global settings + SDK/keystore managers all
    // live behind the Settings & Tools hub now, so they route through one HUB destination.
    val paletteHost = object : UiActionHost {
        override val backend: IdeBackend = state.backend
        override fun navigate(destination: String) {
            state.paletteOpen = false
            when (destination) {
                UiDestinations.HUB -> onOpenHub()
                UiDestinations.DEPENDENCIES -> onOpenDependencies(null)
                UiDestinations.LOGS -> state.logsOpen = true
            }
        }
        override fun toggleTheme() { state.paletteOpen = false; onToggleTheme() }
        override fun openFile(path: String, offset: Int) { state.paletteOpen = false; state.openAt(path, offset) }
    }
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
        )
    }
}

/**
 * The destinations that present as sheets rather than panes: Search (phone only — it's a side pane on
 * desktop), the Source-control "coming soon" placeholder, and the More menu of secondary actions.
 */
@Composable
internal fun DestinationSheets(
    state: IdeUiState,
    compact: Boolean,
    onOpenModuleConfig: (String?) -> Unit,
    onToggleTheme: () -> Unit,
    onOpenHub: () -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val indexStatus by state.backend.search.indexStatus.collectAsState()
    if (compact) {
        BottomSheet(visible = state.searchOpen, onDismiss = { state.searchOpen = false }, heightFraction = 0.85f) {
            SearchScreen(
                backend = state.backend,
                indexing = indexStatus.building,
                onOpenAt = { p, o -> state.openAt(p, o); state.searchOpen = false },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
    BottomSheet(visible = state.sheetDest == RailDestination.Source, onDismiss = { state.sheetDest = null }, heightFraction = 0.55f) {
        ComingSoon(
            icon = CaIcons.gitBranch,
            title = stringResource(Res.string.edsheet_source_control),
            description = stringResource(Res.string.edsheet_source_control_desc),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    BottomSheet(visible = state.sheetDest == RailDestination.More, onDismiss = { state.sheetDest = null }, heightFraction = 0.62f) {
        // The "More" rows are UI-side actions resolved from the registry; the host bridges them to the app's
        // navigation/theme callbacks. Adding a row is a registration (see BuiltInUiActions), not an edit here.
        val moreHost = remember(state) {
            object : UiActionHost {
                override val backend: IdeBackend = state.backend
                override fun navigate(destination: String) {
                    state.sheetDest = null
                    when (destination) {
                        UiDestinations.HUB -> onOpenHub()
                        UiDestinations.MODULES -> onOpenModuleConfig(null)
                        UiDestinations.LOGS -> state.logsOpen = true
                        UiDestinations.PROJECTS -> onCloseProject()
                    }
                }
                override fun toggleTheme() { state.sheetDest = null; onToggleTheme() }
                override fun openFile(path: String, offset: Int) { state.sheetDest = null; state.openAt(path, offset) }
            }
        }
        MoreSheetContent(
            backend = state.backend,
            host = moreHost,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    // The Logs viewer — opened from the More menu; a tall sheet so a stack trace has room.
    BottomSheet(visible = state.logsOpen, onDismiss = { state.logsOpen = false }, heightFraction = 0.9f) {
        LogsScreen(
            backend = state.backend,
            fileActions = fileActions,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** The "More" menu: secondary actions that don't warrant a top-level destination. Rows are UI-side actions
 *  resolved from [UiActionRegistry] (the built-ins, plus anything an in-UI plugin contributes). */
@Composable
internal fun MoreSheetContent(
    backend: IdeBackend,
    host: UiActionHost,
    modifier: Modifier = Modifier,
) {
    UiPluginHost.ensureLoaded()
    val actions = UiActionRegistry.forPlace(UiActionPlaces.MORE_MENU, host)
    // Scrollable so every row (incl. "Close project") is reachable when the sheet is short — e.g. the soft
    // keyboard is up and the sheet has been lifted above it (issue #994).
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(stringResource(Res.string.more), color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 10.dp))
        actions.forEach { a ->
            MoreRow(actionIcon(a.iconId), a.text, a.description ?: "") { a.perform(host) }
        }

        // Performance-analytics opt-in lives here (a settings surface) rather than on the home screen, so it's
        // a deliberate one-time choice the user can revisit, not a permanent fixture of the project picker.
        if (backend.diagnostics.analyticsAvailable()) {
            var on by remember { mutableStateOf(backend.diagnostics.analyticsConsent() == true) }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Ca.colors.separator))
            Box(Modifier.padding(horizontal = 6.dp)) {
                AnalyticsToggleRow(enabled = on, onChange = { on = it; backend.diagnostics.setAnalyticsConsent(it) })
            }
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
            Modifier.size(38.dp).background(Ca.colors.accentSoft, androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.sm)),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(icon, null, Modifier.size(19.dp), tint = Ca.colors.accent) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Ca.colors.textTertiary, style = Ca.type.caption2)
        }
        androidx.compose.material3.Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = Ca.colors.textTertiary)
    }
}

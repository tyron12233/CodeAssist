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
import dev.ide.ui.components.AnalyticsToggleRow
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.ComingSoon
import dev.ide.ui.components.CommandPalette
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

@Composable
internal fun PaletteOverlay(state: IdeUiState, onToggleTheme: () -> Unit, onOpenSettings: () -> Unit, onOpenDependencies: (String?) -> Unit, onOpenSdkManager: () -> Unit) {
    DropdownOverlay(
        visible = state.paletteOpen,
        onDismiss = { state.paletteOpen = false },
    ) {
        CommandPalette(
            files = openableFiles(state.tree),
            backend = state.backend,
            onOpenFile = { node -> node.filePath?.let { state.open(it, node.name); state.paletteOpen = false } },
            onOpenAt = { path, offset -> state.openAt(path, offset); state.paletteOpen = false },
            onToggleTheme = onToggleTheme,
            onReindex = { state.backend.reindex(); state.paletteOpen = false },
            onOpenDependencies = { state.paletteOpen = false; onOpenDependencies(null) },
            onManageSdk = { state.paletteOpen = false; onOpenSdkManager() },
            onOpenSettings = { state.paletteOpen = false; onOpenSettings() },
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
    onOpenSettings: () -> Unit,
    onOpenSdkManager: () -> Unit,
    onCloseProject: () -> Unit,
    fileActions: FileActions,
) {
    val indexStatus by state.backend.indexStatus.collectAsState()
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
            title = "Source Control",
            description = "Git integration — staging, diffs, commit & push — is on the roadmap.",
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    BottomSheet(visible = state.sheetDest == RailDestination.More, onDismiss = { state.sheetDest = null }, heightFraction = 0.62f) {
        MoreSheetContent(
            backend = state.backend,
            onSettings = { state.sheetDest = null; onOpenSettings() },
            onModuleSettings = { state.sheetDest = null; onOpenModuleConfig(null) },
            onReindex = { state.sheetDest = null; state.backend.reindex() },
            onToggleTheme = { state.sheetDest = null; onToggleTheme() },
            onManageSdk = { state.sheetDest = null; onOpenSdkManager() },
            onViewLogs = { state.sheetDest = null; state.logsOpen = true },
            onCloseProject = { state.sheetDest = null; onCloseProject() },
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

/** The "More" menu: secondary actions that don't warrant a top-level destination. */
@Composable
private fun MoreSheetContent(
    backend: IdeBackend,
    onSettings: () -> Unit,
    onModuleSettings: () -> Unit,
    onReindex: () -> Unit,
    onToggleTheme: () -> Unit,
    onManageSdk: () -> Unit,
    onViewLogs: () -> Unit,
    onCloseProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrollable so every row (incl. "Close project") is reachable when the sheet is short — e.g. the soft
    // keyboard is up and the sheet has been lifted above it (issue #994).
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("More", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 10.dp))
        MoreRow(CaIcons.gear, "Settings", "Appearance · editor · completion · analysis · build", onSettings)
        MoreRow(CaIcons.layers, "Modules", "Add/remove modules · Java version · dependencies · repositories", onModuleSettings)
        MoreRow(CaIcons.pkg, "SDK Manager", "Download Android SDK packages & JDK sources", onManageSdk)
        MoreRow(CaIcons.refresh, "Re-index project", "Rebuild symbol & completion indexes", onReindex)
        MoreRow(CaIcons.terminal, "View logs", "Editor, analysis & build logs — share when something's off", onViewLogs)
        MoreRow(CaIcons.eye, "Toggle theme", "Switch between light and dark", onToggleTheme)
        MoreRow(CaIcons.close, "Close project", "Back to all projects", onCloseProject)

        // Performance-analytics opt-in lives here (a settings surface) rather than on the home screen, so it's
        // a deliberate one-time choice the user can revisit, not a permanent fixture of the project picker.
        if (backend.analyticsAvailable()) {
            var on by remember { mutableStateOf(backend.analyticsConsent() == true) }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Ca.colors.separator))
            Box(Modifier.padding(horizontal = 6.dp)) {
                AnalyticsToggleRow(enabled = on, onChange = { on = it; backend.setAnalyticsConsent(it) })
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

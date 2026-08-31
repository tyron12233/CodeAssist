package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.plugins_failed
import dev.ide.ui.generated.resources.plugins_installed_empty
import dev.ide.ui.generated.resources.plugins_required
import dev.ide.ui.generated.resources.plugins_requires
import dev.ide.ui.generated.resources.plugins_restart_hint
import dev.ide.ui.generated.resources.plugins_tab_builtin
import dev.ide.ui.generated.resources.plugins_tab_installed
import dev.ide.ui.generated.resources.settings_plugins
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The two kinds of plugin the IDE loads, one tab each. */
private enum class PluginTab(val label: StringResource) {
    BuiltIn(Res.string.plugins_tab_builtin),
    Installed(Res.string.plugins_tab_installed),
}

/**
 * The Plugins settings screen: enable or disable the plugins this build loaded, split across two tabs.
 * **Built-in** plugins ship inside the IDE; **Installed** ones came from a separate app the user installed and
 * carry the package they came from, plus the reason any of them failed to load. Each tab's count is on its
 * label, so an installed plugin is visible without switching. Essential plugins are shown locked (a "Required"
 * pill instead of a switch), which never applies to an installed plugin. A change is persisted immediately
 * (app-global) but applied on the next launch, so a restart hint appears once anything is toggled.
 */
@Composable
fun PluginsScreen(backend: IdeBackend, onBack: () -> Unit) {
    var plugins by remember { mutableStateOf(backend.settings.pluginCatalog()) }
    var changed by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(PluginTab.BuiltIn) }

    val builtIn = plugins.filter { it.builtIn }
    val installed = plugins.filterNot { it.builtIn }

    ExpressiveScaffold(title = stringResource(Res.string.settings_plugins), onBack = onBack) { innerPadding ->
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(innerPadding)) {
            // Above the tabs: a toggle on either tab needs the same restart, so the hint is not per-tab.
            if (changed) RestartHint()
            PluginTabs(tab, builtIn.size, installed.size) { tab = it }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val shown = if (tab == PluginTab.BuiltIn) builtIn else installed
                if (shown.isEmpty()) {
                    EmptyInstalled()
                } else {
                    for (p in shown) {
                        PluginRow(p) { enabled ->
                            backend.settings.setPluginEnabled(p.id, enabled)
                            plugins = backend.settings.pluginCatalog()
                            changed = true
                        }
                    }
                }
                AdSlot(AdPlacement.SETTINGS)
            }
        }
    }
}

@Composable
private fun PluginTabs(selected: PluginTab, builtInCount: Int, installedCount: Int, onSelect: (PluginTab) -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = PluginTab.entries.indexOf(selected),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        for (t in PluginTab.entries) {
            Tab(
                selected = t == selected,
                onClick = { onSelect(t) },
                // BOTH colours must be given: M3's `Tab` defaults `unselectedContentColor` to
                // `selectedContentColor`, so leaving them out paints every tab the row's content colour and
                // the selection reads only from the indicator.
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    TabLabel(
                        stringResource(t.label),
                        if (t == PluginTab.BuiltIn) builtInCount else installedCount,
                    )
                },
            )
        }
    }
}

@Composable
private fun TabLabel(text: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun EmptyInstalled() {
    Text(
        stringResource(Res.string.plugins_installed_empty),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun RestartHint() {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.md))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CaIcons.info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(Res.string.plugins_restart_hint), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PluginRow(p: UiPluginInfo, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (p.description.isNotBlank()) {
                Text(p.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            val meta = buildList {
                if (p.version.isNotBlank()) add("v${p.version}")
                if (p.origin.isNotBlank()) add(p.origin)
                if (p.dependsOn.isNotEmpty()) add(stringResource(Res.string.plugins_requires, p.dependsOn.joinToString(", ")))
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  ·  "), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
            }
            p.error?.let {
                Text(
                    stringResource(Res.string.plugins_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (p.essential) RequiredPill() else CaSwitch(p.enabled, onToggle)
    }
}

@Composable
private fun RequiredPill() {
    Text(
        stringResource(Res.string.plugins_required),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

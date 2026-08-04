package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.plugins_required
import dev.ide.ui.generated.resources.plugins_requires
import dev.ide.ui.generated.resources.plugins_restart_hint
import dev.ide.ui.generated.resources.settings_plugins
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The Plugins settings screen: enable or disable built-in plugins, reachable from the Settings & Tools hub.
 * Essential plugins are shown locked (a "Required" pill instead of a switch). A change is persisted immediately
 * (app-global) but applied on the next launch, so a restart hint appears once anything is toggled.
 */
@Composable
fun PluginsScreen(backend: IdeBackend, onBack: () -> Unit) {
    var plugins by remember { mutableStateOf(backend.settings.pluginCatalog()) }
    var changed by remember { mutableStateOf(false) }

    ExpressiveScaffold(title = stringResource(Res.string.settings_plugins), onBack = onBack) { innerPadding ->
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (changed) RestartHint()
                for (p in plugins) {
                    PluginRow(p) { enabled ->
                        backend.settings.setPluginEnabled(p.id, enabled)
                        plugins = backend.settings.pluginCatalog()
                        changed = true
                    }
                }
            AdSlot(AdPlacement.SETTINGS)
        }
    }
}

@Composable
private fun RestartHint() {
    Row(
        Modifier.fillMaxWidth()
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
                if (p.dependsOn.isNotEmpty()) add(stringResource(Res.string.plugins_requires, p.dependsOn.joinToString(", ")))
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  ·  "), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
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

package dev.ide.ui.screens

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
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.GlassMaterial
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

    Box(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            GlassSurface(Modifier.fillMaxWidth(), GlassMaterial.Regular) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButtonCa(CaIcons.chevronLeft, stringResource(Res.string.back), onBack)
                    Icon(CaIcons.box, null, Modifier.size(20.dp), tint = Ca.colors.accent)
                    Text(
                        stringResource(Res.string.settings_plugins), color = Ca.colors.textPrimary,
                        style = Ca.type.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
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
            }
        }
    }
}

@Composable
private fun RestartHint() {
    Row(
        Modifier.fillMaxWidth()
            .background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.md))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CaIcons.info, null, Modifier.size(18.dp), tint = Ca.colors.accent)
        Text(stringResource(Res.string.plugins_restart_hint), color = Ca.colors.textPrimary, style = Ca.type.footnote)
    }
}

@Composable
private fun PluginRow(p: UiPluginInfo, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.name, color = Ca.colors.textPrimary, style = Ca.type.body, fontWeight = FontWeight.SemiBold)
            if (p.description.isNotBlank()) {
                Text(p.description, color = Ca.colors.textSecondary, style = Ca.type.footnote)
            }
            val meta = buildList {
                if (p.version.isNotBlank()) add("v${p.version}")
                if (p.dependsOn.isNotEmpty()) add(stringResource(Res.string.plugins_requires, p.dependsOn.joinToString(", ")))
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  ·  "), color = Ca.colors.textTertiary, style = Ca.type.caption)
            }
        }
        if (p.essential) RequiredPill() else CaSwitch(p.enabled, onToggle)
    }
}

@Composable
private fun RequiredPill() {
    Text(
        stringResource(Res.string.plugins_required),
        color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(Ca.colors.surface3, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

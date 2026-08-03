package dev.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.settings_code_style
import dev.ide.ui.generated.resources.settings_code_style_subtitle
import dev.ide.ui.generated.resources.settings_hub_title
import dev.ide.ui.generated.resources.settings_keystore_manager
import dev.ide.ui.generated.resources.settings_keystore_manager_subtitle
import dev.ide.ui.generated.resources.settings_plugins
import dev.ide.ui.generated.resources.settings_plugins_subtitle
import dev.ide.ui.generated.resources.settings_sdk_manager
import dev.ide.ui.generated.resources.settings_sdk_manager_subtitle
import dev.ide.ui.generated.resources.settings_settings
import dev.ide.ui.generated.resources.settings_settings_subtitle
import dev.ide.ui.generated.resources.settings_storage
import dev.ide.ui.generated.resources.settings_storage_subtitle
import dev.ide.ui.icons.CaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The Settings & Tools hub — the single entry to the settings and toolchain managers, reachable both from the
 * project picker (with no project open) and from inside the editor. Redesigned for Material 3 Expressive: a
 * collapsing [LargeTopAppBar] over one grouped tonal card of [ListItem] destinations, each with a colorful
 * tonal icon container. Pure navigation: each row hands control back to the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onBack: () -> Unit,
    onOpenGlobalSettings: () -> Unit,
    onOpenCodeStyle: () -> Unit,
    onOpenSdkManager: () -> Unit,
    onOpenKeystoreManager: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.settings_hub_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(CaIcons.chevronLeft, stringResource(Res.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val rows = listOf(
                HubDest(CaIcons.gear, stringResource(Res.string.settings_settings), stringResource(Res.string.settings_settings_subtitle), onOpenGlobalSettings),
                HubDest(CaIcons.braces, stringResource(Res.string.settings_code_style), stringResource(Res.string.settings_code_style_subtitle), onOpenCodeStyle),
                HubDest(CaIcons.pkg, stringResource(Res.string.settings_sdk_manager), stringResource(Res.string.settings_sdk_manager_subtitle), onOpenSdkManager),
                HubDest(CaIcons.key, stringResource(Res.string.settings_keystore_manager), stringResource(Res.string.settings_keystore_manager_subtitle), onOpenKeystoreManager),
                HubDest(CaIcons.box, stringResource(Res.string.settings_plugins), stringResource(Res.string.settings_plugins_subtitle), onOpenPlugins),
                HubDest(CaIcons.layers, stringResource(Res.string.settings_storage), stringResource(Res.string.settings_storage_subtitle), onOpenStorage),
            )
            Card(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                rows.forEachIndexed { i, dest ->
                    if (i > 0) HorizontalDivider(
                        Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    HubRow(dest)
                }
            }
        }
    }
}

private class HubDest(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun HubRow(dest: HubDest) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.clickable(onClick = dest.onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            // One consistent accent tone for every destination (no per-row color).
            Surface(shape = CircleShape, color = scheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(dest.icon, null, Modifier.size(22.dp), tint = scheme.onPrimaryContainer)
                }
            }
        },
        headlineContent = { Text(dest.title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(dest.subtitle, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = {
            Icon(CaIcons.chevronRight, null, Modifier.size(18.dp), tint = scheme.outline)
        },
    )
}

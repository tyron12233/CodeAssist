package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.BasicAlertDialog
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
import dev.ide.ui.backend.UiPluginChange
import dev.ide.ui.backend.UiPluginChangeKind
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.PluginConsent
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.plugins_change_disabled
import dev.ide.ui.generated.resources.plugins_change_enabled
import dev.ide.ui.generated.resources.plugins_change_installed
import dev.ide.ui.generated.resources.plugins_change_uninstalled
import dev.ide.ui.generated.resources.plugins_change_updated
import dev.ide.ui.generated.resources.plugins_changes_more
import dev.ide.ui.generated.resources.plugins_failed
import dev.ide.ui.generated.resources.plugins_installed_empty
import dev.ide.ui.generated.resources.plugins_logs
import dev.ide.ui.generated.resources.plugins_required
import dev.ide.ui.generated.resources.plugins_requires
import dev.ide.ui.generated.resources.plugins_restart_hint
import dev.ide.ui.generated.resources.plugins_restart_now
import dev.ide.ui.generated.resources.plugins_restarting
import dev.ide.ui.generated.resources.plugins_review
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
 * carry the package they came from, plus the reason any of them failed to load. A plugin app whose manifest
 * the IDE could not read is listed there too, with its reason and no switch. Each tab's count is on its
 * label, so an installed plugin is visible without switching. Essential plugins are shown locked (a "Required"
 * pill instead of a switch), which never applies to an installed plugin.
 *
 * Nothing here is live: plugins are loaded once, when the app starts. A change is persisted immediately
 * (app-global) and applied by restarting, so the screen names everything that is waiting, both the answers
 * given here and what has happened to the plugin apps on the device since launch (installed, updated,
 * uninstalled), and offers the restart itself on a host that can restart.
 */
@Composable
fun PluginsScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    /** Show this plugin's own log records. Null when there is no editor to show the Logs viewer over. */
    onOpenLogs: ((pluginId: String) -> Unit)? = null,
    /** Save open work and restart the app, applying the waiting changes. Null where the host cannot restart
     *  itself (desktop), which leaves the hint stating the restart without offering to do it. */
    onRestart: (() -> Unit)? = null,
) {
    var plugins by remember { mutableStateOf(backend.settings.pluginCatalog()) }
    // Read from the backend rather than tracked as "the user touched something here": it also covers a
    // plugin app installed or updated on the device since launch, and it goes away by itself when a change
    // is answered back to what is already loaded.
    var pending by remember { mutableStateOf(backend.settings.pendingPluginChanges()) }
    var tab by remember { mutableStateOf(PluginTab.BuiltIn) }
    // The plugin whose consent sheet is open. Nothing loads while this is unanswered, so the sheet is a
    // gate rather than a notification.
    var asking by remember { mutableStateOf<UiPluginInfo?>(null) }

    val builtIn = plugins.filter { it.builtIn }
    val installed = plugins.filterNot { it.builtIn }

    ExpressiveScaffold(title = stringResource(Res.string.settings_plugins), onBack = onBack) { innerPadding ->
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(innerPadding)) {
            // Above the tabs: a toggle on either tab needs the same restart, so the hint is not per-tab.
            if (pending.isNotEmpty()) RestartHint(pending, onRestart)
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
                        PluginRow(
                            p,
                            onReview = { asking = p },
                            onToggle = { enabled ->
                                backend.settings.setPluginEnabled(p.id, enabled)
                                plugins = backend.settings.pluginCatalog()
                                pending = backend.settings.pendingPluginChanges()
                            },
                            // Built-ins log under their own ids too, but their logs are the IDE's; this is
                            // for the author of an installed plugin watching their own code run.
                            onOpenLogs = onOpenLogs
                                ?.takeIf { !p.builtIn && p.togglable }
                                ?.let { open -> { open(p.id) } },
                        )
                    }
                }
                AdSlot(AdPlacement.SETTINGS)
            }
        }
    }

    asking?.let { plugin ->
        PluginConsentDialog(
            plugin = plugin,
            onAnswer = { granted ->
                backend.settings.setPluginConsent(plugin.id, granted)
                plugins = backend.settings.pluginCatalog()
                pending = backend.settings.pendingPluginChanges()
                asking = null
            },
        )
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

/** How many waiting changes are named before the rest are counted. */
private const val MAX_LISTED_CHANGES = 4

/**
 * What a restart would apply, and the restart itself where the host can do it. Each change is named rather
 * than counted, so an installed plugin app's update is visible as something the IDE has seen.
 */
@Composable
private fun RestartHint(changes: List<UiPluginChange>, onRestart: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.md))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(CaIcons.info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(Res.string.plugins_restart_hint),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        for (c in changes.take(MAX_LISTED_CHANGES)) {
            Text(
                "${c.name}  ·  ${changeLabel(c.kind)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (changes.size > MAX_LISTED_CHANGES) {
            Text(
                stringResource(Res.string.plugins_changes_more, changes.size - MAX_LISTED_CHANGES),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (onRestart != null) {
            // Taking the app down takes a moment (the process that does it has to start first), and the
            // screen stays up meanwhile, so the button reports that it was pressed and refuses a second
            // press rather than starting the restart twice.
            var restarting by remember { mutableStateOf(false) }
            TextButton(
                onClick = { restarting = true; onRestart() },
                enabled = !restarting,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.align(Alignment.End).heightIn(min = 32.dp),
            ) {
                Text(
                    stringResource(
                        if (restarting) Res.string.plugins_restarting else Res.string.plugins_restart_now,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun changeLabel(kind: UiPluginChangeKind): String = stringResource(
    when (kind) {
        UiPluginChangeKind.INSTALLED -> Res.string.plugins_change_installed
        UiPluginChangeKind.UPDATED -> Res.string.plugins_change_updated
        UiPluginChangeKind.UNINSTALLED -> Res.string.plugins_change_uninstalled
        UiPluginChangeKind.ENABLED -> Res.string.plugins_change_enabled
        UiPluginChangeKind.DISABLED -> Res.string.plugins_change_disabled
    }
)

@Composable
private fun PluginRow(
    p: UiPluginInfo,
    onReview: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenLogs: (() -> Unit)? = null,
) {
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
            if (p.needsConsent) {
                Text(
                    "Not running yet. Review what it can do before allowing it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            p.error?.let {
                Text(
                    stringResource(Res.string.plugins_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // An installed plugin's own records, which the Logs viewer already attributes by plugin id. Its
            // author's alternative is reading the whole IDE's log and picking their lines out of it.
            if (onOpenLogs != null) {
                TextButton(
                    onClick = onOpenLogs,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.offset(x = (-6).dp).heightIn(min = 28.dp),
                ) {
                    Text(stringResource(Res.string.plugins_logs), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // A plugin the IDE could not read has no id to toggle, so its row carries only its reason. One
        // awaiting an answer gets Review rather than a switch: a switch would say "off", when the truth is
        // that nothing has been decided and it has never run.
        when {
            p.essential -> RequiredPill()
            p.needsConsent -> TextButton(onReview) { Text(stringResource(Res.string.plugins_review)) }
            p.togglable -> CaSwitch(p.enabled, onToggle)
        }
    }
}

/** The consent gate, as a modal the user has to answer before the plugin is allowed to load. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PluginConsentDialog(plugin: UiPluginInfo, onAnswer: (Boolean) -> Unit) {
    // Dismissing without answering leaves the plugin exactly as it was: still not running, still asked
    // about next time. Only the two explicit buttons record a decision.
    BasicAlertDialog(onDismissRequest = { onAnswer(false) }) {
        PluginConsent(plugin, onRefuse = { onAnswer(false) }, onAccept = { onAnswer(true) })
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

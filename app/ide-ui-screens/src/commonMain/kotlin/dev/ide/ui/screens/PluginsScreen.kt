package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPluginChange
import dev.ide.ui.backend.UiPluginChangeKind
import dev.ide.ui.backend.UiPluginDownload
import dev.ide.ui.backend.UiPluginDownloads
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.CapabilityList
import dev.ide.ui.components.Chip
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PluginConsent
import dev.ide.ui.components.PluginGlyph
import dev.ide.ui.components.PluginStatusPill
import dev.ide.ui.components.formatSizeShort
import dev.ide.ui.components.groupCapabilities
import dev.ide.ui.components.shortSignature
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.plugins_by_codeassist
import dev.ide.ui.generated.resources.plugins_change_disabled
import dev.ide.ui.generated.resources.plugins_change_enabled
import dev.ide.ui.generated.resources.plugins_change_installed
import dev.ide.ui.generated.resources.plugins_change_uninstalled
import dev.ide.ui.generated.resources.plugins_change_updated
import dev.ide.ui.generated.resources.plugins_changes_more
import dev.ide.ui.generated.resources.plugins_detail_adds
import dev.ide.ui.generated.resources.plugins_detail_adds_none
import dev.ide.ui.generated.resources.plugins_detail_api
import dev.ide.ui.generated.resources.plugins_detail_app_info
import dev.ide.ui.generated.resources.plugins_detail_app_version
import dev.ide.ui.generated.resources.plugins_detail_builtin
import dev.ide.ui.generated.resources.plugins_detail_copied
import dev.ide.ui.generated.resources.plugins_detail_depends
import dev.ide.ui.generated.resources.plugins_detail_details
import dev.ide.ui.generated.resources.plugins_detail_enabled
import dev.ide.ui.generated.resources.plugins_detail_enabled_hint
import dev.ide.ui.generated.resources.plugins_detail_id
import dev.ide.ui.generated.resources.plugins_detail_installed
import dev.ide.ui.generated.resources.plugins_detail_installer
import dev.ide.ui.generated.resources.plugins_detail_installer_direct
import dev.ide.ui.generated.resources.plugins_detail_min_host
import dev.ide.ui.generated.resources.plugins_detail_min_host_value
import dev.ide.ui.generated.resources.plugins_detail_open_app
import dev.ide.ui.generated.resources.plugins_detail_package
import dev.ide.ui.generated.resources.plugins_detail_problem
import dev.ide.ui.generated.resources.plugins_detail_signature
import dev.ide.ui.generated.resources.plugins_detail_signature_none
import dev.ide.ui.generated.resources.plugins_detail_size
import dev.ide.ui.generated.resources.plugins_detail_updated
import dev.ide.ui.generated.resources.plugins_detail_version
import dev.ide.ui.generated.resources.plugins_failed
import dev.ide.ui.generated.resources.plugins_get_download
import dev.ide.ui.generated.resources.plugins_get_empty
import dev.ide.ui.generated.resources.plugins_get_installed
import dev.ide.ui.generated.resources.plugins_get_installed_version
import dev.ide.ui.generated.resources.plugins_get_intro
import dev.ide.ui.generated.resources.plugins_get_loading
import dev.ide.ui.generated.resources.plugins_get_needs_host
import dev.ide.ui.generated.resources.plugins_get_offline
import dev.ide.ui.generated.resources.plugins_get_release_notes
import dev.ide.ui.generated.resources.plugins_get_retry
import dev.ide.ui.generated.resources.plugins_get_update
import dev.ide.ui.generated.resources.plugins_installed_empty
import dev.ide.ui.generated.resources.plugins_logs
import dev.ide.ui.generated.resources.plugins_not_running
import dev.ide.ui.generated.resources.plugins_open_details
import dev.ide.ui.generated.resources.plugins_required
import dev.ide.ui.generated.resources.plugins_restart_hint
import dev.ide.ui.generated.resources.plugins_restart_now
import dev.ide.ui.generated.resources.plugins_restarting
import dev.ide.ui.generated.resources.plugins_review
import dev.ide.ui.generated.resources.plugins_search
import dev.ide.ui.generated.resources.plugins_search_none
import dev.ide.ui.generated.resources.plugins_signed_like_ide
import dev.ide.ui.generated.resources.plugins_status_failed
import dev.ide.ui.generated.resources.plugins_status_off
import dev.ide.ui.generated.resources.plugins_status_on
import dev.ide.ui.generated.resources.plugins_status_review
import dev.ide.ui.generated.resources.plugins_tab_builtin
import dev.ide.ui.generated.resources.plugins_tab_get
import dev.ide.ui.generated.resources.plugins_tab_installed
import dev.ide.ui.generated.resources.project_age_just_now
import dev.ide.ui.generated.resources.project_age_days
import dev.ide.ui.generated.resources.project_age_hours
import dev.ide.ui.generated.resources.project_age_minutes
import dev.ide.ui.generated.resources.project_age_weeks
import dev.ide.ui.generated.resources.settings_plugins
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The Plugins screen's tabs: what can be downloaded, what the user installed, what ships with the IDE. */
enum class PluginsTab(val label: StringResource) {
    Get(Res.string.plugins_tab_get),
    Installed(Res.string.plugins_tab_installed),
    BuiltIn(Res.string.plugins_tab_builtin),
}

/**
 * Plugins: get new ones, and look after the ones this build loaded, across three tabs.
 *
 * **Get plugins** lists the plugins published for download (the store backend's `plugin_catalog`), each with
 * what this device already has of it; Download opens the APK in the browser, and Android's installer does the
 * rest. **Installed** plugins came from a separate app the user installed; **Built-in** ones ship inside the
 * IDE. Every row opens the plugin's page: what it is, what it says it adds, and the facts the package manager
 * states about it (who signed it, when it was installed), with its switch, its logs and its app's own pages.
 *
 * Nothing here is live: plugins are loaded once, when the app starts. A change is persisted immediately
 * (app-global) and applied by restarting, so the screen names everything that is waiting and offers the
 * restart itself on a host that can restart.
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
    /** Open a link in the browser: a plugin's download or release page. Null where the host cannot. */
    onOpenUrl: ((String) -> Unit)? = null,
    /** The tab to open on; null picks Installed when anything is installed, else Get plugins. */
    startTab: PluginsTab? = null,
) {
    var plugins by remember { mutableStateOf(backend.settings.pluginCatalog()) }
    // Read from the backend rather than tracked as "the user touched something here": it also covers a
    // plugin app installed or updated on the device since launch, and it goes away by itself when a change
    // is answered back to what is already loaded.
    var pending by remember { mutableStateOf(backend.settings.pendingPluginChanges()) }
    val builtIn = plugins.filter { it.builtIn }
    val installed = plugins.filterNot { it.builtIn }
    var tab by remember { mutableStateOf(startTab ?: if (installed.isNotEmpty()) PluginsTab.Installed else PluginsTab.Get) }
    var query by remember { mutableStateOf("") }
    // The plugin whose page is open, by id, so the page follows the list as it is re-read after a change.
    var detailId by remember { mutableStateOf<String?>(null) }
    val detail = detailId?.let { id -> plugins.firstOrNull { it.id == id } }
    // The plugin whose consent dialog is open. Nothing loads while this is unanswered.
    var asking by remember { mutableStateOf<UiPluginInfo?>(null) }

    val refresh = {
        plugins = backend.settings.pluginCatalog()
        pending = backend.settings.pendingPluginChanges()
    }

    PlatformBackHandler(enabled = detail != null) { detailId = null }

    ExpressiveScaffold(
        // A plugin's page carries its name in its own header, so the bar keeps the screen's title.
        title = stringResource(Res.string.settings_plugins),
        onBack = if (detail != null) ({ detailId = null }) else onBack,
        large = detail == null,
    ) { innerPadding ->
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(innerPadding)) {
            // Above the tabs: a change on any tab needs the same restart, so the hint is not per-tab.
            if (pending.isNotEmpty()) RestartHint(pending, onRestart)
            if (detail != null) {
                PluginDetail(
                    p = detail,
                    onReview = { asking = detail },
                    onToggle = { enabled -> backend.settings.setPluginEnabled(detail.id, enabled); refresh() },
                    onOpenLogs = onOpenLogs?.takeIf { !detail.builtIn && detail.togglable }?.let { open -> { open(detail.id) } },
                    onOpenApp = detail.app?.takeIf { it.canOpen }?.let { { backend.settings.openPluginApp(detail.id) } },
                    onOpenAppInfo = detail.app?.let { { backend.settings.openPluginAppInfo(detail.id) } },
                )
                return@Column
            }
            PluginTabs(tab, installed.size, builtIn.size) { tab = it; query = "" }
            when (tab) {
                PluginsTab.Get -> GetPlugins(backend, onOpenUrl)
                else -> {
                    val all = if (tab == PluginsTab.BuiltIn) builtIn else installed
                    val shown = if (query.isBlank()) all else all.filter { it.matches(query) }
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // A search is worth its space once a list no longer fits a screen.
                        if (all.size > 6) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(Res.string.plugins_search)) },
                                leadingIcon = { Symbol(CaSymbols.search, contentDescription = null, size = 20.dp) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                shape = RoundedCornerShape(Ca.radius.pill),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        when {
                            all.isEmpty() -> EmptyNote(stringResource(Res.string.plugins_installed_empty))
                            shown.isEmpty() -> EmptyNote(stringResource(Res.string.plugins_search_none, query))
                            else -> for (p in shown) {
                                PluginCard(
                                    p,
                                    onOpen = { detailId = p.id },
                                    onReview = { asking = p },
                                    onToggle = { enabled -> backend.settings.setPluginEnabled(p.id, enabled); refresh() },
                                )
                            }
                        }
                        AdSlot(AdPlacement.SETTINGS)
                    }
                }
            }
        }
    }

    asking?.let { plugin ->
        PluginConsentDialog(
            plugin = plugin,
            onAnswer = { granted ->
                backend.settings.setPluginConsent(plugin.id, granted)
                refresh()
                asking = null
            },
            onDismiss = { asking = null },
        )
    }
}

private fun UiPluginInfo.matches(query: String): Boolean {
    val q = query.trim()
    return name.contains(q, ignoreCase = true) || description.contains(q, ignoreCase = true) || id.contains(q, ignoreCase = true)
}

@Composable
private fun PluginTabs(selected: PluginsTab, installedCount: Int, builtInCount: Int, onSelect: (PluginsTab) -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = PluginsTab.entries.indexOf(selected),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        for (t in PluginsTab.entries) {
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
                        when (t) {
                            PluginsTab.Get -> null
                            PluginsTab.Installed -> installedCount
                            PluginsTab.BuiltIn -> builtInCount
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun TabLabel(text: String, count: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(Ca.radius.lg))
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
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.lg))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(CaSymbols.sync, contentDescription = null, size = 20.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                stringResource(Res.string.plugins_restart_hint),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        for (c in changes.take(MAX_LISTED_CHANGES)) {
            Text(
                "${c.name}  ·  ${changeLabel(c.kind)}",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 30.dp),
            )
        }
        if (changes.size > MAX_LISTED_CHANGES) {
            Text(
                stringResource(Res.string.plugins_changes_more, changes.size - MAX_LISTED_CHANGES),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 30.dp),
            )
        }
        if (onRestart != null) {
            // Taking the app down takes a moment (the process that does it has to start first), and the
            // screen stays up meanwhile, so the button reports that it was pressed and refuses a second
            // press rather than starting the restart twice.
            var restarting by remember { mutableStateOf(false) }
            Button(
                onClick = { restarting = true; onRestart() },
                enabled = !restarting,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.align(Alignment.End).heightIn(min = 36.dp),
            ) {
                Text(stringResource(if (restarting) Res.string.plugins_restarting else Res.string.plugins_restart_now))
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

/** What state a plugin is in, as a word and a colour: the same on its row and on its page. */
@Composable
private fun statusOf(p: UiPluginInfo): Pair<String, androidx.compose.ui.graphics.Color> = when {
    p.error != null -> stringResource(Res.string.plugins_status_failed) to MaterialTheme.colorScheme.error
    p.needsConsent -> stringResource(Res.string.plugins_status_review) to Ide.colors.warning
    p.enabled -> stringResource(Res.string.plugins_status_on) to Ide.colors.run
    else -> stringResource(Res.string.plugins_status_off) to MaterialTheme.colorScheme.outline
}

/** A plugin in the list: its face, what it is, its state, and the one control it needs. Tapping opens its page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(
    p: UiPluginInfo,
    onOpen: () -> Unit,
    onReview: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val label = stringResource(Res.string.plugins_open_details, p.name)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PluginGlyph(p.name, p.id)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p.description.isNotBlank()) {
                    Text(
                        p.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    val (status, color) = statusOf(p)
                    PluginStatusPill(status, color)
                    if (p.version.isNotBlank()) Chip("v${p.version}")
                    if (p.app?.signedLikeIde == true) VerifiedChip()
                }
            }
            // A plugin awaiting an answer gets Review rather than a switch: a switch would say "off", when the
            // truth is that nothing has been decided and it has never run.
            when {
                p.essential -> RequiredPill()
                p.needsConsent -> FilledTonalButton(onReview, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(stringResource(Res.string.plugins_review))
                }
                p.togglable -> CaSwitch(p.enabled, onToggle)
                else -> Symbol(CaSymbols.chevronRight, contentDescription = label, size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VerifiedChip() {
    // The same box as [Chip] (at least 22 dp, 3 dp vertical padding, sized by the same label text), with an icon
    // no taller than that text, so it is exactly as tall as the chips beside it at any font size.
    Row(
        Modifier
            .defaultMinSize(minHeight = 22.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
            .padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Symbol(CaSymbols.verified, contentDescription = null, size = 12.dp, filled = true, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(stringResource(Res.string.plugins_by_codeassist), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A plugin's page: who it is, its state and controls, what it says it adds, and the facts about it. */
@Composable
private fun PluginDetail(
    p: UiPluginInfo,
    onReview: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenLogs: (() -> Unit)?,
    onOpenApp: (() -> Unit)?,
    onOpenAppInfo: (() -> Unit)?,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header: face, name, version and where it came from, its state.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PluginGlyph(p.name, p.id, size = 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                val sub = listOfNotNull(p.version.takeIf { it.isNotBlank() }?.let { "v$it" }, p.origin.takeIf { it.isNotBlank() })
                if (sub.isNotEmpty()) Text(sub.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (status, color) = statusOf(p)
                    PluginStatusPill(status, color)
                    if (p.app?.signedLikeIde == true) VerifiedChip()
                }
            }
        }
        if (p.description.isNotBlank()) {
            Text(p.description, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
        }

        p.error?.let { error ->
            Notice(CaSymbols.error, stringResource(Res.string.plugins_detail_problem), stringResource(Res.string.plugins_failed, error), c.errorContainer, c.onErrorContainer)
        }
        if (p.needsConsent) {
            Column(
                Modifier.fillMaxWidth().background(c.tertiaryContainer.copy(alpha = 0.55f), RoundedCornerShape(Ca.radius.lg)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(Res.string.plugins_not_running), style = MaterialTheme.typography.bodyMedium, color = c.onTertiaryContainer)
                Button(onReview, Modifier.align(Alignment.End)) { Text(stringResource(Res.string.plugins_review)) }
            }
        }

        // The switch, or why there is none.
        when {
            p.essential -> Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.plugins_detail_builtin), style = MaterialTheme.typography.bodyMedium, color = c.onSurface, modifier = Modifier.weight(1f))
                    RequiredPill()
                }
            }
            p.togglable && !p.needsConsent -> Section {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.plugins_detail_enabled), style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                        Text(stringResource(Res.string.plugins_detail_enabled_hint), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    CaSwitch(p.enabled, onToggle)
                }
            }
        }

        // What the app itself offers: its own page (licenses), the system's page (uninstall), its logs.
        if (onOpenApp != null || onOpenAppInfo != null || onOpenLogs != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                onOpenApp?.let { ActionButton(CaSymbols.info, stringResource(Res.string.plugins_detail_open_app), it, Modifier.weight(1f)) }
                onOpenAppInfo?.let { ActionButton(CaSymbols.settings, stringResource(Res.string.plugins_detail_app_info), it, Modifier.weight(1f)) }
                onOpenLogs?.let { ActionButton(CaSymbols.notes, stringResource(Res.string.plugins_logs), it, Modifier.weight(1f)) }
            }
        }

        Section(title = stringResource(Res.string.plugins_detail_adds)) {
            if (p.capabilities.isEmpty()) {
                Text(stringResource(Res.string.plugins_detail_adds_none), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            } else {
                CapabilityList(p.capabilities)
            }
        }

        Section(title = stringResource(Res.string.plugins_detail_details)) {
            DetailsTable(p)
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun DetailsTable(p: UiPluginInfo) {
    val now = remember { nowMillis() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableIntStateOf(0) }
    val rows = buildList {
        add(stringResource(Res.string.plugins_detail_id) to p.id)
        if (p.version.isNotBlank()) add(stringResource(Res.string.plugins_detail_version) to p.version)
        p.app?.appVersion?.takeIf { it != p.version }?.let { add(stringResource(Res.string.plugins_detail_app_version) to it) }
        p.app?.packageName?.takeIf { it != p.id }?.let { add(stringResource(Res.string.plugins_detail_package) to it) }
        p.apiVersion?.let { add(stringResource(Res.string.plugins_detail_api) to it.toString()) }
        p.minHostVersion?.let { add(stringResource(Res.string.plugins_detail_min_host) to stringResource(Res.string.plugins_detail_min_host_value, it)) }
        if (p.dependsOn.isNotEmpty()) add(stringResource(Res.string.plugins_detail_depends) to p.dependsOn.joinToString(", "))
        p.app?.let { app ->
            relativeAge(app.firstInstalledAt, now)?.let { add(stringResource(Res.string.plugins_detail_installed) to it) }
            if (app.lastUpdatedAt > app.firstInstalledAt + 60_000) relativeAge(app.lastUpdatedAt, now)?.let { add(stringResource(Res.string.plugins_detail_updated) to it) }
            if (app.sizeBytes > 0) add(stringResource(Res.string.plugins_detail_size) to formatSizeShort(app.sizeBytes))
            add(stringResource(Res.string.plugins_detail_installer) to (app.installer ?: stringResource(Res.string.plugins_detail_installer_direct)))
        }
        if (!p.builtIn) {
            add(stringResource(Res.string.plugins_detail_signature) to (p.signature?.let(::shortSignature) ?: stringResource(Res.string.plugins_detail_signature_none)))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        rows.forEachIndexed { i, (key, value) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            // The certificate is the value worth comparing somewhere else, so it copies in full on a tap.
            val isSignature = key == stringResource(Res.string.plugins_detail_signature) && p.signature != null
            Row(
                Modifier.fillMaxWidth()
                    .then(if (isSignature) Modifier.clickable { clipboard.setText(AnnotatedString(p.signature!!)); copied++ } else Modifier)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
                Text(
                    if (isSignature && copied > 0) "$value  ·  ${stringResource(Res.string.plugins_detail_copied)}" else value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun Notice(glyph: Char, title: String, body: String, fill: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.fillMaxWidth().background(fill, RoundedCornerShape(Ca.radius.lg)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(glyph, contentDescription = null, size = 18.dp, tint = ink)
            Text(title, style = MaterialTheme.typography.titleSmall, color = ink)
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = ink)
    }
}

@Composable
private fun ActionButton(glyph: Char, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick, modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Symbol(glyph, contentDescription = null, size = 20.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The "Get plugins" tab: the published plugins, each against what this device has of it. */
@Composable
private fun GetPlugins(backend: IdeBackend, onOpenUrl: ((String) -> Unit)?) {
    var result by remember { mutableStateOf<UiPluginDownloads?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { result = backend.settings.pluginDownloads() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.plugins_get_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val r = result
        when {
            r == null -> Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.plugins_get_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r.plugins.isEmpty() -> {
                EmptyNote(r.unavailable ?: stringResource(Res.string.plugins_get_empty))
                TextButton({ result = null; scope.launch { attempt++ } }) { Text(stringResource(Res.string.plugins_get_retry)) }
            }
            else -> {
                if (r.cached) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Symbol(CaSymbols.cloudDownload, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(Res.string.plugins_get_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton({ result = null; attempt++ }) { Text(stringResource(Res.string.plugins_get_retry)) }
                    }
                }
                for (d in r.plugins) DownloadCard(d, onOpenUrl)
            }
        }
    }
}

/** One downloadable plugin: what it is for, what it adds, and the one thing to do about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadCard(d: UiPluginDownload, onOpenUrl: ((String) -> Unit)?) {
    val c = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PluginGlyph(d.name, d.id, size = 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (d.languages.isNotBlank()) Chip(d.languages, fill = c.secondaryContainer, textColor = c.onSecondaryContainer)
                        Chip("v${d.version}")
                        d.sizeBytes?.let { Chip(formatSizeShort(it)) }
                        d.installedVersion?.takeIf { d.updateAvailable }?.let {
                            Chip(stringResource(Res.string.plugins_get_installed_version, it), fill = c.tertiaryContainer, textColor = c.onTertiaryContainer)
                        }
                        if (d.publisher == "CodeAssist") VerifiedChip()
                    }
                }
            }
            if (d.tagline.isNotBlank()) Text(d.tagline, style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
            if (d.description.isNotBlank()) {
                Text(
                    d.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
            // What it adds, at a glance: the groups its declared capabilities fall in, on one line.
            val groups = groupCapabilities(d.capabilities)
            if (groups.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(CaSymbols.extension, contentDescription = null, size = 16.dp, tint = c.primary)
                    Text(
                        groups.map { (group, _) -> stringResource(group.label) }.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }
            d.needsHost?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(CaSymbols.update, contentDescription = null, size = 18.dp, tint = Ide.colors.warning)
                    Text(stringResource(Res.string.plugins_get_needs_host, it), style = MaterialTheme.typography.bodySmall, color = Ide.colors.warning)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val open = onOpenUrl
                when {
                    d.installedVersion != null && !d.updateAvailable -> FilledTonalButton({}, enabled = false) {
                        Symbol(CaSymbols.downloadDone, contentDescription = null, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.plugins_get_installed))
                    }
                    else -> Button(
                        onClick = { open?.invoke(d.downloadUrl) },
                        enabled = open != null && d.needsHost == null,
                    ) {
                        Symbol(if (d.updateAvailable) CaSymbols.update else CaSymbols.download, contentDescription = null, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (d.updateAvailable) stringResource(Res.string.plugins_get_update, d.version)
                            else stringResource(Res.string.plugins_get_download),
                        )
                    }
                }
                val page = d.pageUrl
                if (page != null && onOpenUrl != null) {
                    TextButton({ onOpenUrl(page) }) { Text(stringResource(Res.string.plugins_get_release_notes)) }
                }
            }
        }
    }
}

/** The consent gate, as a modal the user has to answer before the plugin is allowed to load. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PluginConsentDialog(plugin: UiPluginInfo, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    // Dismissing without answering (Back, a tap outside) leaves the plugin exactly as it was: still not
    // running, still asked about next time. Only the two explicit buttons record a decision; a dismissal used
    // to count as "Don't allow", which turned the plugin off.
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.padding(vertical = 24.dp)) {
        PluginConsent(plugin, onRefuse = { onAnswer(false) }, onAccept = { onAnswer(true) })
    }
}

@Composable
private fun RequiredPill() {
    Text(
        stringResource(Res.string.plugins_required),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "3 days ago", from the same strings the home screen's project ages use. */
@Composable
private fun relativeAge(then: Long, now: Long): String? {
    if (then <= 0L) return null
    val diff = (now - then).coerceAtLeast(0L)
    val minutes = (diff / 60_000L).toInt()
    val hours = (diff / 3_600_000L).toInt()
    val days = (diff / 86_400_000L).toInt()
    val weeks = (diff / 604_800_000L).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.project_age_just_now)
        hours < 1 -> pluralStringResource(Res.plurals.project_age_minutes, minutes, minutes)
        days < 1 -> pluralStringResource(Res.plurals.project_age_hours, hours, hours)
        weeks < 1 -> pluralStringResource(Res.plurals.project_age_days, days, days)
        else -> pluralStringResource(Res.plurals.project_age_weeks, weeks, weeks)
    }
}

package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiBuildFeature
import dev.ide.ui.backend.UiBuildFeatures
import dev.ide.ui.backend.UiCompilerPlugin
import dev.ide.ui.backend.UiCompilerPlugins
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.backend.UiSigningAssignment
import dev.ide.ui.backend.UiSigningAssignments
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiMissingProguardFile
import dev.ide.ui.backend.UiPackagingOptions
import dev.ide.ui.backend.UiPackagingRules
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiRunConfig
import dev.ide.ui.backend.UiSourceSetInfo
import dev.ide.ui.components.AddSourceRootDialog
import dev.ide.ui.components.AddSourceRootRequest
import dev.ide.ui.components.Chip
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.add
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.create
import dev.ide.ui.generated.resources.modcfg_discard
import dev.ide.ui.generated.resources.modcfg_unsaved_changes
import dev.ide.ui.generated.resources.remove
import dev.ide.ui.generated.resources.modcfg_add_placeholder
import dev.ide.ui.generated.resources.modcfg_add_row
import dev.ide.ui.generated.resources.modcfg_add_source_root
import dev.ide.ui.generated.resources.modcfg_auto_detect
import dev.ide.ui.generated.resources.modcfg_auto_detected
import dev.ide.ui.generated.resources.modcfg_consumer_suffix
import dev.ide.ui.generated.resources.modcfg_build_features_android_only
import dev.ide.ui.generated.resources.modcfg_build_features_intro
import dev.ide.ui.generated.resources.modcfg_compiler_plugin_applied
import dev.ide.ui.generated.resources.modcfg_compiler_plugins_android_only
import dev.ide.ui.generated.resources.modcfg_compiler_plugins_intro
import dev.ide.ui.generated.resources.modcfg_couldnt_create
import dev.ide.ui.generated.resources.modcfg_couldnt_load_config
import dev.ide.ui.generated.resources.modcfg_created
import dev.ide.ui.generated.resources.modcfg_debug_default
import dev.ide.ui.generated.resources.modcfg_java_version
import dev.ide.ui.generated.resources.modcfg_keystores_empty
import dev.ide.ui.generated.resources.modcfg_manage_keystores
import dev.ide.ui.generated.resources.modcfg_missing_keep_rule_files
import dev.ide.ui.generated.resources.modcfg_missing_keep_rule_files_content
import dev.ide.ui.generated.resources.modcfg_module_name_placeholder
import dev.ide.ui.generated.resources.modcfg_name
import dev.ide.ui.generated.resources.modcfg_new_badge
import dev.ide.ui.generated.resources.modcfg_new_module
import dev.ide.ui.generated.resources.modcfg_no_module_types
import dev.ide.ui.generated.resources.modcfg_no_modules
import dev.ide.ui.generated.resources.modcfg_no_roots
import dev.ide.ui.generated.resources.modcfg_no_rows_yet
import dev.ide.ui.generated.resources.modcfg_no_source_sets
import dev.ide.ui.generated.resources.modcfg_output
import dev.ide.ui.generated.resources.modcfg_platform_sdk
import dev.ide.ui.generated.resources.modcfg_platform_sdk_auto
import dev.ide.ui.generated.resources.modcfg_remove
import dev.ide.ui.generated.resources.modcfg_remove_named
import dev.ide.ui.generated.resources.modcfg_remove_module
import dev.ide.ui.generated.resources.modcfg_remove_module_content
import dev.ide.ui.generated.resources.modcfg_removed
import dev.ide.ui.generated.resources.modcfg_run
import dev.ide.ui.generated.resources.modcfg_run_config_hint
import dev.ide.ui.generated.resources.modcfg_run_main_class_placeholder
import dev.ide.ui.generated.resources.modcfg_save
import dev.ide.ui.generated.resources.modcfg_save_changes
import dev.ide.ui.generated.resources.modcfg_section_general
import dev.ide.ui.generated.resources.modcfg_section_source_sets
import dev.ide.ui.generated.resources.modcfg_signing_android_only
import dev.ide.ui.generated.resources.modcfg_signing_intro
import dev.ide.ui.generated.resources.modcfg_packaging_android_only
import dev.ide.ui.generated.resources.modcfg_packaging_intro
import dev.ide.ui.generated.resources.modcfg_packaging_glob_hint
import dev.ide.ui.generated.resources.modcfg_packaging_resources
import dev.ide.ui.generated.resources.modcfg_packaging_jni
import dev.ide.ui.generated.resources.modcfg_packaging_jni_note
import dev.ide.ui.generated.resources.modcfg_packaging_excludes
import dev.ide.ui.generated.resources.modcfg_packaging_excludes_desc
import dev.ide.ui.generated.resources.modcfg_packaging_pick_first
import dev.ide.ui.generated.resources.modcfg_packaging_pick_first_desc
import dev.ide.ui.generated.resources.modcfg_packaging_merge
import dev.ide.ui.generated.resources.modcfg_packaging_merge_desc
import dev.ide.ui.generated.resources.modcfg_packaging_default_excludes
import dev.ide.ui.generated.resources.modcfg_packaging_default_merges
import dev.ide.ui.generated.resources.modcfg_tab_build_features
import dev.ide.ui.generated.resources.modcfg_tab_compiler_plugins
import dev.ide.ui.generated.resources.modcfg_tab_dependencies
import dev.ide.ui.generated.resources.modcfg_tab_packaging
import dev.ide.ui.generated.resources.modcfg_tab_settings
import dev.ide.ui.generated.resources.modcfg_tab_signing
import dev.ide.ui.generated.resources.modcfg_title_modules
import dev.ide.ui.generated.resources.modcfg_new_module_action
import dev.ide.ui.generated.resources.modcfg_type
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The tabs of a module's detail view. */
enum class ModulesTab(val label: StringResource) {
    Settings(Res.string.modcfg_tab_settings),
    BuildFeatures(Res.string.modcfg_tab_build_features),
    CompilerPlugins(Res.string.modcfg_tab_compiler_plugins),
    Packaging(Res.string.modcfg_tab_packaging),
    Signing(Res.string.modcfg_tab_signing),
    Dependencies(Res.string.modcfg_tab_dependencies),
}

/**
 * The **Modules** screen. Lists the project's modules first (add / remove); selecting one opens its detail
 * view with two tabs — **Settings** (Java version, source sets, facet config) and **Dependencies** (the
 * per-module dependency manager: libraries, BOMs, module-on-module deps, custom repositories). Facet panels
 * are generic — fields are derived from the facet codec, so a new facet appears without bespoke UI. Talks
 * only to [IdeBackend].
 */
@Composable
fun ModuleConfigScreen(
    backend: IdeBackend,
    initialModule: String?,
    initialTab: ModulesTab = ModulesTab.Settings,
    onBack: () -> Unit,
    onOpenKeystoreManager: () -> Unit = {},
    codeFont: FontFamily = FontFamily.Monospace,
    fileActions: FileActions = FileActions.None,
) {
    var selected by remember { mutableStateOf(initialModule) }
    val module = selected
    if (module == null) {
        ModulesList(backend, codeFont, onOpen = { selected = it }, onBack = onBack)
    } else {
        ModuleDetail(backend, module, initialTab, codeFont, fileActions, onOpenKeystoreManager, onBack = { selected = null })
    }
}

// ---- shared header ------------------------------------------------------------------------------

@Composable
private fun ModulesHeader(title: String, icon: ImageVector, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    GlassSurface(Modifier.fillMaxWidth(), GlassMaterial.Regular) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, stringResource(Res.string.back), onBack)
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            action?.invoke()
        }
    }
}

// ---- modules list -------------------------------------------------------------------------------

@Composable
private fun ModulesList(backend: IdeBackend, codeFont: FontFamily, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val state = rememberModulesListState(backend)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            ModulesHeader(stringResource(Res.string.modcfg_title_modules), CaIcons.layers, onBack) {
                IconButtonCa(CaIcons.plus, stringResource(Res.string.modcfg_new_module_action), onClick = state::openNewModule, active = true)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            if (state.modules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.modcfg_no_modules), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.modules, key = { it.name }) { m ->
                        ModuleListItem(m, onOpen = { onOpen(m.name) }, onRemove = { state.askRemove(m.name) })
                    }
                }
            }
        }
        NewModuleDialog(
            visible = state.newModuleOpen,
            backend = backend,
            codeFont = codeFont,
            onDismiss = state::closeNewModule,
            onCreate = state::createModule,
        )
        ConfirmModuleRemove(
            moduleName = state.pendingRemove,
            onDismiss = state::cancelRemove,
            onConfirm = state::confirmRemove,
        )
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ModuleListItem(module: UiModuleRef, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CaIcons.layers, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(module.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(module.typeDisplay, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButtonCa(CaIcons.close, stringResource(Res.string.modcfg_remove_named, module.name), onClick = onRemove, boxSize = 30, iconSize = 16, tint = MaterialTheme.colorScheme.outline)
        Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

// ---- module detail (Settings | Dependencies) ---------------------------------------------------

@Composable
private fun ModuleDetail(backend: IdeBackend, moduleName: String, initialTab: ModulesTab, codeFont: FontFamily, fileActions: FileActions, onOpenKeystoreManager: () -> Unit, onBack: () -> Unit) {
    var tab by remember(moduleName) { mutableStateOf(initialTab) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            ModulesHeader(moduleName, CaIcons.gear, onBack)
            ModuleTabRow(tab) { tab = it }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            when (tab) {
                ModulesTab.Settings -> ModuleSettingsTab(backend, moduleName, codeFont, Modifier.weight(1f).fillMaxWidth())
                ModulesTab.BuildFeatures -> BuildFeaturesPane(backend, moduleName, Modifier.weight(1f).fillMaxWidth())
                ModulesTab.CompilerPlugins -> CompilerPluginsPane(backend, moduleName, Modifier.weight(1f).fillMaxWidth())
                ModulesTab.Packaging -> PackagingPane(backend, moduleName, codeFont, Modifier.weight(1f).fillMaxWidth())
                ModulesTab.Signing -> SigningPane(backend, moduleName, onOpenKeystoreManager, Modifier.weight(1f).fillMaxWidth())
                ModulesTab.Dependencies -> DependenciesPane(backend, moduleName, codeFont, fileActions, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ModuleTabRow(tab: ModulesTab, onSelect: (ModulesTab) -> Unit) {
    // A real M3 tab strip: scrollable so it never clips on a narrow phone (Settings · Build Features ·
    // Compiler plugins · Packaging · Signing · Dependencies), with the selected-tab indicator M3 draws for it.
    PrimaryScrollableTabRow(
        selectedTabIndex = ModulesTab.entries.indexOf(tab),
        edgePadding = 12.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ModulesTab.entries.forEach { t ->
            Tab(
                selected = t == tab,
                onClick = { onSelect(t) },
                // BOTH colours must be given: M3's `Tab` defaults `unselectedContentColor` to
                // `selectedContentColor`, so leaving them out paints every tab the row's content colour and
                // the selection reads only from the indicator — which is off-screen whenever the strip is
                // scrolled away from the active tab.
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(stringResource(t.label), style = MaterialTheme.typography.titleSmall, maxLines = 1) },
            )
        }
    }
}

// ---- Build Features (AGP buildFeatures: viewBinding / compose) -----------------------------------

@Composable
private fun BuildFeaturesPane(backend: IdeBackend, moduleName: String, modifier: Modifier) {
    val state = rememberBuildFeaturesState(backend, moduleName)

    Box(modifier) {
        val f = state.model
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            f == null -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(
                    stringResource(Res.string.modcfg_build_features_android_only),
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("intro") {
                    Text(
                        stringResource(Res.string.modcfg_build_features_intro),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(f.features, key = { it.id }) { feature ->
                    BuildFeatureRow(
                        feature = feature,
                        working = state.busyId == feature.id,
                        switchEnabled = state.idle,
                    ) { enabled -> state.setEnabled(feature.id, enabled) }
                }
            }
        }
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BuildFeatureRow(feature: UiBuildFeature, working: Boolean, switchEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(feature.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Text(feature.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (working) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                else Switch(checked = feature.enabled, onCheckedChange = { if (switchEnabled) onToggle(it) }, enabled = switchEnabled)
            }
            feature.note?.let {
                Text(it, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---- Compiler plugins (Kotlin compiler plugins: Compose, Serialization, Parcelize) ---------------

@Composable
private fun CompilerPluginsPane(backend: IdeBackend, moduleName: String, modifier: Modifier) {
    val state = rememberCompilerPluginsState(backend, moduleName)

    Box(modifier) {
        val p = state.model
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            p == null -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(
                    stringResource(Res.string.modcfg_compiler_plugins_android_only),
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("intro") {
                    Text(
                        stringResource(Res.string.modcfg_compiler_plugins_intro),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(p.plugins, key = { it.id }) { plugin ->
                    CompilerPluginRow(
                        plugin = plugin,
                        working = state.busyId == plugin.id,
                        switchEnabled = state.idle,
                    ) { enabled -> state.setEnabled(plugin.id, enabled) }
                }
            }
        }
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun CompilerPluginRow(plugin: UiCompilerPlugin, working: Boolean, switchEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(plugin.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(plugin.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            if (working) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            else Switch(checked = plugin.enabled, onCheckedChange = { if (switchEnabled) onToggle(it) }, enabled = switchEnabled)
        }
        // "Active on the classpath" badge — the real build behavior, which can differ from the toggle when the
        // runtime came in transitively (a dependency pulling it in applies the plugin even if not toggled here).
        if (plugin.applied) {
            Text(
                "● " + stringResource(Res.string.modcfg_compiler_plugin_applied),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
            )
        }
        plugin.note?.let {
            Text(it, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        }
        }
    }
}

// ---- Packaging (Java-resource + native-lib merge rules) ------------------------------------------

@Composable
private fun PackagingPane(backend: IdeBackend, moduleName: String, codeFont: FontFamily, modifier: Modifier) {
    val state = rememberPackagingPaneState(backend, moduleName)

    Box(modifier) {
        val o = state.options
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            o == null -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(stringResource(Res.string.modcfg_packaging_android_only), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.modcfg_packaging_intro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(Res.string.modcfg_packaging_glob_hint), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)

                SectionCard(stringResource(Res.string.modcfg_packaging_resources)) {
                    PackagingRuleList(stringResource(Res.string.modcfg_packaging_excludes), stringResource(Res.string.modcfg_packaging_excludes_desc), state.resourceExcludes, codeFont)
                    PackagingRuleList(stringResource(Res.string.modcfg_packaging_pick_first), stringResource(Res.string.modcfg_packaging_pick_first_desc), state.resourcePickFirsts, codeFont)
                    PackagingRuleList(stringResource(Res.string.modcfg_packaging_merge), stringResource(Res.string.modcfg_packaging_merge_desc), state.resourceMerges, codeFont)
                    DefaultsDisclosure(stringResource(Res.string.modcfg_packaging_default_excludes), o.defaultResourceExcludes, codeFont)
                    DefaultsDisclosure(stringResource(Res.string.modcfg_packaging_default_merges), o.defaultResourceMerges, codeFont)
                }

                SectionCard(stringResource(Res.string.modcfg_packaging_jni)) {
                    Text(stringResource(Res.string.modcfg_packaging_jni_note), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                    PackagingRuleList(stringResource(Res.string.modcfg_packaging_excludes), stringResource(Res.string.modcfg_packaging_excludes_desc), state.jniExcludes, codeFont)
                    PackagingRuleList(stringResource(Res.string.modcfg_packaging_pick_first), stringResource(Res.string.modcfg_packaging_pick_first_desc), state.jniPickFirsts, codeFont)
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    PrimaryButton(stringResource(Res.string.modcfg_save), icon = CaIcons.check, onClick = state::save)
                }
            }
        }
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

/** One labelled + described glob-pattern list within a packaging section. */
@Composable
private fun PackagingRuleList(label: String, description: String, values: SnapshotStateList<String>, codeFont: FontFamily) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(description, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        StringListEditor(values, codeFont)
    }
}

/** A collapsible read-only list of the AGP defaults that are always applied on top of the module's rules. */
@Composable
private fun DefaultsDisclosure(label: String, patterns: List<String>, codeFont: FontFamily) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (open) CaIcons.chevronDown else CaIcons.chevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            Text("$label (${patterns.size})", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
        if (open) patterns.forEach { p ->
            Text(p, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont), modifier = Modifier.padding(start = 20.dp))
        }
    }
}

// ---- Signing (assign a keystore to each build type) ----------------------------------------------

@Composable
private fun SigningPane(backend: IdeBackend, moduleName: String, onOpenKeystoreManager: () -> Unit, modifier: Modifier) {
    val state = rememberSigningPaneState(backend, moduleName)

    Box(modifier) {
        val d = state.assignments
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            d == null -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(stringResource(Res.string.modcfg_signing_android_only), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(Res.string.modcfg_signing_intro),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.control))
                                .clickable(remember { MutableInteractionSource() }, null, onClick = onOpenKeystoreManager)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(CaIcons.key, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(Res.string.modcfg_manage_keystores), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                items(d.assignments, key = { it.buildType }) { a ->
                    BuildTypeSigningRow(a, d.keystores, state.busy) { keystoreId -> state.assign(a.buildType, keystoreId) }
                }
                if (d.keystores.isEmpty()) item("empty") {
                    Text(stringResource(Res.string.modcfg_keystores_empty),
                        color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BuildTypeSigningRow(assignment: UiSigningAssignment, keystores: List<UiKeystore>, busy: Boolean, onAssign: (String?) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(assignment.buildType, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        // The choices: the default debug keystore (null) plus every registered keystore.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SigningPill(stringResource(Res.string.modcfg_debug_default), selected = assignment.keystoreId == null, enabled = !busy) { onAssign(null) }
            keystores.forEach { ks ->
                SigningPill(ks.name, selected = assignment.keystoreId == ks.id, enabled = !busy) { onAssign(ks.id) }
            }
        }
    }
}

@Composable
private fun SigningPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
        // The chip's own leading check is the M3 selected affordance, so the hand-drawn one goes.
        leadingIcon = if (selected) ({ Icon(CaIcons.check, null, Modifier.size(16.dp)) }) else null,
        shape = RoundedCornerShape(Ca.radius.pill),
    )
}

@Composable
private fun ModuleSettingsTab(backend: IdeBackend, moduleName: String, codeFont: FontFamily, modifier: Modifier) {
    val state = rememberModuleSettingsState(backend, moduleName)

    Box(modifier) {
        ConfigBody(
            state.config, state.loading, codeFont, backend.project.rootPath, state.missingProguard, Modifier.fillMaxSize(),
            onAddSourceRoot = state::openAddSourceRoot,
            onRemoveSourceRoot = state::removeSourceRoot,
            onCreateProguard = state::createProguardFile,
            onSave = state::applyEdit,
        )
        AddSourceRootDialog(
            request = if (state.addRootOpen) AddSourceRootRequest(moduleName, state.sourceSets) else null,
            onDismiss = state::closeAddSourceRoot,
            onAdd = state::addSourceRoot,
        )
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

// ---- new-module dialog + remove confirm ---------------------------------------------------------

@Composable
private fun ConfirmModuleRemove(moduleName: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var shown by remember { mutableStateOf<String?>(null) }
    if (moduleName != null) shown = moduleName
    DropdownOverlay(visible = moduleName != null, onDismiss = onDismiss, topPadding = 160.dp) {
        Column(
            Modifier.padding(horizontal = 12.dp).widthIn(max = 440.dp).fillMaxWidth()
                .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)).padding(20.dp),
        ) {
            Text(stringResource(Res.string.modcfg_remove_module), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.modcfg_remove_module_content, shown ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                DialogTextButton(stringResource(Res.string.cancel), destructive = false, onClick = onDismiss)
                DialogTextButton(stringResource(Res.string.modcfg_remove), destructive = true, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DialogTextButton(label: String, destructive: Boolean, onClick: () -> Unit) {
    val fill = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (destructive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.background(fill, RoundedCornerShape(Ca.radius.control)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) { Text(label, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun NewModuleDialog(
    visible: Boolean,
    backend: IdeBackend,
    codeFont: FontFamily,
    onDismiss: () -> Unit,
    onCreate: (name: String, typeId: String, languageLevel: String?, facetValues: Map<String, Map<String, Any?>>) -> Unit,
) {
    DropdownOverlay(visible = visible, onDismiss = onDismiss, topPadding = 56.dp) {
        val types = remember { backend.modules.availableModuleTypes() }
        Column(
            Modifier.padding(horizontal = 12.dp).widthIn(max = 560.dp).fillMaxWidth()
                .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)).padding(20.dp),
        ) {
            if (types.isEmpty()) {
                Text(stringResource(Res.string.modcfg_no_module_types), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
            } else {
                var name by remember { mutableStateOf("") }
                var typeIdx by remember { mutableStateOf(0) }
                val type = types[typeIdx.coerceIn(0, types.lastIndex)]
                var level by remember(type.id) { mutableStateOf(type.defaultLanguageLevel) }
                // Facet forms are rebuilt when the chosen type changes (each type has its own default facets).
                val forms = remember(type.id) { type.defaultFacets.map { it.toForm() } }

                Text(stringResource(Res.string.modcfg_new_module), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("name") {
                        LabeledField(stringResource(Res.string.modcfg_name)) {
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                                if (name.isEmpty()) Text(stringResource(Res.string.modcfg_module_name_placeholder), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
                                BasicTextField(name, { name = it }, singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = codeFont),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    item("type") {
                        LabeledField(stringResource(Res.string.modcfg_type)) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                types.forEachIndexed { i, t -> LevelChip(t.displayName, i == typeIdx) { typeIdx = i } }
                            }
                        }
                    }
                    item("level") {
                        LabeledField(stringResource(Res.string.modcfg_java_version)) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                type.languageLevels.forEach { lvl -> LevelChip(prettyLevel(lvl), lvl == level) { level = lvl } }
                            }
                        }
                    }
                    items(forms, key = { it.table }) { form -> FacetPanel(form, codeFont) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.weight(1f))
                    DialogTextButton(stringResource(Res.string.cancel), destructive = false, onClick = onDismiss)
                    PrimaryButton(stringResource(Res.string.create), icon = CaIcons.check, onClick = {
                        onCreate(name.trim(), type.id, level, forms.associate { it.table to it.toValues() })
                    })
                }
            }
        }
    }
}

// ---- body ---------------------------------------------------------------------------------------

@Composable
private fun ConfigBody(
    config: UiModuleConfig?,
    loading: Boolean,
    codeFont: FontFamily,
    projectRoot: String,
    missingProguard: List<UiMissingProguardFile>,
    modifier: Modifier,
    onAddSourceRoot: () -> Unit,
    onRemoveSourceRoot: (sourceSet: String, rootPath: String) -> Unit,
    onCreateProguard: (entry: String) -> Unit,
    onSave: (UiModuleConfigEdit) -> Unit,
) {
    Crossfade(targetState = loading, animationSpec = tween(Motion.BASE), label = "cfgBody", modifier = modifier) { isLoading ->
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            }
            config == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.modcfg_couldnt_load_config), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
            }
            else -> ConfigForm(config, codeFont, projectRoot, missingProguard, onAddSourceRoot, onRemoveSourceRoot, onCreateProguard, onSave)
        }
    }
}

@Composable
private fun ConfigForm(
    config: UiModuleConfig,
    codeFont: FontFamily,
    projectRoot: String,
    missingProguard: List<UiMissingProguardFile>,
    onAddSourceRoot: () -> Unit,
    onRemoveSourceRoot: (sourceSet: String, rootPath: String) -> Unit,
    onCreateProguard: (entry: String) -> Unit,
    onSave: (UiModuleConfigEdit) -> Unit,
) {
    // Editable state, rebuilt whenever a fresh config is loaded (e.g. after a save) — and whenever [revision]
    // moves, which is how Discard throws the edits away: everything below is re-derived from `config`.
    var revision by remember(config) { mutableStateOf(0) }
    var level by remember(config, revision) { mutableStateOf(config.languageLevel) }
    var sdk by remember(config, revision) { mutableStateOf(config.platformSdk) } // "" = follow the module-type default
    val forms = remember(config, revision) { config.facets.map { it.toForm() } }
    val mainClass = remember(config, revision) { mutableStateOf(config.runConfig?.mainClass ?: "") }
    // The facet values as loaded, snapshotted the moment the forms are built. Facet edits were previously
    // invisible to the dirty check, so changing a namespace / minSdk / versionCode left the form looking
    // untouched — which is fatal once the Save affordance only appears when something HAS changed.
    val baselineFacets = remember(config, revision) { forms.associate { it.table to it.toValues() } }
    val facetValues = forms.associate { it.table to it.toValues() }
    val dirty = level != config.languageLevel || sdk != config.platformSdk ||
        (config.runConfig != null && mainClass.value.trim() != config.runConfig!!.mainClass) ||
        facetValues != baselineFacets

    fun edit() = UiModuleConfigEdit(
        languageLevel = level,
        facetValues = facetValues,
        mainClass = if (config.runConfig != null) mainClass.value.trim() else null,
        platformSdk = sdk,
    )

    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- General ----
        item("general") {
            SectionCard(stringResource(Res.string.modcfg_section_general)) {
                MetaRow(stringResource(Res.string.modcfg_type)) { Chip(config.typeDisplay, fill = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.primary) }
                MetaRow(stringResource(Res.string.modcfg_output)) {
                    Text(shortenPath(config.outputDir, projectRoot), color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text(stringResource(Res.string.modcfg_java_version), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    config.languageLevels.forEach { lvl -> LevelChip(prettyLevel(lvl), lvl == level) { level = lvl } }
                }
                // Platform SDK: the boot classpath the module compiles/completes against. "Auto" follows the
                // module type (Java → core-Java, Android → the Android SDK); pinning it is how a console module
                // is kept off android.jar, or a module is targeted at a specific installed platform.
                if (config.availableSdks.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.modcfg_platform_sdk), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LevelChip(stringResource(Res.string.modcfg_platform_sdk_auto, config.resolvedSdk), sdk == "") { sdk = "" }
                        config.availableSdks.forEach { opt -> LevelChip(opt.label, sdk == opt.name) { sdk = opt.name } }
                    }
                }
            }
        }

        // ---- Run configuration (console Java/Kotlin modules) ----
        config.runConfig?.let { rc ->
            item("run") { RunConfigCard(rc, mainClass, codeFont) }
        }

        // ---- Source sets (add / remove typed roots) ----
        item("sourceSets") {
            SectionCard(stringResource(Res.string.modcfg_section_source_sets), action = {
                IconButtonCa(CaIcons.plus, stringResource(Res.string.modcfg_add_source_root), onClick = onAddSourceRoot, boxSize = 26, iconSize = 16, active = true)
            }) {
                if (config.sourceSets.isEmpty()) {
                    Text(stringResource(Res.string.modcfg_no_source_sets), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                }
                config.sourceSets.forEach { ss -> SourceSetRow(ss, codeFont, projectRoot) { root -> onRemoveSourceRoot(ss.name, root) } }
            }
        }

        // ---- Minify: referenced-but-missing keep-rule files ----
        if (missingProguard.isNotEmpty()) {
            item("proguardMissing") { MissingProguardCard(missingProguard, codeFont, onCreateProguard) }
        }

        // ---- Facet panels (generic) ----
        items(forms, key = { it.table }) { form -> FacetPanel(form, codeFont) }

    }
        // Pinned, not the last row of the form: the Save button used to sit below every facet panel, so on an
        // Android module you had to scroll past a screenful of fields to discover that your edits needed
        // saving at all. It slides in the moment anything differs from the loaded config and stays put.
        AnimatedVisibility(
            visible = dirty,
            enter = slideInVertically(tween(Motion.FAST)) { it } + fadeIn(tween(Motion.FAST)),
            exit = slideOutVertically(tween(Motion.FAST)) { it } + fadeOut(tween(Motion.FAST)),
        ) {
            SaveBar(onDiscard = { revision++ }, onSave = { onSave(edit()) })
        }
    }
}

/** The pinned unsaved-changes bar: what changed, a way back, and the commit. */
@Composable
private fun SaveBar(onDiscard: () -> Unit, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CaIcons.info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(Res.string.modcfg_unsaved_changes),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDiscard) { Text(stringResource(Res.string.modcfg_discard)) }
            Button(onClick = onSave) { Text(stringResource(Res.string.modcfg_save)) }
        }
    }
}

/**
 * The console Run configuration for a Java/Kotlin module: which `main` class the Run button launches. A blank
 * field means auto-detect (the placeholder shows what that resolves to); the entry points found in the module's
 * sources are offered as one-tap chips, plus an "Auto-detect" chip that clears the override.
 */
@Composable
private fun RunConfigCard(rc: UiRunConfig, mainClass: MutableState<String>, codeFont: FontFamily) {
    SectionCard(stringResource(Res.string.modcfg_run)) {
        Text(
            stringResource(Res.string.modcfg_run_config_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (mainClass.value.isEmpty()) {
                Text(
                    rc.autoDetected?.let { stringResource(Res.string.modcfg_auto_detected, it) } ?: stringResource(Res.string.modcfg_run_main_class_placeholder),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = mainClass.value,
                onValueChange = { mainClass.value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = codeFont),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (rc.detectedMainClasses.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LevelChip(stringResource(Res.string.modcfg_auto_detect), selected = mainClass.value.isBlank()) { mainClass.value = "" }
                rc.detectedMainClasses.forEach { fqn ->
                    LevelChip(fqn, selected = mainClass.value.trim() == fqn) { mainClass.value = fqn }
                }
            }
        }
    }
}

/**
 * Warns that a build type references keep-rule files (`proguardFiles` / `consumerProguardFiles`) that don't
 * exist on disk — R8 silently skips those, so a `minifyEnabled` build would shrink without them. Each row
 * offers to create the file with a starter template so the reference resolves.
 */
@Composable
private fun MissingProguardCard(
    missing: List<UiMissingProguardFile>,
    codeFont: FontFamily,
    onCreate: (entry: String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ide.colors.warning.copy(alpha = 0.5f), RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.warning, null, Modifier.size(18.dp), tint = Ide.colors.warning)
            Text(stringResource(Res.string.modcfg_missing_keep_rule_files), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(
            stringResource(Res.string.modcfg_missing_keep_rule_files_content),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
        missing.forEach { mf ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mf.entry, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (mf.consumer) stringResource(Res.string.modcfg_consumer_suffix, mf.buildType) else mf.buildType,
                        color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                }
                CreateRuleButton { onCreate(mf.entry) }
            }
        }
    }
}

@Composable
private fun CreateRuleButton(onClick: () -> Unit) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.control))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(CaIcons.plus, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(Res.string.create), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionCard(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // titleSmall in the section colour, not an uppercased micro-label: M3 section headers are
                // read as headings, and SMALL CAPS at labelSmall is the hardest thing on the screen to scan.
                Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

/** A compact label · value row used in the General card (label fixed-width so values line up). */
@Composable
private fun MetaRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun SourceSetRow(ss: UiSourceSetInfo, codeFont: FontFamily, projectRoot: String, onRemoveRoot: (rootPath: String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ss.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Chip(ss.scope.lowercase(), fill = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.primary)
        }
        if (ss.roots.isEmpty()) Text(stringResource(Res.string.modcfg_no_roots), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        ss.roots.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(shortenPath(r, projectRoot), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButtonCa(CaIcons.close, stringResource(Res.string.modcfg_remove_named, shortenPath(r, projectRoot)), onClick = { onRemoveRoot(r) }, boxSize = 22, iconSize = 12)
            }
        }
    }
}

/** A path shown relative to the project root so long absolute paths don't dominate the row. */
private fun shortenPath(full: String, projectRoot: String): String {
    val f = full.replace('\\', '/')
    val root = projectRoot.replace('\\', '/').trimEnd('/')
    return when {
        root.isEmpty() -> full
        f == root -> "."
        f.startsWith("$root/") -> f.removePrefix("$root/")
        else -> full
    }
}

// ---- facet panels (collapsible) -----------------------------------------------------------------

@Composable
private fun FacetPanel(form: FacetForm, codeFont: FontFamily) {
    var open by remember(form) { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CaIcons.box, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(form.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) CaIcons.caretDown else CaIcons.caretRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(Motion.FAST)) + fadeIn(), exit = shrinkVertically(tween(Motion.FAST)) + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                form.fields.forEach { FieldEditor(it, codeFont) }
            }
        }
    }
}

@Composable
private fun FieldEditor(field: FieldState, codeFont: FontFamily) {
    when (field) {
        is FieldState.TextF -> LabeledField(field.label) {
            BoxedTextField(field.value, codeFont)
        }
        is FieldState.NumberF -> LabeledField(field.label) {
            BoxedTextField(field.value, codeFont, numeric = true)
        }
        is FieldState.BoolF -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ToggleSwitch(field.value.value) { field.value.value = it }
        }
        is FieldState.ListF -> LabeledField(field.label) { StringListEditor(field.values, codeFont) }
        is FieldState.TableF -> TableListEditor(field, codeFont)
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun BoxedTextField(state: MutableState<String>, codeFont: FontFamily, numeric: Boolean = false) {
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = if (numeric) it.filter { c -> c.isDigit() } else it },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleSwitch(on: Boolean, onToggle: (Boolean) -> Unit) {
    Switch(checked = on, onCheckedChange = onToggle)
}

@Composable
private fun StringListEditor(values: SnapshotStateList<String>, codeFont: FontFamily) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { i, v ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.sm)).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(v, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont), modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButtonCa(CaIcons.close, stringResource(Res.string.remove), { values.removeAt(i) }, boxSize = 24, iconSize = 14, tint = MaterialTheme.colorScheme.outline)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.sm))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm)).padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                if (draft.isEmpty()) Text(stringResource(Res.string.modcfg_add_placeholder), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                BasicTextField(draft, { draft = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = codeFont),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
            }
            IconButtonCa(CaIcons.plus, stringResource(Res.string.add), { if (draft.isNotBlank()) { values.add(draft.trim()); draft = "" } }, boxSize = 30, iconSize = 16, active = true)
        }
    }
}

@Composable
private fun TableListEditor(field: FieldState.TableF, codeFont: FontFamily) {
    // Highlight the just-added row briefly so it's obvious a new item appeared (it animates in at the bottom).
    var justAdded by remember { mutableStateOf(-1) }
    LaunchedEffect(justAdded) { if (justAdded >= 0) { delay(1600); justAdded = -1 } }
    val singular = field.label.lowercase().removeSuffix("s")

    Column(Modifier.fillMaxWidth().animateContentSize(tween(Motion.BASE, easing = Motion.spring)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(field.label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        if (field.rows.isEmpty()) Text(stringResource(Res.string.modcfg_no_rows_yet, "${singular}s"), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        field.rows.forEachIndexed { i, row ->
            val isNew = i == justAdded
            val borderColor by animateColorAsState(if (isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, tween(Motion.SLOW), label = "newRowBorder")
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
                    .border(if (isNew) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(Ca.radius.md)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rowTitle(row, i), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (isNew) Chip(stringResource(Res.string.modcfg_new_badge), fill = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.primary)
                    IconButtonCa(CaIcons.close, stringResource(Res.string.remove), { val at = i; field.rows.removeAt(at); if (justAdded == at) justAdded = -1 }, boxSize = 24, iconSize = 14, tint = MaterialTheme.colorScheme.outline)
                }
                row.forEach { FieldEditor(it, codeFont) }
            }
        }
        AddRowButton(stringResource(Res.string.modcfg_add_row, singular)) { field.rows.add(cloneTemplateRow(field)); justAdded = field.rows.lastIndex }
    }
}

/** A full-width, clearly-labelled add button for the inline-table editor (build types / product flavors). */
@Composable
private fun AddRowButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.md))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CaIcons.plus, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
        shape = RoundedCornerShape(Ca.radius.pill),
    )
}

/** The user-facing text of a pane's reported outcome. */
@Composable
private fun ConfigToast.message(): String = when (this) {
    is ConfigToast.Message -> text
    is ConfigToast.Removed -> stringResource(Res.string.modcfg_removed, name)
    is ConfigToast.Created -> stringResource(Res.string.modcfg_created, name)
    is ConfigToast.CreateFailed -> stringResource(Res.string.modcfg_couldnt_create, name)
}

@Composable
private fun ConfigToastHost(toast: ConfigToast?, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(bottom = 28.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically(tween(Motion.BASE, easing = Motion.spring)) { it } + fadeIn(tween(Motion.BASE)),
            exit = slideOutVertically(tween(Motion.FAST)) { it } + fadeOut(tween(Motion.FAST)),
        ) {
            val t = toast
            Row(
                Modifier.background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.pill))
                    .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (t?.error == true) CaIcons.warning else CaIcons.check, null, Modifier.size(16.dp), tint = if (t?.error == true) MaterialTheme.colorScheme.error else Ide.colors.run)
                Text(t?.message() ?: "", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---- editable form model (mirrors UiFacetConfig with Compose state) -----------------------------

private class FacetForm(val table: String, val title: String, val fields: List<FieldState>) {
    fun toValues(): Map<String, Any?> = fields.associate { it.key to it.serialize() }
}

private sealed class FieldState {
    abstract val key: String
    abstract val label: String

    class TextF(override val key: String, override val label: String, val value: MutableState<String>) : FieldState()
    class NumberF(override val key: String, override val label: String, val value: MutableState<String>) : FieldState()
    class BoolF(override val key: String, override val label: String, val value: MutableState<Boolean>) : FieldState()
    class ListF(override val key: String, override val label: String, val values: SnapshotStateList<String>) : FieldState()
    class TableF(override val key: String, override val label: String, val rows: SnapshotStateList<SnapshotStateList<FieldState>>) : FieldState()

    fun serialize(): Any? = when (this) {
        is TextF -> value.value
        is NumberF -> value.value.trim().toLongOrNull() ?: 0L
        is BoolF -> value.value
        is ListF -> values.toList()
        is TableF -> rows.map { row -> row.associate { it.key to it.serialize() } }
    }
}

private fun UiFacetConfig.toForm(): FacetForm = FacetForm(table, title, fields.map { it.toFieldState() })

private fun UiConfigField.toFieldState(): FieldState = when (this) {
    is UiConfigField.Text -> FieldState.TextF(key, label, mutableStateOf(value))
    is UiConfigField.Number -> FieldState.NumberF(key, label, mutableStateOf(value.toString()))
    is UiConfigField.Bool -> FieldState.BoolF(key, label, mutableStateOf(value))
    is UiConfigField.StringList -> FieldState.ListF(key, label, mutableStateListOf<String>().also { it.addAll(values) })
    is UiConfigField.TableList -> FieldState.TableF(
        key, label,
        mutableStateListOf<SnapshotStateList<FieldState>>().also { outer ->
            rows.forEach { row -> outer.add(mutableStateListOf<FieldState>().also { it.addAll(row.map { f -> f.toFieldState() }) }) }
        },
    )
}

/** A fresh row for a [FieldState.TableF] add, cloning the first row's field shape with blank/default values. */
private fun cloneTemplateRow(field: FieldState.TableF): SnapshotStateList<FieldState> {
    val template = field.rows.firstOrNull()
    val row = mutableStateListOf<FieldState>()
    if (template != null) template.forEach { row.add(it.blankCopy()) }
    return row
}

private fun FieldState.blankCopy(): FieldState = when (this) {
    is FieldState.TextF -> FieldState.TextF(key, label, mutableStateOf(if (key == "name") "new" else ""))
    is FieldState.NumberF -> FieldState.NumberF(key, label, mutableStateOf("0"))
    is FieldState.BoolF -> FieldState.BoolF(key, label, mutableStateOf(false))
    is FieldState.ListF -> FieldState.ListF(key, label, mutableStateListOf())
    is FieldState.TableF -> FieldState.TableF(key, label, mutableStateListOf())
}

/** The displayed title for an inline-table row: its `name` field's value, else a positional fallback. */
private fun rowTitle(row: SnapshotStateList<FieldState>, index: Int): String {
    val name = row.firstOrNull { it.key == "name" } as? FieldState.TextF
    return name?.value?.value?.takeIf { it.isNotBlank() } ?: "#${index + 1}"
}

private fun prettyLevel(enumName: String): String = enumName.replace("JAVA_", "Java ")

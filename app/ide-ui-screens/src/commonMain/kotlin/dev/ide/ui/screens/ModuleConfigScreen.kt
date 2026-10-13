@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompilerPlugin
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.backend.UiMissingProguardFile
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiRunConfig
import dev.ide.ui.backend.UiSigningAssignment
import dev.ide.ui.backend.UiSourceSetInfo
import dev.ide.ui.components.AddSourceRootDialog
import dev.ide.ui.components.AddSourceRootRequest
import dev.ide.ui.components.Chip
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.add
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.create
import dev.ide.ui.generated.resources.more
import dev.ide.ui.generated.resources.remove
import dev.ide.ui.generated.resources.modcfg_empty_body
import dev.ide.ui.generated.resources.modcfg_empty_title
import dev.ide.ui.generated.resources.modcfg_discard
import dev.ide.ui.generated.resources.modcfg_unsaved_changes
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
import dev.ide.ui.generated.resources.modcfg_platform_missing
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
import dev.ide.ui.generated.resources.modcfg_section_general
import dev.ide.ui.generated.resources.modcfg_section_source_sets
import dev.ide.ui.generated.resources.modcfg_signing_android_only
import dev.ide.ui.generated.resources.modcfg_signing_intro
import dev.ide.ui.generated.resources.modcfg_packaging_android_only
import dev.ide.ui.generated.resources.modcfg_packaging_intro
import dev.ide.ui.generated.resources.modcfg_packaging_glob_hint
import dev.ide.ui.generated.resources.modcfg_packaging_compression
import dev.ide.ui.generated.resources.modcfg_packaging_no_compress
import dev.ide.ui.generated.resources.modcfg_packaging_no_compress_desc
import dev.ide.ui.generated.resources.modcfg_packaging_default_no_compress
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
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsKeyed
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Motion
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The tabs of a module's detail view. An [androidOnly] tab configures the Android build, so it is left out for a
 * module without an Android facet rather than shown as an empty page.
 */
enum class ModulesTab(val label: StringResource, val androidOnly: Boolean = false) {
    Settings(Res.string.modcfg_tab_settings),
    BuildFeatures(Res.string.modcfg_tab_build_features, androidOnly = true),
    CompilerPlugins(Res.string.modcfg_tab_compiler_plugins, androidOnly = true),
    Packaging(Res.string.modcfg_tab_packaging, androidOnly = true),
    Signing(Res.string.modcfg_tab_signing, androidOnly = true),
    Dependencies(Res.string.modcfg_tab_dependencies),
}

/** At or above this width the module list and the selected module sit side by side. */
private val MODULES_TWO_PANE_BREAKPOINT = 840.dp

/** The module list's width in the two-pane layout. */
private val MODULES_LIST_PANE_WIDTH = 300.dp

/** The widest a module page's content grows; past it the page centres rather than stretching every row. */
private val MODULE_CONTENT_MAX_WIDTH = 880.dp

/** The content width at which facet fields and the General pickers go two to a row. */
private val MODULE_TWO_COLUMN_MIN = 600.dp

/** The side gutter that centres a [MODULE_CONTENT_MAX_WIDTH] column in [available] width, never under 16dp. */
private fun contentGutter(available: Dp): Dp = maxOf(16.dp, (available - MODULE_CONTENT_MAX_WIDTH) / 2)

/**
 * The **Modules** screen. On a phone it is a list of the project's modules that opens one module at a time; on a
 * wide window the list stays beside the selected module. A module's detail view has a tab per concern: **Settings**
 * (Java version, platform SDK, source sets, facet config), the Android build tabs, and **Dependencies** (the
 * per-module dependency manager). Facet panels are generic: fields are derived from the facet codec, so a new facet
 * appears without bespoke UI. Talks only to [IdeBackend].
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
    val list = rememberModulesListState(backend)
    var selected by remember { mutableStateOf(initialModule) }
    // Kept at screen level so switching modules in the two-pane layout stays on the same tab.
    var tab by remember { mutableStateOf(initialTab) }
    // A module that was just removed cannot stay open.
    LaunchedEffect(list.modules) {
        if (list.modules.none { it.name == selected }) selected = null
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val twoPane = maxWidth >= MODULES_TWO_PANE_BREAKPOINT
        // Two panes always show a module; a phone shows the list until one is picked.
        val shown = selected ?: if (twoPane) list.modules.firstOrNull()?.name else null
        PlatformBackHandler(enabled = !twoPane && selected != null) { selected = null }

        val detail: @Composable (String, (() -> Unit)?) -> Unit = { name, back ->
            key(name) {
                ModuleDetail(
                    backend, name, list.modules.firstOrNull { it.name == name }?.typeDisplay, tab, { tab = it },
                    codeFont, fileActions, onOpenKeystoreManager, onBack = back, onRemove = { list.askRemove(name) },
                )
            }
        }
        when {
            twoPane && list.modules.isEmpty() -> Column(Modifier.fillMaxSize()) {
                ListPaneHeader(onBack, onNewModule = null)
                EmptyModules(list::openNewModule, Modifier.weight(1f).fillMaxWidth())
            }
            twoPane -> Row(Modifier.fillMaxSize()) {
                ModulesListPane(list, shown, twoPane = true, onBack, { selected = it }, Modifier.width(MODULES_LIST_PANE_WIDTH).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) { shown?.let { detail(it, null) } }
            }
            shown == null -> ModulesListPane(list, null, twoPane = false, onBack, { selected = it }, Modifier.fillMaxSize())
            else -> detail(shown) { selected = null }
        }

        NewModuleDialog(
            visible = list.newModuleOpen,
            backend = backend,
            codeFont = codeFont,
            onDismiss = list::closeNewModule,
            onCreate = list::createModule,
        )
        ConfirmModuleRemove(
            moduleName = list.pendingRemove,
            onDismiss = list::cancelRemove,
            onConfirm = list::confirmRemove,
        )
        ConfigToastHost(list.toast, Modifier.align(Alignment.BottomCenter))
    }
}

// ---- modules list -------------------------------------------------------------------------------

/**
 * The module list. As the left pane of a wide window it reads like a navigation drawer (the open module is
 * highlighted, New module sits in the header); on a phone it is the whole page, with a labelled FAB.
 */
@Composable
private fun ModulesListPane(
    state: ModulesListState,
    selected: String?,
    twoPane: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val container = if (twoPane) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background
    Box(modifier.background(container)) {
        Column(Modifier.fillMaxSize()) {
            ListPaneHeader(onBack, onNewModule = if (twoPane) state::openNewModule else null)
            if (state.modules.isEmpty()) {
                EmptyModules(state::openNewModule, Modifier.weight(1f).fillMaxWidth())
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    // A phone keeps the last row clear of the FAB.
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = if (twoPane) 12.dp else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(if (twoPane) 4.dp else 8.dp),
                ) {
                    itemsKeyed(state.modules, key = { it.name }) { m ->
                        ModuleRow(m, selected = m.name == selected, compact = twoPane, onOpen = { onOpen(m.name) }, onRemove = { state.askRemove(m.name) })
                    }
                }
            }
        }
        if (!twoPane && state.modules.isNotEmpty()) {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(Res.string.modcfg_new_module_action)) },
                icon = { Icon(CaIcons.plus, null, Modifier.size(20.dp)) },
                onClick = state::openNewModule,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun ListPaneHeader(onBack: () -> Unit, onNewModule: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) { Icon(CaIcons.chevronLeft, stringResource(Res.string.back)) }
        Text(
            stringResource(Res.string.modcfg_title_modules), color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (onNewModule != null) {
            FilledTonalIconButton(onClick = onNewModule) {
                Icon(CaIcons.plus, stringResource(Res.string.modcfg_new_module_action), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ModuleRow(module: UiModuleRef, selected: Boolean, compact: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val container = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        compact -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val onContainer = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = if (compact) 8.dp else 12.dp, bottom = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ModuleTypeTile(module.typeDisplay, if (compact) 36.dp else 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    module.name, color = onContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                )
                Text(
                    module.typeDisplay, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                )
            }
            ModuleOverflowMenu(onRemove)
            if (!compact) {
                Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/** The module's type as a tonal icon tile, so an app, a library and an Android module tell apart at a glance. */
@Composable
private fun ModuleTypeTile(typeDisplay: String, size: Dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(moduleTypeIcon(typeDisplay), null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A glyph for a module type. Keyed on the display name, which is all a [UiModuleRef] carries; it only picks an icon. */
private fun moduleTypeIcon(typeDisplay: String): ImageVector = when {
    typeDisplay.contains("Android", ignoreCase = true) -> CaIcons.androidLogo
    typeDisplay.contains("Library", ignoreCase = true) -> CaIcons.pkg
    else -> CaIcons.code
}

/**
 * Removing a module sits behind a menu: a bare close icon beside the row's chevron was one mis-tap away from the
 * confirm dialog, and it read as "close" rather than "remove".
 */
@Composable
private fun ModuleOverflowMenu(onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Symbol(CaSymbols.moreVert, stringResource(Res.string.more), size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.modcfg_remove_module)) },
                leadingIcon = { Symbol(CaSymbols.delete, null, size = 20.dp, tint = MaterialTheme.colorScheme.error) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                onClick = { open = false; onRemove() },
            )
        }
    }
}

@Composable
private fun EmptyModules(onCreate: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)), Alignment.Center) {
            Icon(CaIcons.layers, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(Res.string.modcfg_empty_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(Res.string.modcfg_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) {
            Icon(CaIcons.plus, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.modcfg_new_module_action))
        }
    }
}

// ---- module detail (header + tabs) --------------------------------------------------------------

@Composable
private fun ModuleDetail(
    backend: IdeBackend,
    moduleName: String,
    typeDisplay: String?,
    tab: ModulesTab,
    onSelectTab: (ModulesTab) -> Unit,
    codeFont: FontFamily,
    fileActions: FileActions,
    onOpenKeystoreManager: () -> Unit,
    onBack: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val settings = rememberModuleSettingsState(backend, moduleName)
    // Whether the module builds through the Android pipeline (the build-features pane loads only for one). Unknown
    // until it answers, and every tab shows meanwhile, so an Android module's tab strip never jumps.
    val android by produceState<Boolean?>(null, backend, moduleName) {
        value = runCatching { backend.modules.getBuildFeatures(moduleName) != null }.getOrDefault(true)
    }
    val tabs = ModulesTab.entries.filter { !it.androidOnly || android != false }
    val current = if (tab in tabs) tab else ModulesTab.Settings

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val gutter = contentGutter(maxWidth)
        Column(Modifier.fillMaxSize()) {
            ModuleDetailHeader(moduleName, settings.config?.typeDisplay ?: typeDisplay.orEmpty(), gutter, onBack, onRemove)
            ModuleTabRow(tabs, current, gutter, onSelectTab)
            val pane = Modifier.weight(1f).fillMaxWidth()
            when (current) {
                ModulesTab.Settings -> ModuleSettingsTab(backend, settings, codeFont, pane)
                ModulesTab.BuildFeatures -> BuildFeaturesPane(backend, moduleName, pane)
                ModulesTab.CompilerPlugins -> CompilerPluginsPane(backend, moduleName, pane)
                ModulesTab.Packaging -> PackagingPane(backend, moduleName, codeFont, pane)
                ModulesTab.Signing -> SigningPane(backend, moduleName, onOpenKeystoreManager, pane)
                ModulesTab.Dependencies -> DependenciesPane(backend, moduleName, codeFont, fileActions, pane)
            }
        }
    }
}

/** The module's identity: type tile, name, type, and the module actions. Aligned with the page column below it. */
@Composable
private fun ModuleDetailHeader(name: String, typeDisplay: String, gutter: Dp, onBack: (() -> Unit)?, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(start = if (onBack != null) 4.dp else gutter, end = (gutter - 12.dp).coerceAtLeast(4.dp), top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(CaIcons.chevronLeft, stringResource(Res.string.back)) }
        ModuleTypeTile(typeDisplay, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (typeDisplay.isNotEmpty()) {
                Text(typeDisplay, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ModuleOverflowMenu(onRemove)
    }
}

@Composable
private fun ModuleTabRow(tabs: List<ModulesTab>, tab: ModulesTab, gutter: Dp, onSelect: (ModulesTab) -> Unit) {
    // Scrollable so it never clips on a narrow phone. Its edge padding lines the first tab's label up with the page
    // column (a tab pads its label by 16dp itself).
    PrimaryScrollableTabRow(
        selectedTabIndex = tabs.indexOf(tab),
        edgePadding = (gutter - 16.dp).coerceAtLeast(0.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        tabs.forEach { t ->
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

/**
 * A module page's scrolling column: centred at [MODULE_CONTENT_MAX_WIDTH] on a wide window, a 16dp gutter on a
 * phone. The gutters are content padding, so the whole width scrolls. [content] learns whether the column is wide
 * enough for two fields to a row.
 */
@Composable
private fun ModulePage(modifier: Modifier, content: LazyListScope.(twoColumn: Boolean) -> Unit) {
    BoxWithConstraints(modifier) {
        val gutter = contentGutter(maxWidth)
        val twoColumn = maxWidth - gutter * 2 >= MODULE_TWO_COLUMN_MIN
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content(twoColumn) }
    }
}

/** The empty page of an Android-only pane opened on a module it does not apply to. */
@Composable
private fun PaneNotApplicable(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PaneIntro(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

// ---- Build Features (AGP buildFeatures: viewBinding / compose) -----------------------------------

@Composable
private fun BuildFeaturesPane(backend: IdeBackend, moduleName: String, modifier: Modifier) {
    val state = rememberBuildFeaturesState(backend, moduleName)

    Box(modifier) {
        val f = state.model
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            f == null -> PaneNotApplicable(stringResource(Res.string.modcfg_build_features_android_only))
            else -> ModulePage(Modifier.fillMaxSize()) {
                item("intro") { PaneIntro(stringResource(Res.string.modcfg_build_features_intro)) }
                itemsKeyed(f.features, key = { it.id }) { feature ->
                    ToggleCard(
                        title = feature.title,
                        description = feature.description,
                        checked = feature.enabled,
                        working = state.busyId == feature.id,
                        enabled = state.idle,
                        onToggle = { enabled -> state.setEnabled(feature.id, enabled) },
                    ) {
                        feature.note?.let { Text(it, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

/** A switchable entry (a build feature, a compiler plugin): the whole card toggles, and a spinner replaces the switch while it applies. */
@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    working: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth()
                .toggleable(value = checked, enabled = enabled && !working, role = Role.Switch, onValueChange = onToggle)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (working) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                else Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
            footer()
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
            p == null -> PaneNotApplicable(stringResource(Res.string.modcfg_compiler_plugins_android_only))
            else -> ModulePage(Modifier.fillMaxSize()) {
                item("intro") { PaneIntro(stringResource(Res.string.modcfg_compiler_plugins_intro)) }
                itemsKeyed(p.plugins, key = { it.id }) { plugin ->
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
    ToggleCard(plugin.title, plugin.description, plugin.enabled, working, switchEnabled, onToggle) {
        // "Active on the classpath" badge — the real build behavior, which can differ from the toggle when the
        // runtime came in transitively (a dependency pulling it in applies the plugin even if not toggled here).
        if (plugin.applied) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(CaIcons.check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.modcfg_compiler_plugin_applied), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        plugin.note?.let {
            Text(it, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
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
            o == null -> PaneNotApplicable(stringResource(Res.string.modcfg_packaging_android_only))
            else -> ModulePage(Modifier.fillMaxSize()) {
                item("intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaneIntro(stringResource(Res.string.modcfg_packaging_intro))
                        Text(stringResource(Res.string.modcfg_packaging_glob_hint), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item("resources") {
                    SectionCard(stringResource(Res.string.modcfg_packaging_resources)) {
                        PackagingRuleList(stringResource(Res.string.modcfg_packaging_excludes), stringResource(Res.string.modcfg_packaging_excludes_desc), state.resourceExcludes, codeFont)
                        PackagingRuleList(stringResource(Res.string.modcfg_packaging_pick_first), stringResource(Res.string.modcfg_packaging_pick_first_desc), state.resourcePickFirsts, codeFont)
                        PackagingRuleList(stringResource(Res.string.modcfg_packaging_merge), stringResource(Res.string.modcfg_packaging_merge_desc), state.resourceMerges, codeFont)
                        DefaultsDisclosure(stringResource(Res.string.modcfg_packaging_default_excludes), o.defaultResourceExcludes, codeFont)
                        DefaultsDisclosure(stringResource(Res.string.modcfg_packaging_default_merges), o.defaultResourceMerges, codeFont)
                    }
                }
                item("jni") {
                    SectionCard(stringResource(Res.string.modcfg_packaging_jni), subtitle = stringResource(Res.string.modcfg_packaging_jni_note)) {
                        PackagingRuleList(stringResource(Res.string.modcfg_packaging_excludes), stringResource(Res.string.modcfg_packaging_excludes_desc), state.jniExcludes, codeFont)
                        PackagingRuleList(stringResource(Res.string.modcfg_packaging_pick_first), stringResource(Res.string.modcfg_packaging_pick_first_desc), state.jniPickFirsts, codeFont)
                    }
                }
                item("compression") {
                    SectionCard(stringResource(Res.string.modcfg_packaging_compression)) {
                        PackagingRuleList(stringResource(Res.string.modcfg_packaging_no_compress), stringResource(Res.string.modcfg_packaging_no_compress_desc), state.noCompress, codeFont)
                        DefaultsDisclosure(stringResource(Res.string.modcfg_packaging_default_no_compress), o.defaultNoCompress, codeFont)
                    }
                }
                item("save") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                        Button(onClick = state::save, enabled = !state.saving) {
                            Icon(CaIcons.check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.modcfg_save))
                        }
                    }
                }
            }
        }
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

/** One labelled + described glob-pattern list within a packaging section. */
@Composable
private fun PackagingRuleList(label: String, description: String, values: SnapshotStateList<String>, codeFont: FontFamily) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        StringListEditor(values, codeFont)
    }
}

/** A collapsible read-only list of the AGP defaults that are always applied on top of the module's rules. */
@Composable
private fun DefaultsDisclosure(label: String, patterns: List<String>, codeFont: FontFamily) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (open) CaIcons.chevronDown else CaIcons.chevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$label (${patterns.size})", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        if (open) patterns.forEach { p ->
            Text(p, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont), modifier = Modifier.padding(start = 22.dp))
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
            d == null -> PaneNotApplicable(stringResource(Res.string.modcfg_signing_android_only))
            else -> ModulePage(Modifier.fillMaxSize()) {
                item("intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PaneIntro(stringResource(Res.string.modcfg_signing_intro))
                        FilledTonalButton(onClick = onOpenKeystoreManager) {
                            Icon(CaIcons.key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.modcfg_manage_keystores))
                        }
                    }
                }
                itemsKeyed(d.assignments, key = { it.buildType }) { a ->
                    BuildTypeSigningRow(a, d.keystores, state.busy) { keystoreId -> state.assign(a.buildType, keystoreId) }
                }
                if (d.keystores.isEmpty()) item("empty") {
                    Text(stringResource(Res.string.modcfg_keystores_empty),
                        color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ConfigToastHost(state.toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BuildTypeSigningRow(assignment: UiSigningAssignment, keystores: List<UiKeystore>, busy: Boolean, onAssign: (String?) -> Unit) {
    SectionCard(assignment.buildType) {
        // The choices: the default debug keystore (null) plus every registered keystore. They wrap rather than
        // scroll sideways, so every keystore is visible at once.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun ModuleSettingsTab(backend: IdeBackend, state: ModuleSettingsState, codeFont: FontFamily, modifier: Modifier) {
    Box(modifier) {
        ConfigBody(
            state.config, state.loading, codeFont, backend.project.rootPath, state.missingProguard, Modifier.fillMaxSize(),
            onAddSourceRoot = state::openAddSourceRoot,
            onRemoveSourceRoot = state::removeSourceRoot,
            onCreateProguard = state::createProguardFile,
            onSave = state::applyEdit,
        )
        AddSourceRootDialog(
            request = if (state.addRootOpen) AddSourceRootRequest(state.moduleName, state.sourceSets) else null,
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
    if (destructive) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text(label) }
    } else {
        TextButton(onClick = onClick) { Text(label) }
    }
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
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(Res.string.modcfg_name)) },
                            placeholder = { Text(stringResource(Res.string.modcfg_module_name_placeholder)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = codeFont),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item("type") {
                        LabeledField(stringResource(Res.string.modcfg_type)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                types.forEachIndexed { i, t -> LevelChip(t.displayName, i == typeIdx) { typeIdx = i } }
                            }
                        }
                    }
                    item("level") {
                        LabeledField(stringResource(Res.string.modcfg_java_version)) {
                            JavaLevelPicker(type.languageLevels, level) { level = it }
                        }
                    }
                    itemsKeyed(forms, key = { it.table }) { form -> FacetPanel(form, codeFont, twoColumn = false) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.weight(1f))
                    DialogTextButton(stringResource(Res.string.cancel), destructive = false, onClick = onDismiss)
                    Button(onClick = { onCreate(name.trim(), type.id, level, forms.associate { it.table to it.toValues() }) }) {
                        Icon(CaIcons.check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.create))
                    }
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
            config == null -> PaneNotApplicable(stringResource(Res.string.modcfg_couldnt_load_config))
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
        ModulePage(Modifier.weight(1f).fillMaxWidth()) { twoColumn ->
            // ---- General ----
            item("general") {
                GeneralCard(config, level, { level = it }, sdk, { sdk = it }, codeFont, projectRoot, twoColumn)
            }

            // ---- Run configuration (console Java/Kotlin modules) ----
            config.runConfig?.let { rc ->
                item("run") { RunConfigCard(rc, mainClass, codeFont) }
            }

            // ---- Minify: referenced-but-missing keep-rule files ----
            if (missingProguard.isNotEmpty()) {
                item("proguardMissing") { MissingProguardCard(missingProguard, codeFont, onCreateProguard) }
            }

            // ---- Source sets (add / remove typed roots) ----
            item("sourceSets") {
                SourceSetsCard(config.sourceSets, codeFont, projectRoot, twoColumn, onAddSourceRoot, onRemoveSourceRoot)
            }

            // ---- Facet panels (generic) ----
            itemsKeyed(forms, key = { it.table }) { form -> FacetPanel(form, codeFont, twoColumn) }
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

/**
 * The module's build basics: where it compiles to, its Java level (a segmented choice, since there are only a
 * handful) and its platform SDK (a menu, since an SDK-rich workspace lists many and their labels are long).
 */
@Composable
private fun GeneralCard(
    config: UiModuleConfig,
    level: String,
    onLevel: (String) -> Unit,
    sdk: String,
    onSdk: (String) -> Unit,
    codeFont: FontFamily,
    projectRoot: String,
    twoColumn: Boolean,
) {
    SectionCard(stringResource(Res.string.modcfg_section_general)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CaIcons.folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.modcfg_output), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(
                shortenPath(config.outputDir, projectRoot), color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val levelPicker = @Composable { m: Modifier ->
            LabeledField(stringResource(Res.string.modcfg_java_version), m) { JavaLevelPicker(config.languageLevels, level, onLevel) }
        }
        // Platform SDK: the boot classpath the module compiles/completes against. "Auto" follows the module type
        // (Java → core-Java, Android → the Android SDK); pinning it is how a console module is kept off
        // android.jar, or a module is targeted at a specific installed platform.
        val showSdk = config.availableSdks.size > 1
        val sdkPicker = @Composable { m: Modifier ->
            val auto = stringResource(Res.string.modcfg_platform_sdk_auto, config.resolvedSdk)
            val options = listOf("" to auto) + config.availableSdks.map { it.name to it.label }
            LabeledField(stringResource(Res.string.modcfg_platform_sdk), m) {
                DropdownField(options.firstOrNull { it.first == sdk }?.second ?: sdk, options, sdk, onSdk)
            }
        }
        if (twoColumn && showSdk) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                levelPicker(Modifier.weight(1f))
                sdkPicker(Modifier.weight(1f))
            }
        } else {
            levelPicker(Modifier.fillMaxWidth())
            if (showSdk) sdkPicker(Modifier.fillMaxWidth())
        }
        config.missingPlatform?.let { mp ->
            Notice(stringResource(Res.string.modcfg_platform_missing, mp.compileSdk, mp.usedLevel), error = true)
        }
    }
}

/** The module's Java level as a segmented row ("8 · 11 · 17 · 21"), or a menu when there are too many to fit. */
@Composable
private fun JavaLevelPicker(levels: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (levels.size > 5) {
        DropdownField(prettyLevel(selected), levels.map { it to prettyLevel(it) }, selected, onSelect)
        return
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        levels.forEachIndexed { i, lvl ->
            SegmentedButton(
                selected = lvl == selected,
                onClick = { onSelect(lvl) },
                shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                // No check mark: four "Java NN" segments with one have to fit a 360dp phone.
                icon = {},
                label = { Text(prettyLevel(lvl), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** A read-only field that opens a menu of [options] (value to label); [value] is the label shown. */
@Composable
private fun DropdownField(value: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(Ca.radius.pill),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Symbol(CaSymbols.arrowDropDown, null, size = 24.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = if (key == selected) ({ Icon(CaIcons.check, null, Modifier.size(18.dp)) }) else null,
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}

/** An inline warning or error inside a card. */
@Composable
private fun Notice(text: String, error: Boolean) {
    val tint = if (error) MaterialTheme.colorScheme.error else Ide.colors.warning
    Row(
        Modifier.fillMaxWidth().background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.small).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CaIcons.warning, null, Modifier.size(18.dp), tint = tint)
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}

/** The pinned unsaved-changes bar: what changed, a way back, and the commit. */
@Composable
private fun SaveBar(onDiscard: () -> Unit, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gutter = contentGutter(maxWidth)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
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
}

/**
 * The console Run configuration for a Java/Kotlin module: which `main` class the Run button launches. A blank
 * field means auto-detect (the placeholder shows what that resolves to); the entry points found in the module's
 * sources are offered as one-tap chips, plus an "Auto-detect" chip that clears the override.
 */
@Composable
private fun RunConfigCard(rc: UiRunConfig, mainClass: MutableState<String>, codeFont: FontFamily) {
    SectionCard(stringResource(Res.string.modcfg_run), subtitle = stringResource(Res.string.modcfg_run_config_hint)) {
        OutlinedTextField(
            value = mainClass.value,
            onValueChange = { mainClass.value = it },
            singleLine = true,
            placeholder = {
                Text(
                    rc.autoDetected?.let { stringResource(Res.string.modcfg_auto_detected, it) } ?: stringResource(Res.string.modcfg_run_main_class_placeholder),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (rc.detectedMainClasses.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    OutlinedCard(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, Ide.colors.warning.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.warning, null, Modifier.size(18.dp), tint = Ide.colors.warning)
                Text(stringResource(Res.string.modcfg_missing_keep_rule_files), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(Res.string.modcfg_missing_keep_rule_files_content),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            missing.forEach { mf ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(mf.entry, color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (mf.consumer) stringResource(Res.string.modcfg_consumer_suffix, mf.buildType) else mf.buildType,
                            color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(onClick = { onCreate(mf.entry) }) {
                        Icon(CaIcons.plus, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.create))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
                action?.invoke()
            }
            content()
        }
    }
}

/**
 * The module's source sets. A set's roots usually share one folder (`app/src/main/{java,kotlin,res,assets}`), so
 * that folder is written once and each root is a chip of its own name. Four full paths per set made this the
 * tallest, noisiest card on the page.
 */
@Composable
private fun SourceSetsCard(
    sets: List<UiSourceSetInfo>,
    codeFont: FontFamily,
    projectRoot: String,
    wide: Boolean,
    onAdd: () -> Unit,
    onRemoveRoot: (sourceSet: String, rootPath: String) -> Unit,
) {
    SectionCard(stringResource(Res.string.modcfg_section_source_sets), action = {
        TextButton(onClick = onAdd) {
            Icon(CaIcons.plus, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.modcfg_add_source_root))
        }
    }) {
        if (sets.isEmpty()) {
            Text(stringResource(Res.string.modcfg_no_source_sets), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        }
        sets.forEachIndexed { i, ss ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SourceSetBlock(ss, codeFont, projectRoot, wide) { root -> onRemoveRoot(ss.name, root) }
        }
    }
}

@Composable
private fun SourceSetBlock(ss: UiSourceSetInfo, codeFont: FontFamily, projectRoot: String, wide: Boolean, onRemoveRoot: (rootPath: String) -> Unit) {
    val relative = ss.roots.map { it to shortenPath(it, projectRoot) }
    // The folder every root sits in, when they share one; then a chip only needs the root's own name.
    val parent = relative.map { it.second.substringBeforeLast('/', "") }.distinct().singleOrNull()?.takeIf { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val folder: @Composable (Modifier) -> Unit = { m ->
            if (parent != null) {
                Text(
                    parent, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ss.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(
                ss.scope.lowercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            // Beside the name when there is room; on a phone it gets a line of its own rather than being cut short.
            if (wide) folder(Modifier.weight(1f, fill = false))
        }
        if (!wide) folder(Modifier)
        if (ss.roots.isEmpty()) {
            Text(stringResource(Res.string.modcfg_no_roots), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                relative.forEach { (full, rel) ->
                    RemovableChip(
                        label = if (parent != null) rel.substringAfterLast('/') else rel,
                        removeLabel = stringResource(Res.string.modcfg_remove_named, rel),
                        codeFont = codeFont,
                        icon = CaIcons.folder,
                        onRemove = { onRemoveRoot(full) },
                    )
                }
            }
        }
    }
}

/** A value with its own remove button: a source root, a glob pattern, a list entry. */
@Composable
private fun RemovableChip(label: String, removeLabel: String, codeFont: FontFamily, icon: ImageVector? = null, onRemove: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.height(32.dp).padding(start = if (icon != null) 8.dp else 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge.copy(fontFamily = codeFont),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(CaIcons.close, removeLabel, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun FacetPanel(form: FacetForm, codeFont: FontFamily, twoColumn: Boolean) {
    var open by remember(form) { mutableStateOf(true) }
    val caret by animateFloatAsState(if (open) 0f else -90f, tween(Motion.FAST), label = "facetCaret")
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)), Alignment.Center) {
                Icon(CaIcons.box, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(form.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(CaIcons.chevronDown, null, Modifier.size(20.dp).rotate(caret), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(Motion.FAST)) + fadeIn(), exit = shrinkVertically(tween(Motion.FAST)) + fadeOut()) {
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                FieldGrid(form.fields, codeFont, twoColumn)
            }
        }
    }
}

/**
 * A facet's fields laid out by kind: text and number inputs on a grid (two to a row when there is room), the
 * switches as a grid of toggle rows, then lists and tables at full width. Order within each kind is the codec's.
 */
@Composable
private fun FieldGrid(fields: List<FieldState>, codeFont: FontFamily, twoColumn: Boolean) {
    val columns = if (twoColumn) 2 else 1
    val inputs = fields.filter { it is FieldState.TextF || it is FieldState.NumberF }
    val toggles = fields.filterIsInstance<FieldState.BoolF>()
    val blocks = fields.filter { it is FieldState.ListF || it is FieldState.TableF }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (inputs.isNotEmpty()) GridRows(inputs, columns) { FieldEditor(it, codeFont, twoColumn) }
        if (toggles.isNotEmpty()) GridRows(toggles, columns, spacing = 8.dp) { ToggleRow(it) }
        blocks.forEach { FieldEditor(it, codeFont, twoColumn) }
    }
}

/** [items] in rows of [columns] equal cells; a short last row keeps its cells the same width as the rest. */
@Composable
private fun <T> GridRows(items: List<T>, columns: Int, spacing: Dp = 12.dp, cell: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FieldEditor(field: FieldState, codeFont: FontFamily, twoColumn: Boolean) {
    when (field) {
        is FieldState.TextF -> BoxedTextField(field.label, field.value, codeFont)
        is FieldState.NumberF -> BoxedTextField(field.label, field.value, codeFont, numeric = true)
        is FieldState.BoolF -> ToggleRow(field)
        is FieldState.ListF -> LabeledField(field.label) { StringListEditor(field.values, codeFont) }
        is FieldState.TableF -> TableListEditor(field, codeFont, twoColumn)
    }
}

/** A switch with its label; the whole row is the touch target. */
@Composable
private fun ToggleRow(field: FieldState.BoolF) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .toggleable(value = field.value.value, role = Role.Switch) { field.value.value = it }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(field.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = field.value.value, onCheckedChange = null)
    }
}

@Composable
private fun LabeledField(label: String, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** An M3 outlined field with its label inside the outline, so a grid of them stays compact. */
@Composable
private fun BoxedTextField(label: String, state: MutableState<String>, codeFont: FontFamily, numeric: Boolean = false) {
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = if (numeric) it.filter { c -> c.isDigit() } else it },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = codeFont),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** An editable list of strings: the entries as removable chips, then a field that adds one (Enter or +). */
@Composable
private fun StringListEditor(values: SnapshotStateList<String>, codeFont: FontFamily) {
    var draft by remember { mutableStateOf("") }
    val add = { if (draft.isNotBlank()) { values.add(draft.trim()); draft = "" } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (values.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEachIndexed { i, v ->
                    RemovableChip(v, stringResource(Res.string.modcfg_remove_named, v), codeFont, onRemove = { values.removeAt(i) })
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.modcfg_add_placeholder)) },
            trailingIcon = {
                IconButton(onClick = add, enabled = draft.isNotBlank()) { Icon(CaIcons.plus, stringResource(Res.string.add), Modifier.size(20.dp)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = codeFont),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TableListEditor(field: FieldState.TableF, codeFont: FontFamily, twoColumn: Boolean) {
    // Highlight the just-added row briefly so it's obvious a new item appeared (it animates in at the bottom).
    var justAdded by remember { mutableStateOf(-1) }
    LaunchedEffect(justAdded) { if (justAdded >= 0) { delay(1600); justAdded = -1 } }
    val singular = field.label.lowercase().removeSuffix("s")

    Column(Modifier.fillMaxWidth().animateContentSize(tween(Motion.BASE, easing = Motion.spring)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(field.label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        if (field.rows.isEmpty()) Text(stringResource(Res.string.modcfg_no_rows_yet, "${singular}s"), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        field.rows.forEachIndexed { i, row ->
            val isNew = i == justAdded
            val borderColor by animateColorAsState(if (isNew) MaterialTheme.colorScheme.primary else Color.Transparent, tween(Motion.SLOW), label = "newRowBorder")
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                    .border(1.5.dp, borderColor, MaterialTheme.shapes.medium).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rowTitle(row, i), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (isNew) Chip(stringResource(Res.string.modcfg_new_badge), fill = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { val at = i; field.rows.removeAt(at); if (justAdded == at) justAdded = -1 }) {
                        Symbol(CaSymbols.delete, stringResource(Res.string.remove), size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.padding(end = 8.dp)) { FieldGrid(row, codeFont, twoColumn) }
            }
        }
        OutlinedButton(onClick = { field.rows.add(cloneTemplateRow(field)); justAdded = field.rows.lastIndex }, modifier = Modifier.fillMaxWidth()) {
            Icon(CaIcons.plus, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.modcfg_add_row, singular))
        }
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

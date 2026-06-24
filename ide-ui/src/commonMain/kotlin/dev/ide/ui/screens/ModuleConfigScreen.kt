package dev.ide.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiMissingProguardFile
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiSourceSetInfo
import dev.ide.ui.components.AddSourceRootDialog
import dev.ide.ui.components.AddSourceRootRequest
import dev.ide.ui.components.Chip
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ConfigToast(val text: String, val error: Boolean)

/** The two tabs of a module's detail view. */
enum class ModulesTab { Settings, Dependencies }

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
    codeFont: FontFamily = FontFamily.Monospace,
    fileActions: FileActions = FileActions.None,
) {
    var selected by remember { mutableStateOf(initialModule) }
    val module = selected
    if (module == null) {
        ModulesList(backend, codeFont, onOpen = { selected = it }, onBack = onBack)
    } else {
        ModuleDetail(backend, module, initialTab, codeFont, fileActions, onBack = { selected = null })
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
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack)
            Icon(icon, null, Modifier.size(20.dp), tint = Ca.colors.accent)
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            action?.invoke()
        }
    }
}

// ---- modules list -------------------------------------------------------------------------------

@Composable
private fun ModulesList(backend: IdeBackend, codeFont: FontFamily, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var modules by remember { mutableStateOf(backend.configurableModules()) }
    var newOpen by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<ConfigToast?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    Box(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            ModulesHeader("Modules", CaIcons.layers, onBack) {
                IconButtonCa(CaIcons.plus, "New module", onClick = { newOpen = true }, active = true)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            if (modules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No modules. Tap + to add one.", color = Ca.colors.textTertiary, style = Ca.type.subhead)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(modules, key = { it.name }) { m ->
                        ModuleListItem(m, onOpen = { onOpen(m.name) }, onRemove = { pendingRemove = m.name })
                    }
                }
            }
        }
        NewModuleDialog(
            visible = newOpen,
            backend = backend,
            codeFont = codeFont,
            onDismiss = { newOpen = false },
            onCreate = { name, typeId, level, facetValues ->
                scope.launch {
                    val r = backend.createModule(name, typeId, level, facetValues)
                    toast = ConfigToast(r.message, error = !r.success)
                    if (r.success) { newOpen = false; modules = backend.configurableModules() }
                }
            },
        )
        ConfirmModuleRemove(
            moduleName = pendingRemove,
            onDismiss = { pendingRemove = null },
            onConfirm = {
                val name = pendingRemove
                if (name != null && backend.removeModule(name)) {
                    toast = ConfigToast("Removed $name", error = false); modules = backend.configurableModules()
                }
                pendingRemove = null
            },
        )
        ConfigToastHost(toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ModuleListItem(module: UiModuleRef, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CaIcons.layers, null, Modifier.size(20.dp), tint = Ca.colors.accent)
        Column(Modifier.weight(1f)) {
            Text(module.name, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(module.typeDisplay, color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButtonCa(CaIcons.close, "Remove ${module.name}", onClick = onRemove, boxSize = 30, iconSize = 16, tint = Ca.colors.textTertiary)
        Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = Ca.colors.textTertiary)
    }
}

// ---- module detail (Settings | Dependencies) ---------------------------------------------------

@Composable
private fun ModuleDetail(backend: IdeBackend, moduleName: String, initialTab: ModulesTab, codeFont: FontFamily, fileActions: FileActions, onBack: () -> Unit) {
    var tab by remember(moduleName) { mutableStateOf(initialTab) }
    Box(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            ModulesHeader(moduleName, CaIcons.gear, onBack)
            ModuleTabRow(tab) { tab = it }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            when (tab) {
                ModulesTab.Settings -> ModuleSettingsTab(backend, moduleName, codeFont, Modifier.weight(1f).fillMaxWidth())
                ModulesTab.Dependencies -> DependenciesPane(backend, moduleName, codeFont, fileActions, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ModuleTabRow(tab: ModulesTab, onSelect: (ModulesTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModulesTab.entries.forEach { t ->
            val sel = t == tab
            val bg by animateColorAsState(if (sel) Ca.colors.accentSoft else Ca.colors.surface2, tween(Motion.FAST), label = "tabBg")
            Box(
                Modifier.background(bg, RoundedCornerShape(Ca.radius.pill)).clickable(remember { MutableInteractionSource() }, null) { onSelect(t) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(t.name, color = if (sel) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModuleSettingsTab(backend: IdeBackend, moduleName: String, codeFont: FontFamily, modifier: Modifier) {
    var config by remember(moduleName) { mutableStateOf<UiModuleConfig?>(null) }
    var loading by remember(moduleName) { mutableStateOf(false) }
    var reloadKey by remember(moduleName) { mutableStateOf(0) }
    var toast by remember { mutableStateOf<ConfigToast?>(null) }
    var addRootOpen by remember { mutableStateOf(false) }
    var missingProguard by remember(moduleName) { mutableStateOf<List<UiMissingProguardFile>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(moduleName, reloadKey) {
        loading = true
        config = runCatching { backend.getModuleConfig(moduleName) }.getOrNull()
        missingProguard = runCatching { backend.missingProguardFiles(moduleName) }.getOrDefault(emptyList())
        loading = false
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    Box(modifier) {
        ConfigBody(
            config, loading, codeFont, backend.project.rootPath, missingProguard, Modifier.fillMaxSize(),
            onAddSourceRoot = { addRootOpen = true },
            onRemoveSourceRoot = { set, root -> if (backend.removeSourceRoot(moduleName, set, root)) reloadKey++ },
            onCreateProguard = { entry ->
                scope.launch {
                    val created = backend.createProguardFile(moduleName, entry)
                    toast = ConfigToast(
                        if (created != null) "Created $entry" else "Couldn't create $entry",
                        error = created == null,
                    )
                    if (created != null) reloadKey++
                }
            },
        ) { edit ->
            scope.launch {
                val r = backend.updateModuleConfig(moduleName, edit)
                toast = ConfigToast(r.message, error = !r.success)
                if (r.success) reloadKey++
            }
        }
        AddSourceRootDialog(
            request = if (addRootOpen) AddSourceRootRequest(moduleName, backend.moduleSourceSets(moduleName)) else null,
            onDismiss = { addRootOpen = false },
            onAdd = { module, set, dirName, role -> if (backend.addSourceRoot(module, set, dirName, role) != null) reloadKey++ },
        )
        ConfigToastHost(toast, Modifier.align(Alignment.BottomCenter))
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
                .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)).padding(20.dp),
        ) {
            Text("Remove module", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Remove '${shown ?: ""}' from the project? Its files are left on disk; other modules' dependencies on it are dropped.",
                color = Ca.colors.textSecondary, style = Ca.type.footnote)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                DialogTextButton("Cancel", destructive = false, onClick = onDismiss)
                DialogTextButton("Remove", destructive = true, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DialogTextButton(label: String, destructive: Boolean, onClick: () -> Unit) {
    val fill = if (destructive) Ca.colors.error else Ca.colors.surface3
    val fg = if (destructive) Ca.colors.textOnAccent else Ca.colors.textSecondary
    Box(
        Modifier.background(fill, RoundedCornerShape(Ca.radius.control)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) { Text(label, color = fg, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold) }
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
        val types = remember { backend.availableModuleTypes() }
        Column(
            Modifier.padding(horizontal = 12.dp).widthIn(max = 560.dp).fillMaxWidth()
                .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)).padding(20.dp),
        ) {
            if (types.isEmpty()) {
                Text("No module types available.", color = Ca.colors.textTertiary, style = Ca.type.subhead)
            } else {
                var name by remember { mutableStateOf("") }
                var typeIdx by remember { mutableStateOf(0) }
                val type = types[typeIdx.coerceIn(0, types.lastIndex)]
                var level by remember(type.id) { mutableStateOf(type.defaultLanguageLevel) }
                // Facet forms are rebuilt when the chosen type changes (each type has its own default facets).
                val forms = remember(type.id) { type.defaultFacets.map { it.toForm() } }

                Text("New module", color = Ca.colors.textPrimary, style = Ca.type.title3, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("name") {
                        LabeledField("Name") {
                            Box(Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                                if (name.isEmpty()) Text("e.g. feature, core", color = Ca.colors.textTertiary, style = Ca.type.footnote)
                                BasicTextField(name, { name = it }, singleLine = true,
                                    textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary, fontFamily = codeFont),
                                    cursorBrush = SolidColor(Ca.colors.accent), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    item("type") {
                        LabeledField("Type") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                types.forEachIndexed { i, t -> LevelChip(t.displayName, i == typeIdx) { typeIdx = i } }
                            }
                        }
                    }
                    item("level") {
                        LabeledField("Java version") {
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
                    DialogTextButton("Cancel", destructive = false, onClick = onDismiss)
                    PrimaryButton("Create", icon = CaIcons.check, onClick = {
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
                CircularProgressIndicator(Modifier.size(28.dp), color = Ca.colors.accent, strokeWidth = 3.dp)
            }
            config == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load module configuration.", color = Ca.colors.textTertiary, style = Ca.type.subhead)
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
    // Editable state, rebuilt whenever a fresh config is loaded (e.g. after a save).
    var level by remember(config) { mutableStateOf(config.languageLevel) }
    val forms = remember(config) { config.facets.map { it.toForm() } }
    val dirty = level != config.languageLevel  // (facet edits always allow save; this just brightens the button)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- General ----
        item("general") {
            SectionCard("General") {
                MetaRow("Type") { Chip(config.typeDisplay, fill = Ca.colors.accentSoft, textColor = Ca.colors.accent) }
                MetaRow("Output") {
                    Text(shortenPath(config.outputDir, projectRoot), color = Ca.colors.textTertiary,
                        style = Ca.type.caption.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text("Java version", color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    config.languageLevels.forEach { lvl -> LevelChip(prettyLevel(lvl), lvl == level) { level = lvl } }
                }
            }
        }

        // ---- Source sets (add / remove typed roots) ----
        item("sourceSets") {
            SectionCard("Source sets", action = {
                IconButtonCa(CaIcons.plus, "Add source root", onClick = onAddSourceRoot, boxSize = 26, iconSize = 16, active = true)
            }) {
                if (config.sourceSets.isEmpty()) {
                    Text("No source sets yet.", color = Ca.colors.textTertiary, style = Ca.type.caption2)
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

        item("save") {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                PrimaryButton(if (dirty) "Save changes" else "Save", icon = CaIcons.check, onClick = {
                    onSave(UiModuleConfigEdit(
                        languageLevel = level,
                        facetValues = forms.associate { it.table to it.toValues() },
                    ))
                })
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
        Modifier.fillMaxWidth().background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.warning.copy(alpha = 0.5f), RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.warning, null, Modifier.size(18.dp), tint = Ca.colors.warning)
            Text("Missing keep-rule files", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "These files are referenced by a build type but don't exist, so R8 skips them when minify is on.",
            color = Ca.colors.textSecondary, style = Ca.type.caption,
        )
        missing.forEach { mf ->
            Row(
                Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mf.entry, color = Ca.colors.textPrimary,
                        style = Ca.type.footnote.copy(fontFamily = codeFont), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${mf.buildType}${if (mf.consumer) " · consumer" else ""}",
                        color = Ca.colors.textTertiary, style = Ca.type.caption2)
                }
                CreateRuleButton { onCreate(mf.entry) }
            }
        }
    }
}

@Composable
private fun CreateRuleButton(onClick: () -> Unit) {
    Row(
        Modifier.background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.control))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(CaIcons.plus, null, Modifier.size(14.dp), tint = Ca.colors.accent)
        Text("Create", color = Ca.colors.accent, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionCard(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

/** A compact label · value row used in the General card (label fixed-width so values line up). */
@Composable
private fun MetaRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Ca.colors.textSecondary, style = Ca.type.caption, modifier = Modifier.width(60.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun SourceSetRow(ss: UiSourceSetInfo, codeFont: FontFamily, projectRoot: String, onRemoveRoot: (rootPath: String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ss.name, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            Chip(ss.scope.lowercase(), fill = Ca.colors.accentSoft, textColor = Ca.colors.accent)
        }
        if (ss.roots.isEmpty()) Text("no roots", color = Ca.colors.textTertiary, style = Ca.type.caption2)
        ss.roots.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(shortenPath(r, projectRoot), color = Ca.colors.textTertiary, style = Ca.type.caption2.copy(fontFamily = codeFont),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButtonCa(CaIcons.close, "Remove ${shortenPath(r, projectRoot)}", onClick = { onRemoveRoot(r) }, boxSize = 22, iconSize = 12)
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
        Modifier.fillMaxWidth().background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CaIcons.box, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Text(form.title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) CaIcons.caretDown else CaIcons.caretRight, null, Modifier.size(16.dp), tint = Ca.colors.textTertiary)
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
            Text(field.label, color = Ca.colors.textPrimary, style = Ca.type.footnote, modifier = Modifier.weight(1f))
            ToggleSwitch(field.value.value) { field.value.value = it }
        }
        is FieldState.ListF -> LabeledField(field.label) { StringListEditor(field.values, codeFont) }
        is FieldState.TableF -> TableListEditor(field, codeFont)
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun BoxedTextField(state: MutableState<String>, codeFont: FontFamily, numeric: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = state.value,
            onValueChange = { state.value = if (numeric) it.filter { c -> c.isDigit() } else it },
            singleLine = true,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary, fontFamily = codeFont),
            cursorBrush = SolidColor(Ca.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToggleSwitch(on: Boolean, onToggle: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (on) Ca.colors.accent else Ca.colors.surface3, tween(Motion.FAST), label = "switchBg")
    val align = if (on) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).background(bg, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null) { onToggle(!on) }.padding(3.dp),
        contentAlignment = align,
    ) {
        Box(Modifier.size(20.dp).background(Ca.colors.textOnAccent, RoundedCornerShape(Ca.radius.pill)))
    }
}

@Composable
private fun StringListEditor(values: SnapshotStateList<String>, codeFont: FontFamily) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { i, v ->
            Row(
                Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm)).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(v, color = Ca.colors.textPrimary, style = Ca.type.caption.copy(fontFamily = codeFont), modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButtonCa(CaIcons.close, "Remove", { values.removeAt(i) }, boxSize = 24, iconSize = 14, tint = Ca.colors.textTertiary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
                    .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.sm)).padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                if (draft.isEmpty()) Text("Add…", color = Ca.colors.textTertiary, style = Ca.type.caption)
                BasicTextField(draft, { draft = it }, singleLine = true,
                    textStyle = Ca.type.caption.copy(color = Ca.colors.textPrimary, fontFamily = codeFont),
                    cursorBrush = SolidColor(Ca.colors.accent), modifier = Modifier.fillMaxWidth())
            }
            IconButtonCa(CaIcons.plus, "Add", { if (draft.isNotBlank()) { values.add(draft.trim()); draft = "" } }, boxSize = 30, iconSize = 16, active = true)
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
        Text(field.label, color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
        if (field.rows.isEmpty()) Text("No ${singular}s yet — add one below.", color = Ca.colors.textTertiary, style = Ca.type.caption2)
        field.rows.forEachIndexed { i, row ->
            val isNew = i == justAdded
            val borderColor by animateColorAsState(if (isNew) Ca.colors.accent else Ca.colors.hairline, tween(Motion.SLOW), label = "newRowBorder")
            Column(
                Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md))
                    .border(if (isNew) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(Ca.radius.md)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rowTitle(row, i), color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (isNew) Chip("new", fill = Ca.colors.accentSoft, textColor = Ca.colors.accent)
                    IconButtonCa(CaIcons.close, "Remove", { val at = i; field.rows.removeAt(at); if (justAdded == at) justAdded = -1 }, boxSize = 24, iconSize = 14, tint = Ca.colors.textTertiary)
                }
                row.forEach { FieldEditor(it, codeFont) }
            }
        }
        AddRowButton("Add $singular") { field.rows.add(cloneTemplateRow(field)); justAdded = field.rows.lastIndex }
    }
}

/** A full-width, clearly-labelled add button for the inline-table editor (build types / product flavors). */
@Composable
private fun AddRowButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.md))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CaIcons.plus, null, Modifier.size(16.dp), tint = Ca.colors.accent)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Ca.colors.accent, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ca.colors.accent else Ca.colors.surface2, tween(Motion.FAST), label = "levelBg")
    Box(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.pill)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Ca.colors.textOnAccent else Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
    }
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
                Modifier.background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.pill))
                    .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (t?.error == true) CaIcons.warning else CaIcons.check, null, Modifier.size(16.dp), tint = if (t?.error == true) Ca.colors.error else Ca.colors.run)
                Text(t?.text ?: "", color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
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

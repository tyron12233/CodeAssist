package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiSourceSetInfo
import dev.ide.ui.components.AddSourceRootDialog
import dev.ide.ui.components.AddSourceRootRequest
import dev.ide.ui.components.Chip
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Width at/above which the screen uses the desktop two-pane layout (module list pane + content). */
private val CONFIG_EXPANDED_BREAKPOINT = 820.dp

private data class ConfigToast(val text: String, val error: Boolean)

/**
 * The **Module Settings** editor: configure a module's Java version and its facet settings (Android
 * namespace/SDK/build types, …). Facet panels are generic — each field is rendered from the
 * model-API-driven [UiConfigField] the backend derived from the facet codec, so a new facet (or a new
 * field on an existing one) appears here automatically. Persists via [IdeBackend.updateModuleConfig].
 * Adaptive: a two-pane desktop layout, a chip switcher on phone. Talks only to [IdeBackend].
 */
@Composable
fun ModuleConfigScreen(
    backend: IdeBackend,
    initialModule: String?,
    onBack: () -> Unit,
    codeFont: FontFamily = FontFamily.Monospace,
) {
    val modules = remember { backend.configurableModules() }
    var selected by remember { mutableStateOf(initialModule ?: modules.firstOrNull()?.name) }
    var config by remember { mutableStateOf<UiModuleConfig?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<ConfigToast?>(null) }
    var addRootOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Add/remove typed content roots, then reload the form so the change shows.
    val onAddSourceRoot: () -> Unit = { addRootOpen = true }
    val onRemoveSourceRoot: (String, String) -> Unit = { set, root ->
        val module = selected
        if (module != null && backend.removeSourceRoot(module, set, root)) reloadKey++
    }

    LaunchedEffect(selected, reloadKey) {
        val module = selected ?: return@LaunchedEffect
        loading = true
        config = runCatching { backend.getModuleConfig(module) }.getOrNull()
        loading = false
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        val expanded = maxWidth >= CONFIG_EXPANDED_BREAKPOINT
        Column(Modifier.fillMaxSize()) {
            ConfigHeader(onBack)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    GlassSurface(Modifier.width(280.dp).fillMaxHeight(), GlassMaterial.Regular) {
                        ModuleListPane(modules, selected) { selected = it }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
                    ConfigBody(config, loading, codeFont, Modifier.weight(1f).fillMaxHeight(), onAddSourceRoot, onRemoveSourceRoot) { edit ->
                        val module = selected ?: return@ConfigBody
                        scope.launch {
                            val r = backend.updateModuleConfig(module, edit)
                            toast = ConfigToast(r.message, error = !r.success)
                            if (r.success) reloadKey++
                        }
                    }
                }
            } else {
                if (modules.size > 1) ModuleSwitcherChips(modules, selected) { selected = it }
                ConfigBody(config, loading, codeFont, Modifier.weight(1f).fillMaxWidth(), onAddSourceRoot, onRemoveSourceRoot) { edit ->
                    val module = selected ?: return@ConfigBody
                    scope.launch {
                        val r = backend.updateModuleConfig(module, edit)
                        toast = ConfigToast(r.message, error = !r.success)
                        if (r.success) reloadKey++
                    }
                }
            }
        }
        AddSourceRootDialog(
            request = (selected.takeIf { addRootOpen })?.let { AddSourceRootRequest(it, backend.moduleSourceSets(it)) },
            onDismiss = { addRootOpen = false },
            onAdd = { module, set, dirName, role ->
                if (backend.addSourceRoot(module, set, dirName, role) != null) reloadKey++
            },
        )
        ConfigToastHost(toast, Modifier.align(Alignment.BottomCenter))
    }
}

// ---- header + switchers -------------------------------------------------------------------------

@Composable
private fun ConfigHeader(onBack: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), GlassMaterial.Regular) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack)
            Icon(CaIcons.gear, null, Modifier.size(20.dp), tint = Ca.colors.accent)
            Text("Module Settings", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModuleListPane(modules: List<UiModuleRef>, selected: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("MODULES", color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            items(modules, key = { it.name }) { m ->
                val isSel = m.name == selected
                val bg by animateColorAsState(if (isSel) Ca.colors.accentSoft else Color.Transparent, tween(Motion.FAST), label = "cfgModuleBg")
                Row(
                    Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { onSelect(m.name) }
                        .background(bg, RoundedCornerShape(Ca.radius.sm)).padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(CaIcons.layers, null, Modifier.size(18.dp), tint = if (isSel) Ca.colors.accent else Ca.colors.textSecondary)
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = if (isSel) Ca.colors.accent else Ca.colors.textPrimary, style = Ca.type.footnote,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.typeDisplay, color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleSwitcherChips(modules: List<UiModuleRef>, selected: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modules.forEach { m ->
            val isSel = m.name == selected
            val bg by animateColorAsState(if (isSel) Ca.colors.accentSoft else Ca.colors.surface2, tween(Motion.FAST), label = "cfgChipBg")
            Row(
                Modifier.background(bg, RoundedCornerShape(Ca.radius.pill))
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(m.name) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(CaIcons.layers, null, Modifier.size(14.dp), tint = if (isSel) Ca.colors.accent else Ca.colors.textSecondary)
                Text(m.name, color = if (isSel) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.footnote,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
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
    modifier: Modifier,
    onAddSourceRoot: () -> Unit,
    onRemoveSourceRoot: (sourceSet: String, rootPath: String) -> Unit,
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
            else -> ConfigForm(config, codeFont, onAddSourceRoot, onRemoveSourceRoot, onSave)
        }
    }
}

@Composable
private fun ConfigForm(
    config: UiModuleConfig,
    codeFont: FontFamily,
    onAddSourceRoot: () -> Unit,
    onRemoveSourceRoot: (sourceSet: String, rootPath: String) -> Unit,
    onSave: (UiModuleConfigEdit) -> Unit,
) {
    // Editable state, rebuilt whenever a fresh config is loaded (e.g. after a save).
    var level by remember(config) { mutableStateOf(config.languageLevel) }
    val forms = remember(config) { config.facets.map { it.toForm() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- General ----
        item("general") {
            SectionCard("General") {
                ReadOnlyRow("Module", config.name, codeFont)
                ReadOnlyRow("Type", config.typeDisplay, codeFont)
                ReadOnlyRow("Output", config.outputDir, codeFont)
                Spacer(Modifier.height(6.dp))
                Text("Java version", color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    config.languageLevels.forEach { lvl -> LevelChip(prettyLevel(lvl), lvl == level) { level = lvl } }
                }
            }
        }

        // ---- Source sets (add / remove typed roots) ----
        item("sourceSets") {
            SectionCard("Source sets", action = {
                IconButtonCa(CaIcons.plus, "Add source root", onClick = onAddSourceRoot, boxSize = 26, iconSize = 16)
            }) {
                if (config.sourceSets.isEmpty()) {
                    Text("No source sets yet.", color = Ca.colors.textTertiary, style = Ca.type.caption2)
                }
                config.sourceSets.forEach { ss -> SourceSetRow(ss, codeFont) { root -> onRemoveSourceRoot(ss.name, root) } }
            }
        }

        // ---- Facet panels (generic) ----
        items(forms, key = { it.table }) { form -> FacetPanel(form, codeFont) }

        item("save") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                PrimaryButton("Save changes", icon = CaIcons.check, onClick = {
                    onSave(UiModuleConfigEdit(
                        languageLevel = level,
                        facetValues = forms.associate { it.table to it.toValues() },
                    ))
                })
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String, codeFont: FontFamily) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Ca.colors.textSecondary, style = Ca.type.caption, modifier = Modifier.width(72.dp))
        Text(value, color = Ca.colors.textPrimary, style = Ca.type.footnote.copy(fontFamily = codeFont),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SourceSetRow(ss: UiSourceSetInfo, codeFont: FontFamily, onRemoveRoot: (rootPath: String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ss.name, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            Chip(ss.scope.lowercase(), fill = Ca.colors.accentSoft, textColor = Ca.colors.accent)
        }
        ss.roots.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r, color = Ca.colors.textTertiary, style = Ca.type.caption2.copy(fontFamily = codeFont),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButtonCa(CaIcons.close, "Remove $r", onClick = { onRemoveRoot(r) }, boxSize = 22, iconSize = 12)
            }
        }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButtonCa(CaIcons.plus, "Add ${field.label}", { field.rows.add(cloneTemplateRow(field)) }, boxSize = 28, iconSize = 15, active = true)
        }
        field.rows.forEachIndexed { i, row ->
            Column(
                Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rowTitle(row, i), color = Ca.colors.textPrimary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButtonCa(CaIcons.close, "Remove", { field.rows.removeAt(i) }, boxSize = 24, iconSize = 14, tint = Ca.colors.textTertiary)
                }
                row.forEach { FieldEditor(it, codeFont) }
            }
        }
        if (field.rows.isEmpty()) Text("None", color = Ca.colors.textTertiary, style = Ca.type.caption2)
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

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.CustomizationActions
import dev.ide.ui.backend.CustomizationService
import dev.ide.ui.backend.UiMacro
import dev.ide.ui.backend.UiSymbolKey
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.CaDropdownMenu
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.symbolbar_comment
import dev.ide.ui.generated.resources.symbolbar_duplicate_line
import dev.ide.ui.generated.resources.symbolbar_move_line_down
import dev.ide.ui.generated.resources.symbolbar_move_line_up
import dev.ide.ui.generated.resources.symbolbar_next_problem
import dev.ide.ui.generated.resources.symbolbar_tab
import dev.ide.ui.generated.resources.symed_add
import dev.ide.ui.generated.resources.symed_add_action
import dev.ide.ui.generated.resources.symed_add_macro
import dev.ide.ui.generated.resources.symed_delete
import dev.ide.ui.generated.resources.symed_edit_key
import dev.ide.ui.generated.resources.symed_key_action
import dev.ide.ui.generated.resources.symed_key_inserts
import dev.ide.ui.generated.resources.symed_key_label
import dev.ide.ui.generated.resources.symed_key_pinned
import dev.ide.ui.generated.resources.symed_macro_abbrev
import dev.ide.ui.generated.resources.symed_macro_desc
import dev.ide.ui.generated.resources.symed_macro_type
import dev.ide.ui.generated.resources.symed_macro_type_hint
import dev.ide.ui.generated.resources.symed_scope_instance
import dev.ide.ui.generated.resources.symed_scope_static
import dev.ide.ui.generated.resources.symed_variable
import dev.ide.ui.generated.resources.symed_macro_enabled
import dev.ide.ui.generated.resources.symed_macro_languages
import dev.ide.ui.generated.resources.symed_macro_preview
import dev.ide.ui.generated.resources.symed_macro_syntax
import dev.ide.ui.generated.resources.symed_macro_template
import dev.ide.ui.generated.resources.symed_macros_empty
import dev.ide.ui.generated.resources.symed_save
import dev.ide.ui.generated.resources.symed_tab_macros
import dev.ide.ui.generated.resources.symed_add_hint
import dev.ide.ui.generated.resources.symed_empty
import dev.ide.ui.generated.resources.symed_export
import dev.ide.ui.generated.resources.symed_exported
import dev.ide.ui.generated.resources.symed_import
import dev.ide.ui.generated.resources.symed_import_apply
import dev.ide.ui.generated.resources.symed_import_bad
import dev.ide.ui.generated.resources.symed_import_title
import dev.ide.ui.generated.resources.symed_insert_token
import dev.ide.ui.generated.resources.symed_move_down
import dev.ide.ui.generated.resources.symed_move_up
import dev.ide.ui.generated.resources.symed_pin
import dev.ide.ui.generated.resources.symed_presets
import dev.ide.ui.generated.resources.symed_unpin
import dev.ide.ui.generated.resources.symed_project_unavailable
import dev.ide.ui.generated.resources.symed_remove
import dev.ide.ui.generated.resources.symed_reset
import dev.ide.ui.generated.resources.symed_scope_global
import dev.ide.ui.generated.resources.symed_scope_hint_global
import dev.ide.ui.generated.resources.symed_scope_hint_project
import dev.ide.ui.generated.resources.symed_scope_project
import dev.ide.ui.generated.resources.symed_suggest
import dev.ide.ui.generated.resources.symed_symbols
import dev.ide.ui.generated.resources.symed_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.DropdownMenuItem

/**
 * The Symbols &amp; Macros editor — a bottom sheet opened from the keyboard symbol bar's gear key (hosted in
 * [dev.ide.ui.screens.EditorScreen] so it overlays both layouts). Phase 1 edits the keyboard symbol bar at a
 * chosen scope (Global / This project): add, remove, reorder, reset to defaults, apply a preset, suggest keys
 * from the active file, and copy/paste the set as JSON. Every change auto-saves to the selected scope and
 * refreshes the live bar via [IdeUiState.refreshSymbolKeys]. (Macros land in the next phase.)
 */
@Composable
internal fun SymbolMacroEditor(state: IdeUiState) {
    val dismiss = { state.symbolEditorOpen = false }
    // The bottom sheet has no app bar, so it keeps the in-body header (title + close).
    BottomSheet(visible = state.symbolEditorOpen, onDismiss = dismiss, heightFraction = 0.82f) {
        SymbolMacroEditorBody(state, active = state.symbolEditorOpen, showHeader = true, onClose = dismiss, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

/** Full-screen host of the editor — the Settings ▸ Symbols &amp; Macros destination (reachable with or without
 *  a project open; the project scope is simply disabled when none is). Uses the standard settings app bar with
 *  a back button (like Code Style etc.), so the body drops its own header. Shares the body with the bar's sheet. */
@Composable
internal fun SymbolMacroEditorScreen(state: IdeUiState, onBack: () -> Unit) {
    ExpressiveScaffold(title = stringResource(Res.string.symed_title), onBack = onBack) { innerPadding ->
        SymbolMacroEditorBody(state, active = true, showHeader = false, onClose = onBack, modifier = Modifier.padding(innerPadding))
    }
}

private enum class SymEditorTab { Symbols, Macros }

/** The macro being added/edited: [index] is its position in the scope's list, or null when adding a new one. */
private data class MacroEdit(val index: Int?, val macro: UiMacro)

/** The symbol/action key being edited (by its [index] in the list). */
private data class SymbolEdit(val index: Int, val key: UiSymbolKey)

@Composable
private fun SymbolMacroEditorBody(
    state: IdeUiState,
    active: Boolean,
    showHeader: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backend = state.backend
    val projectAvailable = remember(active) { backend.customize.scopeAvailable(CustomizationService.PROJECT) }
    var tab by remember(active) { mutableStateOf(SymEditorTab.Symbols) }
    // Global is the default scope (my-symbols-everywhere); project is opt-in and only when one is open.
    var scope by remember(active) { mutableStateOf(CustomizationService.GLOBAL) }
    val items = remember(active) { mutableStateListOf<UiSymbolKey>() }
    val macros = remember(active) { mutableStateListOf<UiMacro>() }
    var adding by remember(active) { mutableStateOf("") }
    var importOpen by remember(active) { mutableStateOf(false) }
    var macroEdit by remember(active) { mutableStateOf<MacroEdit?>(null) }
    var symbolEdit by remember(active) { mutableStateOf<SymbolEdit?>(null) }
    var notice by remember(active) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    // The shipped built-in macros, shown alongside the user's own so they can be edited or disabled.
    val defaultMacros = remember(active) { backend.customize.defaultMacros() }
    val macroVars = remember(active) { backend.customize.macroVariables() }
    val uiScope = rememberCoroutineScope()
    val symbolListState = rememberLazyListState()
    val macroListState = rememberLazyListState()

    // (Re)seed both lists from the selected scope; symbols fall back to the current effective bar, and macros
    // merge the built-ins with the scope's own overrides (scope wins). Re-runs on scope change / (re)open.
    LaunchedEffect(active, scope) {
        if (active) {
            items.clear(); items.addAll(backend.customize.scopedSymbols(scope) ?: backend.customize.symbolKeys())
            macros.clear(); macros.addAll(mergeMacros(defaultMacros, backend.customize.scopedMacros(scope)))
        }
    }
    fun commitSymbols() { backend.customize.setScopedSymbols(scope, items.toList()); state.refreshSymbolKeys() }
    fun replaceSymbols(keys: List<UiSymbolKey>) { items.clear(); items.addAll(keys); commitSymbols() }
    // Persist only the entries that DIFFER from a built-in default (an unchanged built-in stays with its
    // backend; an edit/disable/new macro is written to the scope so the completion contributor applies it).
    fun commitMacros() {
        val byId = defaultMacros.associateBy { macroKey(it) }
        backend.customize.setScopedMacros(scope, macros.filter { byId[macroKey(it)] != it })
    }

    // A wrapping Box so the dialogs float ABOVE the content column. (Placing them as Column siblings starved
    // the weight(1f) list — and the dialogs themselves — of height, which is why "add" appeared to do nothing.)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // In-body header (sheet only — the full-screen host supplies its own app bar + back button).
            if (showHeader) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.symed_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.close, stringResource(Res.string.close), onClose)
            }

            // Symbols | Macros tab toggle.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabChip(stringResource(Res.string.symed_symbols), tab == SymEditorTab.Symbols) { tab = SymEditorTab.Symbols }
                TabChip(stringResource(Res.string.symed_tab_macros), tab == SymEditorTab.Macros) { tab = SymEditorTab.Macros }
            }

            // Scope segmented toggle + hint (shared by both tabs — a scope holds both symbols and macros).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeChip(stringResource(Res.string.symed_scope_global), scope == CustomizationService.GLOBAL, enabled = true) {
                    scope = CustomizationService.GLOBAL
                }
                ScopeChip(stringResource(Res.string.symed_scope_project), scope == CustomizationService.PROJECT, enabled = projectAvailable) {
                    scope = CustomizationService.PROJECT
                }
            }
            Text(
                when {
                    scope == CustomizationService.PROJECT && !projectAvailable -> stringResource(Res.string.symed_project_unavailable)
                    scope == CustomizationService.PROJECT -> stringResource(Res.string.symed_scope_hint_project)
                    else -> stringResource(Res.string.symed_scope_hint_global)
                },
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )

            if (tab == SymEditorTab.Symbols) {
                // Live preview of the bar exactly as configured (updates as keys are added / reordered / pinned).
                SymbolBarPreview(items)
                // Add-a-symbol row.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallTextField(
                        value = adding,
                        onValueChange = { adding = it.replace("\n", "") },
                        placeholder = stringResource(Res.string.symed_add_hint),
                        modifier = Modifier.weight(1f),
                    )
                    val add = {
                        val t = adding.trim()
                        if (t.isNotEmpty()) {
                            items.add(UiSymbolKey(t, t)); adding = ""; commitSymbols()
                            // Scroll the newly-added key into view (it lands at the end of a long list).
                            uiScope.launch { runCatching { symbolListState.animateScrollToItem(items.lastIndex) } }
                        }
                    }
                    ActionChip(stringResource(Res.string.symed_add), CaIcons.plus, onClick = add, accent = true)
                }
                // The editable key list (or an empty-state hint). Tapping a row opens its editor.
                if (items.isEmpty()) {
                    EmptyHint(stringResource(Res.string.symed_empty))
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        state = symbolListState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(items, key = { i, k -> "$i:${k.label}:${k.insert}:${k.action}" }) { index, key ->
                            SymbolRow(
                                key = key,
                                canUp = index > 0,
                                canDown = index < items.lastIndex,
                                onUp = { if (index > 0) { items.add(index - 1, items.removeAt(index)); commitSymbols() } },
                                onDown = { if (index < items.lastIndex) { items.add(index + 1, items.removeAt(index)); commitSymbols() } },
                                onTogglePin = { items[index] = items[index].copy(pinned = !items[index].pinned); commitSymbols() },
                                onEdit = { symbolEdit = SymbolEdit(index, key) },
                            )
                        }
                    }
                }
                // Symbol actions: reset · add-action · suggest · presets. FlowRow so they wrap onto another line
                // on a narrow screen instead of squishing (the "suggest from file" chip used to get crushed).
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip(stringResource(Res.string.symed_reset), CaIcons.refresh) { replaceSymbols(backend.customize.defaultSymbols()) }
                    AddActionChip(existing = items.mapNotNull { it.action }.toSet()) { action, label ->
                        items.add(UiSymbolKey(label = label, insert = "", pinned = true, action = action)); commitSymbols()
                    }
                    state.active?.path?.let { path ->
                        ActionChip(stringResource(Res.string.symed_suggest), CaIcons.sparkle) {
                            val extra = backend.customize.suggestSymbols(path, items.toList())
                            if (extra.isNotEmpty()) { items.addAll(extra); commitSymbols() }
                        }
                    }
                    PresetsChip { keys -> replaceSymbols(keys) }
                }
            } else {
                // Add-a-macro row.
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ActionChip(stringResource(Res.string.symed_add_macro), CaIcons.plus, accent = true) {
                        macroEdit = MacroEdit(null, UiMacro(abbreviation = "", template = ""))
                    }
                }
                if (macros.isEmpty()) {
                    EmptyHint(stringResource(Res.string.symed_macros_empty))
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        state = macroListState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val defaultsById = defaultMacros.associateBy { macroKey(it) }
                        itemsIndexed(macros, key = { i, m -> "$i:${m.abbreviation}:${m.languages}" }) { index, m ->
                            val default = defaultsById[macroKey(m)]
                            MacroRow(
                                macro = m,
                                // A built-in that's been changed from its shipped form; drives the reset affordance.
                                overridden = default != null && default != m,
                                onEdit = { macroEdit = MacroEdit(index, m) },
                                onToggle = { macros[index] = macros[index].copy(enabled = !macros[index].enabled); commitMacros() },
                                // Built-in → restore its default (reset); user macro → delete it.
                                onResetOrRemove = {
                                    if (default != null) macros[index] = default else macros.removeAt(index)
                                    commitMacros()
                                },
                            )
                        }
                    }
                }
            }

            // Shared footer: export / import the whole scope set (symbols + macros).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                val exported = stringResource(Res.string.symed_exported)
                IconButtonCa(CaIcons.copy, stringResource(Res.string.symed_export), onClick = {
                    clipboard.setText(AnnotatedString(backend.customize.exportScope(scope)))
                    notice = exported
                })
                IconButtonCa(CaIcons.download, stringResource(Res.string.symed_import), onClick = { importOpen = true })
            }
            notice?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                LaunchedEffect(it) { kotlinx.coroutines.delay(1800); notice = null }
            }
        }

        // --- Overlays (float above the content, so they aren't squeezed by the weight(1f) list) ---

        // Paste-JSON import dialog (reseeds both lists on success).
        ImportDialog(
            visible = importOpen,
            onDismiss = { importOpen = false },
            onImport = { text ->
                if (backend.customize.importScope(scope, text)) {
                    items.clear(); items.addAll(backend.customize.scopedSymbols(scope) ?: backend.customize.symbolKeys())
                    macros.clear(); macros.addAll(mergeMacros(defaultMacros, backend.customize.scopedMacros(scope)))
                    state.refreshSymbolKeys()
                    importOpen = false
                    true
                } else false
            },
        )

        // The symbol/action key editor (label, insert, pin, delete).
        symbolEdit?.let { edit ->
            SymbolEditDialog(
                edit = edit,
                onSave = { updated -> if (edit.index in items.indices) items[edit.index] = updated; commitSymbols(); symbolEdit = null },
                onDelete = { if (edit.index in items.indices) items.removeAt(edit.index); commitSymbols(); symbolEdit = null },
                onDismiss = { symbolEdit = null },
            )
        }

        // The macro add/edit dialog.
        macroEdit?.let { edit ->
            MacroEditDialog(
                edit = edit,
                preview = { backend.customize.previewMacro(it) },
                variables = macroVars,
                onDismiss = { macroEdit = null },
                onSave = { updated ->
                    if (edit.index == null) {
                        macros.add(updated)
                        uiScope.launch { runCatching { macroListState.animateScrollToItem(macros.lastIndex) } }
                    } else if (edit.index in macros.indices) {
                        macros[edit.index] = updated
                    }
                    commitMacros()
                    macroEdit = null
                },
            )
        }
    }
}

/** A weight(1f) centered empty-state hint used by both tabs' lists. */
@Composable
private fun ColumnScope.EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A live preview of the keyboard bar as configured — the real [EditorSymbolBar] rendered read-only over the
 *  current [symbols], so edits (add / reorder / pin) are visible immediately. */
@Composable
private fun SymbolBarPreview(symbols: List<UiSymbolKey>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        FieldLabel(stringResource(Res.string.symed_macro_preview))
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(Ca.radius.sm))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm)),
        ) {
            EditorSymbolBar(symbols = symbols, onSymbol = {}, onAction = {}, showDiagnosticJump = false, onCustomize = null)
        }
    }
}

/** The Symbols / Macros tab pill. */
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** One macro row: the abbreviation + a built-in/language tag, a description/first-line subtitle, an enabled
 *  toggle, and a trailing action — **reset** (restore the shipped default) for a built-in, **remove** for a
 *  user macro. Tapping the row opens the editor. [overridden] is a changed built-in (its reset is meaningful). */
@Composable
private fun MacroRow(macro: UiMacro, overridden: Boolean, onEdit: () -> Unit, onToggle: () -> Unit, onResetOrRemove: () -> Unit) {
    val dim = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onEdit).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    macro.abbreviation,
                    color = if (macro.enabled) MaterialTheme.colorScheme.onSurface else dim,
                    style = Ide.type.codeSmall, fontWeight = FontWeight.SemiBold,
                )
                val tag = buildList {
                    if (macro.languages.isNotEmpty()) add(macro.languages.joinToString(", "))
                    macro.receiverType?.takeIf { it.isNotBlank() }?.let {
                        add(it.substringAfterLast('.') + if (macro.static) " (static)" else "")
                    }
                    if (overridden) add("edited")
                }.joinToString(" · ")
                if (tag.isNotEmpty()) Text(
                    tag, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                )
            }
            val sub = macro.description.ifBlank { macro.template.lineSequence().firstOrNull().orEmpty() }
            if (sub.isNotBlank()) Text(
                sub, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButtonCa(
            CaIcons.check, stringResource(Res.string.symed_macro_enabled), onToggle, boxSize = 30, iconSize = 16,
            tint = if (macro.enabled) MaterialTheme.colorScheme.primary else dim,
        )
        // Built-ins can't be deleted (they'd reappear) — the action resets them; user macros are removed.
        if (macro.builtIn) {
            IconButtonCa(CaIcons.refresh, stringResource(Res.string.symed_reset), onResetOrRemove, boxSize = 30, iconSize = 16, tint = if (overridden) null else dim)
        } else {
            IconButtonCa(CaIcons.close, stringResource(Res.string.symed_remove), onResetOrRemove, boxSize = 30, iconSize = 16)
        }
    }
}

/** Identity of a macro for merging built-ins with a scope's overrides: abbreviation + its language set (so the
 *  same abbreviation in different languages — Java vs Kotlin `ife` — stays distinct). */
private fun macroKey(m: UiMacro): String = m.abbreviation + " " + m.languages.sorted().joinToString(",")

/** The built-in macros overlaid by the scope's own entries (scope wins on the same [macroKey]) — the editor's
 *  display list. Built-ins the scope hasn't touched keep `builtIn = true` and equal their default. */
private fun mergeMacros(defaults: List<UiMacro>, scoped: List<UiMacro>): List<UiMacro> {
    val byId = LinkedHashMap<String, UiMacro>()
    for (d in defaults) byId[macroKey(d)] = d
    for (s in scoped) byId[macroKey(s)] = s
    return byId.values.toList()
}

/** The add/edit-a-macro dialog: abbreviation, multi-line template, description, per-language chips, and a live
 *  preview of the expansion (placeholders shown as their defaults, `$VAR$` filled with sample values). */
@Composable
private fun MacroEditDialog(
    edit: MacroEdit,
    preview: (String) -> String,
    variables: List<String>,
    onDismiss: () -> Unit,
    onSave: (UiMacro) -> Unit,
) {
    var abbrev by remember(edit) { mutableStateOf(edit.macro.abbreviation) }
    var template by remember(edit) { mutableStateOf(edit.macro.template) }
    var desc by remember(edit) { mutableStateOf(edit.macro.description) }
    var langs by remember(edit) { mutableStateOf(edit.macro.languages.toSet()) }
    var receiverType by remember(edit) { mutableStateOf(edit.macro.receiverType ?: "") }
    var static by remember(edit) { mutableStateOf(edit.macro.static) }
    // Token highlighter for the template field (theme colors captured here; the transform itself isn't @Composable).
    val stopColor = MaterialTheme.colorScheme.primary
    val varColor = MaterialTheme.colorScheme.tertiary
    val endColor = Ide.colors.success
    val litColor = MaterialTheme.colorScheme.outline
    val highlight = remember(stopColor, varColor, endColor, litColor) { SnippetHighlight(stopColor, varColor, endColor, litColor) }
    // Consume scroll deltas so scrolling the dialog doesn't leak to the screen/app-bar behind it.
    val blockScroll = remember { BlockParentScroll() }
    CenteredDialog(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.width(380.dp).clip(RoundedCornerShape(Ca.radius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .heightIn(max = 600.dp).nestedScroll(blockScroll).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(Res.string.symed_add_macro),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FieldLabel(stringResource(Res.string.symed_macro_abbrev))
            SmallTextField(abbrev, { abbrev = it.replace("\n", "").trim() }, "sout", Modifier.fillMaxWidth())
            FieldLabel(stringResource(Res.string.symed_macro_template))
            SmallTextField(
                template, { template = it }, "System.out.println(\$END\$);",
                Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp), singleLine = false,
                visualTransformation = highlight, codeStyle = true,
            )
            Text(stringResource(Res.string.symed_macro_syntax), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
            // Insert row: the few tab-stop tokens as chips, plus a compact Variable dropdown (the variable list
            // is long, so a menu reads far better than a wall of chips). All append to the template.
            FieldLabel(stringResource(Res.string.symed_insert_token))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TokenChip("\$1", stopColor, varColor, endColor) { template += "\$1" }
                TokenChip("\$2", stopColor, varColor, endColor) { template += "\$2" }
                TokenChip("\$END\$", stopColor, varColor, endColor) { template += "\$END\$" }
                VariableMenu(variables, preview, varColor) { name -> template += "\$$name\$" }
            }
            FieldLabel(stringResource(Res.string.symed_macro_desc))
            SmallTextField(desc, { desc = it.replace("\n", "") }, "", Modifier.fillMaxWidth())
            FieldLabel(stringResource(Res.string.symed_macro_languages))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LANG_OPTIONS.forEach { (id, label) ->
                    LangChip(label, id in langs) { langs = if (id in langs) langs - id else langs + id }
                }
            }
            // Type scope (optional): when a type is set the macro is offered only after a matching receiver.
            FieldLabel(stringResource(Res.string.symed_macro_type))
            SmallTextField(receiverType, { receiverType = it.replace("\n", "").trim() }, "java.lang.String", Modifier.fillMaxWidth())
            if (receiverType.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LangChip(stringResource(Res.string.symed_scope_instance), !static) { static = false }
                    LangChip(stringResource(Res.string.symed_scope_static), static) { static = true }
                }
                Text(stringResource(Res.string.symed_macro_type_hint), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
            }
            FieldLabel(stringResource(Res.string.symed_macro_preview))
            val previewText = remember(template) { preview(template) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm))
                    .padding(10.dp),
            ) {
                Text(previewText.ifBlank { " " }, color = MaterialTheme.colorScheme.onSurface, style = Ide.type.codeSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                ActionChip(stringResource(Res.string.cancel), CaIcons.close, onClick = onDismiss)
                val canSave = abbrev.isNotBlank() && template.isNotBlank()
                ActionChip(stringResource(Res.string.symed_save), CaIcons.check, accent = canSave) {
                    if (canSave) onSave(
                        edit.macro.copy(
                            abbreviation = abbrev, template = template, description = desc, languages = langs.toList(),
                            receiverType = receiverType.ifBlank { null }, static = static,
                        ),
                    )
                }
            }
        }
    }
}

/** The compact "Variable ▾" dropdown for the macro editor — a menu of every template variable with its sample
 *  value, so a long list is scannable (vs. a wall of chips). Picking one appends `$NAME$` to the template. */
@Composable
private fun VariableMenu(variables: List<String>, preview: (String) -> String, color: Color, onInsert: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(color.copy(alpha = 0.12f))
                .clickable { open = true }.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(Res.string.symed_variable), color = color, style = Ide.type.codeSmall, fontWeight = FontWeight.Medium)
            Icon(CaIcons.caretDown, null, Modifier.size(14.dp), tint = color)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Box(Modifier.heightIn(max = 320.dp)) {
                Column {
                    variables.forEach { v ->
                        val sample = remember(v) { preview("\$$v\$") }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("\$$v\$", color = color, style = Ide.type.codeSmall, fontWeight = FontWeight.SemiBold)
                                    if (sample.isNotBlank()) Text(sample, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            onClick = { open = false; onInsert(v) },
                        )
                    }
                }
            }
        }
    }
}

/** A nested-scroll connection that swallows the scroll leftover so a scrollable dialog doesn't scroll the
 *  content (or collapse the app bar) behind it. */
private class BlockParentScroll : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(
        consumed: androidx.compose.ui.geometry.Offset,
        available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
    ): androidx.compose.ui.geometry.Offset = available

    override suspend fun onPostFling(
        consumed: androidx.compose.ui.unit.Velocity,
        available: androidx.compose.ui.unit.Velocity,
    ): androidx.compose.ui.unit.Velocity = available
}

/** An insertable snippet-token chip (`$1` / `$END$` / `$FILE$` …), colored by its kind to match the template
 *  field's highlighting. Tapping appends the token to the template. */
@Composable
private fun TokenChip(token: String, stop: Color, variable: Color, end: Color, onClick: () -> Unit) {
    val color = when {
        token == "\$END\$" -> end
        token.length >= 2 && token[1].isDigit() -> stop
        else -> variable
    }
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill))
            .background(color.copy(alpha = 0.12f)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(token, color = color, style = Ide.type.codeSmall, fontWeight = FontWeight.Medium)
    }
}

/** A small selectable language chip in the macro editor. */
@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** A small field caption above an input in the macro dialog. */
@Composable
private fun FieldLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
}

/** The languages the macro editor offers (id → display label). Empty selection = every language. */
private val LANG_OPTIONS: List<Pair<String, String>> = listOf("java" to "Java", "kotlin" to "Kotlin", "xml" to "XML")

/** A preset menu chip: replaces the current list with a shipped starter set (default / Kotlin / XML). */
@Composable
private fun PresetsChip(onPick: (List<UiSymbolKey>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionChip(stringResource(Res.string.symed_presets), CaIcons.layers) { open = true }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PRESETS.forEach { (name, keys) ->
                DropdownMenuItem(
                    text = { Text(name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; onPick(keys.map { UiSymbolKey(it, it) }) },
                )
            }
        }
    }
}

/** One editable key row: the label + a subtitle (its insert, or the action name), reorder (up/down) and pin
 *  controls, and — tapping the label area — opens the key editor (label / insert / delete). Action keys are
 *  marked so they read distinctly from inserted symbols. */
@Composable
private fun SymbolRow(
    key: UiSymbolKey,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val isAction = key.action != null
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Ca.radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tapping the label area edits the key (label / insert / delete).
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(Ca.radius.sm)).clickable(onClick = onEdit)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                key.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = if (isAction) MaterialTheme.typography.bodyMedium else Ide.type.codeSmall,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = when {
                isAction -> actionLabel(key.action!!)
                key.insert != key.label -> key.insert
                else -> null
            }
            if (subtitle != null) Text(
                subtitle, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // Pin toggle: accent when pinned (in the fixed row), dim when it scrolls.
        IconButtonCa(
            CaIcons.pin,
            if (key.pinned) stringResource(Res.string.symed_unpin) else stringResource(Res.string.symed_pin),
            onTogglePin, boxSize = 30, iconSize = 16,
            tint = if (key.pinned) MaterialTheme.colorScheme.primary else dim,
        )
        IconButtonCa(CaIcons.chevronUp, stringResource(Res.string.symed_move_up), onUp, boxSize = 30, iconSize = 16, tint = if (canUp) null else dim)
        IconButtonCa(CaIcons.chevronDown, stringResource(Res.string.symed_move_down), onDown, boxSize = 30, iconSize = 16, tint = if (canDown) null else dim)
    }
}

/** The symbol/action key editor: rename the [label] (what shows on the key), set what it [inserts] (a text key
 *  only — an action key shows its bound action read-only), toggle **pinned**, and **delete**. */
@Composable
private fun SymbolEditDialog(edit: SymbolEdit, onSave: (UiSymbolKey) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var label by remember(edit) { mutableStateOf(edit.key.label) }
    var insert by remember(edit) { mutableStateOf(edit.key.insert) }
    var pinned by remember(edit) { mutableStateOf(edit.key.pinned) }
    val isAction = edit.key.action != null
    CenteredDialog(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.width(360.dp).clip(RoundedCornerShape(Ca.radius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.symed_edit_key),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FieldLabel(stringResource(Res.string.symed_key_label))
            SmallTextField(label, { label = it.replace("\n", "") }, "{", Modifier.fillMaxWidth())
            if (isAction) {
                FieldLabel(stringResource(Res.string.symed_key_action))
                Text(
                    actionLabel(edit.key.action!!),
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                FieldLabel(stringResource(Res.string.symed_key_inserts))
                SmallTextField(insert, { insert = it.replace("\n", "") }, label.ifBlank { "{" }, Modifier.fillMaxWidth())
            }
            LangChip(stringResource(Res.string.symed_key_pinned), pinned) { pinned = !pinned }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionChip(stringResource(Res.string.symed_delete), CaIcons.close, onClick = onDelete)
                Spacer(Modifier.weight(1f))
                ActionChip(stringResource(Res.string.cancel), CaIcons.close, onClick = onDismiss)
                val canSave = label.isNotBlank()
                ActionChip(stringResource(Res.string.symed_save), CaIcons.check, accent = canSave) {
                    if (canSave) onSave(edit.key.copy(label = label, insert = if (isAction) edit.key.insert else insert.ifBlank { label }, pinned = pinned))
                }
            }
        }
    }
}

/** An "add action key" menu: lists the built-in editor actions not already present; picking one adds it as a
 *  pinned action key. [onAdd] receives the action id and its localized label. */
@Composable
private fun AddActionChip(existing: Set<String>, onAdd: (action: String, label: String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val addable = CustomizationActions.ALL.filter { it !in existing }
    if (addable.isEmpty()) return
    Box {
        ActionChip(stringResource(Res.string.symed_add_action), CaIcons.plus) { open = true }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            addable.forEach { action ->
                val label = actionLabel(action)
                DropdownMenuItem(
                    text = { Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; onAdd(action, label) },
                )
            }
        }
    }
}

/** The localized display label for a built-in editor action id (reuses the symbol-bar strings). */
@Composable
private fun actionLabel(action: String): String = when (action) {
    CustomizationActions.TAB -> stringResource(Res.string.symbolbar_tab)
    CustomizationActions.COMMENT -> stringResource(Res.string.symbolbar_comment)
    CustomizationActions.MOVE_LINE_UP -> stringResource(Res.string.symbolbar_move_line_up)
    CustomizationActions.MOVE_LINE_DOWN -> stringResource(Res.string.symbolbar_move_line_down)
    CustomizationActions.DUPLICATE_LINE -> stringResource(Res.string.symbolbar_duplicate_line)
    CustomizationActions.NEXT_PROBLEM -> stringResource(Res.string.symbolbar_next_problem)
    else -> action
}

/** The paste-a-JSON import dialog: a multiline field + Import/Cancel; shows an error if the payload is bad. */
@Composable
private fun ImportDialog(visible: Boolean, onDismiss: () -> Unit, onImport: (String) -> Boolean) {
    var text by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf(false) }
    CenteredDialog(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier.width(360.dp)
                .clip(RoundedCornerShape(Ca.radius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.symed_import_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            SmallTextField(
                value = text,
                onValueChange = { text = it; error = false },
                placeholder = "{ … }",
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 200.dp),
            )
            if (error) Text(
                stringResource(Res.string.symed_import_bad),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                ActionChip(stringResource(Res.string.cancel), CaIcons.close, onClick = onDismiss)
                ActionChip(stringResource(Res.string.symed_import_apply), CaIcons.download, accent = true) {
                    if (!onImport(text)) error = true
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Boolean = false, onClick: () -> Unit) {
    val fg = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill))
            .background(if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** A minimal bordered text input (BasicTextField in a box) — matches the app's field style without a Material
 *  TextField's chrome. Single-line unless [singleLine] is false. */
@Composable
private fun SmallTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    codeStyle: Boolean = false,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Ca.radius.sm))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(
            placeholder, color = MaterialTheme.colorScheme.outline,
            style = if (codeStyle) Ide.type.codeSmall else MaterialTheme.typography.bodyMedium,
        )
        val base = if (codeStyle) Ide.type.codeSmall else MaterialTheme.typography.bodyMedium
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = base.copy(color = MaterialTheme.colorScheme.onSurface),
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Colors the snippet tokens in a template field: `$1`/`$2` tab stops, `${1:default}` placeholders, `$END$`
 *  final caret, and `$NAME$` variables — so the syntax is legible at a glance. Identity offset mapping (it only
 *  styles; the text is unchanged). */
private class SnippetHighlight(
    private val stop: Color,
    private val variable: Color,
    private val end: Color,
    private val literal: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        fun span(color: Color, start: Int, stop2: Int) {
            if (stop2 > start) spans.add(AnnotatedString.Range(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), start, stop2))
        }
        var i = 0
        while (i < s.length) {
            if (s[i] != '$') { i++; continue }
            if (i + 1 < s.length && s[i + 1] == '$') { span(literal, i, i + 2); i += 2; continue } // $$ literal
            if (i + 1 < s.length && s[i + 1] == '{') { // ${...}
                var depth = 0; var j = i + 1
                while (j < s.length) { if (s[j] == '{') depth++ else if (s[j] == '}') { depth--; if (depth == 0) break }; j++ }
                if (j < s.length) {
                    val digit = s.getOrNull(i + 2)?.isDigit() == true
                    span(if (digit) stop else variable, i, j + 1); i = j + 1; continue
                }
                i++; continue
            }
            if (i + 1 < s.length && s[i + 1].isDigit()) { // $N
                var j = i + 1; while (j < s.length && s[j].isDigit()) j++
                span(if (s.substring(i + 1, j) == "0") end else stop, i, j); i = j; continue
            }
            if (i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_')) { // $NAME$ / $NAME
                var j = i + 1; while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                val name = s.substring(i + 1, j)
                val e = if (j < s.length && s[j] == '$') j + 1 else j
                span(if (name == "END") end else variable, i, e); i = e; continue
            }
            i++
        }
        return TransformedText(AnnotatedString(s, spans), OffsetMapping.Identity)
    }
}

// Starter presets the Presets chip offers; each replaces the current list. Names are short and not localized
// (they name character sets, not UI actions).
private val PRESETS: List<Pair<String, List<String>>> = listOf(
    "Java / Kotlin" to listOf("{", "}", "(", ")", ";", "=", ".", ",", "\"", "'", ":", "<", ">", "/", "*", "[", "]", "+", "-", "&", "|", "!", "?", "@", "_"),
    "Kotlin extras" to listOf("->", "?.", "?:", "::", "..", "==", "!=", "&&", "||", "{", "}", "(", ")", "=", ".", ",", "\"", "$", "<", ">"),
    "XML" to listOf("<", ">", "/", "=", "\"", "'", ":", "@", "+", ".", "-", "_", "?", "#"),
)

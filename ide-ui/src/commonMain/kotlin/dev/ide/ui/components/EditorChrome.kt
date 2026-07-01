package dev.ide.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.UiActionItem
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.theme.Ca

/**
 * Top bar (glass-regular), pared back to the essentials: sidebar toggle · project name · index status ·
 * save · command palette · console · Run. The project tile lives in the side rail; theme toggle and
 * re-index moved to the command palette (and rail Settings) — they were redundant here.
 *
 * Responsive: the project name takes the flexible middle (truncating instead of pushing the right cluster
 * off-screen), and in [compact] width the Run button collapses to an icon and the idle index chip hides —
 * so the controls never squeeze on a phone.
 */
@Composable
fun EditorTopBar(
    projectName: String,
    indexStatus: IndexUiStatus,
    onToggleNav: () -> Unit,
    onOpenPalette: () -> Unit,
    runTasks: () -> List<RunTaskOption> = { emptyList() },
    onPickTask: (RunTaskOption) -> Unit = {},
    /** The active build variant to show in the switcher chip, or null to hide it (non-Android project). */
    activeVariant: String? = null,
    variants: () -> List<String> = { emptyList() },
    onPickVariant: (String) -> Unit = {},
    onSave: () -> Unit = {},
    hasUnsavedChanges: Boolean = false,
    hasActiveFile: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onFind: () -> Unit = {},
    onReformat: () -> Unit = {},
    onToggleConsole: () -> Unit = {},
    consoleOpen: Boolean = false,
    inlayHintsOn: Boolean = true,
    onToggleInlayHints: () -> Unit = {},
    showPreview: Boolean = false,
    onPreview: () -> Unit = {},
    previewBusy: Boolean = false,
    onIndexClick: () -> Unit = {},
    /** Plugin-contributed toolbar actions (the `mainToolbar` place), rendered just before Run. Empty by
     *  default — built-in chrome stays native; this is the seam a plugin adds a button through. */
    pluginActions: List<UiActionItem> = emptyList(),
    onPluginAction: (String) -> Unit = {},
    compact: Boolean = false,
) {
    val dim = Ca.colors.textTertiary.copy(alpha = 0.35f)
    GlassSurface(modifier = Modifier.fillMaxWidth().height(52.dp), material = GlassMaterial.Regular) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            IconButtonCa(CaIcons.sidebar, "Toggle navigator", onToggleNav)
            // The name takes the flexible middle and truncates — the right-hand cluster keeps its size.
            Text(
                projectName, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IndexStatusChip(indexStatus, compact = compact, onClick = onIndexClick)
            // Accent-tinted while there are unsaved changes; saves the active tab (Cmd/Ctrl-S also works).
            IconButtonCa(CaIcons.save, "Save", onSave, active = hasUnsavedChanges)
            if (compact) {
                // On a phone the bar can't hold every control, so Run stays inline and the rest (incl. the
                // edit actions) collapse into a single ⋯ overflow menu — everything one tap away.
                PluginToolbarActions(pluginActions, dim, onPluginAction)
                if (activeVariant != null) VariantChip(activeVariant, variants, onPickVariant, compact = true)
                RunControl(runTasks, onPickTask, compact = true)
                EditorOverflowMenu(
                    onOpenPalette = onOpenPalette,
                    hasActiveFile = hasActiveFile,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onFind = onFind,
                    onReformat = onReformat,
                    inlayHintsOn = inlayHintsOn,
                    onToggleInlayHints = onToggleInlayHints,
                    consoleOpen = consoleOpen,
                    onToggleConsole = onToggleConsole,
                    showPreview = showPreview,
                    onPreview = onPreview,
                )
            } else {
                // Edit actions (undo/redo/find) sit just before Run — disabled-tinted with no file open.
                if (hasActiveFile) {
                    IconButtonCa(CaIcons.undo, "Undo", onUndo, tint = if (canUndo) null else dim)
                    IconButtonCa(CaIcons.redo, "Redo", onRedo, tint = if (canRedo) null else dim)
                    IconButtonCa(CaIcons.search, "Find / replace", onFind)
                    IconButtonCa(CaIcons.braces, "Reformat code", onReformat)
                }
                IconButtonCa(CaIcons.command, "Command palette", onOpenPalette)
                IconButtonCa(CaIcons.eye, "Toggle inlay hints", onToggleInlayHints, active = inlayHintsOn)
                IconButtonCa(CaIcons.terminal, "Build console", onToggleConsole, active = consoleOpen)
                // Shown when the open file has @Preview composables — renders/checks them via the interpreter.
                if (showPreview) IconButtonCa(CaIcons.image, "Compose preview", onPreview, active = previewBusy)
                PluginToolbarActions(pluginActions, dim, onPluginAction)
                if (activeVariant != null) VariantChip(activeVariant, variants, onPickVariant, compact = false)
                RunControl(runTasks, onPickTask, compact = false)
            }
        }
    }
}

/** Renders the plugin-contributed toolbar actions (the `mainToolbar` action place). Disabled actions are
 *  tinted muted; clicking routes the action id back to the host, which runs it through the registry. */
@Composable
private fun PluginToolbarActions(actions: List<UiActionItem>, dim: Color, onAction: (String) -> Unit) {
    actions.forEach { a ->
        IconButtonCa(
            actionIcon(a.iconId),
            a.text,
            onClick = { if (a.enabled) onAction(a.id) },
            tint = if (a.enabled) null else dim,
        )
    }
}

/**
 * The compact-bar overflow: a ⋯ button opening a dropdown of the secondary controls that don't fit inline on
 * a phone (command palette, inlay-hint toggle, build console, Compose preview). Toggled items show their
 * on-state in the accent colour.
 */
@Composable
private fun EditorOverflowMenu(
    onOpenPalette: () -> Unit,
    hasActiveFile: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onReformat: () -> Unit,
    inlayHintsOn: Boolean,
    onToggleInlayHints: () -> Unit,
    consoleOpen: Boolean,
    onToggleConsole: () -> Unit,
    showPreview: Boolean,
    onPreview: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButtonCa(CaIcons.ellipsis, "More actions", { open = true })
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasActiveFile) {
                OverflowItem(CaIcons.undo, "Undo", enabled = canUndo) { open = false; onUndo() }
                OverflowItem(CaIcons.redo, "Redo", enabled = canRedo) { open = false; onRedo() }
                OverflowItem(CaIcons.search, "Find / replace") { open = false; onFind() }
                OverflowItem(CaIcons.braces, "Reformat code") { open = false; onReformat() }
            }
            OverflowItem(CaIcons.command, "Command palette") { open = false; onOpenPalette() }
            OverflowItem(
                CaIcons.eye, if (inlayHintsOn) "Hide inlay hints" else "Show inlay hints", active = inlayHintsOn,
            ) { open = false; onToggleInlayHints() }
            OverflowItem(CaIcons.terminal, "Build console", active = consoleOpen) { open = false; onToggleConsole() }
            if (showPreview) OverflowItem(CaIcons.image, "Compose preview") { open = false; onPreview() }
        }
    }
}

@Composable
private fun OverflowItem(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val textColor = when {
        !enabled -> Ca.colors.textTertiary.copy(alpha = 0.4f)
        active -> Ca.colors.accent
        else -> Ca.colors.textPrimary
    }
    val tint = when {
        !enabled -> Ca.colors.textTertiary.copy(alpha = 0.4f)
        active -> Ca.colors.accent
        else -> Ca.colors.textSecondary
    }
    DropdownMenuItem(
        text = { Text(label, color = textColor, style = Ca.type.footnote, fontWeight = FontWeight.Medium) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = tint) },
        onClick = onClick,
        enabled = enabled,
    )
}

/**
 * A thin strip under the top bar driven by the shared `depsState`. While resolution runs it shows progress
 * (a newly-created project's template deps, an add, or a Retry); when resolution is idle but declared
 * dependencies are still unresolved it shows a persistent error banner with the reason + a Retry action —
 * the project-level error state (builds of the affected modules are blocked until it's cleared). Shows
 * everywhere in the app, so the user needn't stay on the Dependencies screen.
 */
@Composable
fun DepsProgressBar(state: DepsResolveState, onRetry: () -> Unit) {
    if (!state.resolving) {
        if (state.unresolved.isNotEmpty()) UnresolvedDepsBanner(state, onRetry)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    GlassSurface(modifier = Modifier.fillMaxWidth(), material = GlassMaterial.Regular) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.pkg, null, Modifier.size(14.dp), tint = Ca.colors.accent)
                Text(
                    state.message.ifBlank { "Resolving dependencies…" }, color = Ca.colors.textSecondary,
                    style = Ca.type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                )
                // Expand to a live log of what the resolver is doing (POMs walked, artifacts downloaded).
                if (state.log.isNotEmpty()) {
                    IconButtonCa(
                        if (expanded) CaIcons.caretDown else CaIcons.caretRight,
                        if (expanded) "Hide resolution details" else "Show resolution details",
                        { expanded = !expanded }, boxSize = 24, iconSize = 14,
                    )
                }
            }
            if (state.fraction in 0.0..1.0) {
                LinearProgressIndicator(
                    progress = { state.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Ca.colors.accent, trackColor = Ca.colors.surface2,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Ca.colors.accent, trackColor = Ca.colors.surface2)
            }
            AnimatedVisibility(expanded && state.log.isNotEmpty()) {
                val scroll = rememberScrollState()
                // Follow the tail as new lines stream in.
                LaunchedEffect(state.log.size) { scroll.scrollTo(scroll.maxValue) }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(top = 8.dp).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for (line in state.log) {
                        Text(
                            line, color = Ca.colors.textTertiary, style = Ca.type.codeSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The persistent "dependencies couldn't be resolved" banner — the project error state. Surfaces the count
 * and the (heuristic) reason inline, expands to the per-dependency list, and offers Retry (re-resolve once
 * the network is back). Builds of the affected modules are refused by the engine while this is showing.
 */
@Composable
private fun UnresolvedDepsBanner(state: DepsResolveState, onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val n = state.unresolved.size
    GlassSurface(modifier = Modifier.fillMaxWidth(), material = GlassMaterial.Regular) {
        Column(
            Modifier.fillMaxWidth()
                .background(Ca.colors.error.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.error, null, Modifier.size(16.dp), tint = Ca.colors.error)
                Column(Modifier.weight(1f)) {
                    Text(
                        "$n ${if (n == 1) "dependency" else "dependencies"} couldn't be resolved",
                        color = Ca.colors.error, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.unresolved.first().reason, color = Ca.colors.textSecondary,
                        style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Retry: re-resolve the declared set (cache-first; recovers once the network is back).
                Row(
                    Modifier.background(Ca.colors.error.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
                        .clickable(onClick = onRetry).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(CaIcons.refresh, "Retry", Modifier.size(13.dp), tint = Ca.colors.error)
                    Text("Retry", color = Ca.colors.error, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                }
                IconButtonCa(
                    if (expanded) CaIcons.caretDown else CaIcons.caretRight,
                    if (expanded) "Hide unresolved dependencies" else "Show unresolved dependencies",
                    { expanded = !expanded }, boxSize = 24, iconSize = 14,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(top = 8.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (u in state.unresolved) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                u.coordinate, color = Ca.colors.textPrimary, style = Ca.type.codeSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${u.reason}  ·  ${u.module}", color = Ca.colors.textTertiary,
                                style = Ca.type.caption2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The Run button + a dropdown to pick which task to run/assemble (with a search box once the list grows). */
@Composable
private fun RunControl(
    tasks: () -> List<RunTaskOption>,
    onPickTask: (RunTaskOption) -> Unit,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<RunTaskOption>()) }
    val interaction = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    Box {
        // The Run button opens the task dropdown directly — no separate chevron, no filled background. The task
        // list is resolved lazily here (it scans sources), not on every recomposition. `keyboard.hide()` drops
        // any editor keyboard so the task list isn't covered; the search field stays unfocusable until tapped
        // (below) so the menu's focusable popup can't auto-focus it and pop the keyboard on open.
        val openMenu = { keyboard?.hide(); query = ""; items = tasks(); open = true }
        if (compact) {
            IconButtonCa(CaIcons.play, "Run", onClick = openMenu, tint = Ca.colors.accent)
        } else {
            Row(
                Modifier.height(34.dp)
                    .clip(RoundedCornerShape(Ca.radius.sm))
                    .pressScale(interaction)
                    .clickable(interaction, indication = null, onClick = openMenu)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(CaIcons.play, "Run", Modifier.size(16.dp), tint = Ca.colors.accent)
                Text("Run", color = Ca.colors.accent, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            }
        }
        CaDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
                if (items.size > 3) {
                    val searchFocus = remember { FocusRequester() }
                    // The field is NOT focusable until the user taps it (`canFocus = searchTapped`), so the
                    // menu's focusable popup can't auto-focus it on open and raise the keyboard. Reset each time
                    // the menu opens. Tapping the box activates it and requests focus → the keyboard shows then.
                    var searchTapped by remember(open) { mutableStateOf(false) }
                    LaunchedEffect(searchTapped) { if (searchTapped) runCatching { searchFocus.requestFocus() } }
                    Box(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(240.dp)
                            .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.sm))
                            .clickable { searchTapped = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (query.isEmpty()) Text("Search tasks…", color = Ca.colors.textTertiary, style = Ca.type.footnote)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                            cursorBrush = SolidColor(Ca.colors.accent),
                            modifier = Modifier.fillMaxWidth()
                                .focusRequester(searchFocus)
                                .focusProperties { canFocus = searchTapped },
                        )
                    }
                }
                val filtered = items.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true)
                }
                if (items.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Nothing to run", color = Ca.colors.textTertiary, style = Ca.type.footnote) },
                        onClick = {}, enabled = false,
                    )
                }
                if (filtered.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No matching tasks", color = Ca.colors.textTertiary, style = Ca.type.footnote) },
                        onClick = {}, enabled = false,
                    )
                }
                Box(Modifier.heightIn(max = 320.dp)) {
                    Column {
                        filtered.forEach { task ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(task.label, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
                                        Text(task.group, color = Ca.colors.textTertiary, style = Ca.type.caption2)
                                    }
                                },
                                leadingIcon = { Icon(CaIcons.play, null, Modifier.size(14.dp), tint = Ca.colors.accent) },
                                onClick = { open = false; onPickTask(task) },
                            )
                        }
                    }
                }
            }
    }
}

/**
 * The active build-variant switcher: a `⌥ layers` chip showing the current variant (e.g. `debug`), opening a
 * dropdown of the module's variants. Picking one re-points the editor's analysis classpath and the Run/assemble
 * default. Shown only for an Android module (a non-empty [variants]); [label] is the current active variant.
 */
@Composable
private fun VariantChip(
    label: String,
    variants: () -> List<String>,
    onPick: (String) -> Unit,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<String>()) }
    val interaction = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    val openMenu = { keyboard?.hide(); items = variants(); open = true }
    Box {
        Row(
            Modifier.height(34.dp)
                .clip(RoundedCornerShape(Ca.radius.sm))
                .pressScale(interaction)
                .clickable(interaction, indication = null, onClick = openMenu)
                .padding(horizontal = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(CaIcons.layers, "Build variant", Modifier.size(15.dp), tint = Ca.colors.textSecondary)
            if (!compact) Text(label, color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
            Icon(CaIcons.caretDown, null, Modifier.size(12.dp), tint = Ca.colors.textTertiary)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (items.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No variants", color = Ca.colors.textTertiary, style = Ca.type.footnote) },
                    onClick = {}, enabled = false,
                )
            }
            items.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = if (v == label) FontWeight.SemiBold else FontWeight.Normal) },
                    leadingIcon = { if (v == label) Icon(CaIcons.check, null, Modifier.size(14.dp), tint = Ca.colors.accent) else Box(Modifier.size(14.dp)) },
                    onClick = { open = false; onPick(v) },
                )
            }
        }
    }
}

/**
 * A compact indexing indicator: accent spinner + percent while building, a faint check when ready. When
 * [onClick] is supplied the chip is tappable, opening the index-status dialog (what's being indexed).
 */
@Composable
fun IndexStatusChip(status: IndexUiStatus, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(Ca.radius.pill)
    // On a phone the idle "Indexed" chip is just clutter that crowds the Run button — show only while building.
    if (compact && !status.building) return
    val base = Modifier.clip(shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    if (status.building) {
        Row(
            base.background(Ca.colors.accentSoft, shape).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(Modifier.size(13.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
            val label = if (status.fraction in 0.0..1.0) "Indexing… ${(status.fraction * 100).toInt()}%" else "Indexing…"
            Text(label, color = Ca.colors.accent, style = Ca.type.caption, fontWeight = FontWeight.Medium)
        }
    } else {
        Row(
            base.background(Ca.colors.surface2, shape).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(CaIcons.check, null, Modifier.size(12.dp), tint = Ca.colors.success)
            Text("Indexed", color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
    }
}

/** Tabs strip (solid editor-bg): active tab gets a 2px accent underline, a modified dot, a close icon. */
@Composable
fun TabsStrip(
    openFiles: List<OpenFile>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (OpenFile) -> Unit,
) {
    val accent = Ca.colors.accent
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Ca.colors.editorBg)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        openFiles.forEachIndexed { index, file ->
            val active = index == activeIndex
            Row(
                Modifier
                    .fillMaxHeight()
                    .clickable { onSelect(index) }
                    .drawBehind {
                        if (active) {
                            drawLine(
                                accent,
                                Offset(0f, size.height - 1f),
                                Offset(size.width, size.height - 1f),
                                strokeWidth = 2f,
                            )
                        }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    file.name,
                    color = if (active) Ca.colors.textPrimary else Ca.colors.textSecondary,
                    style = Ca.type.footnote,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                // The unsaved-changes dot fades + scales while its slot (the dot *and* its leading gap, so the
                // tab smoothly grows/shrinks) expands/collapses — instead of the tab jumping to a new width.
                AnimatedVisibility(
                    visible = file.modified,
                    enter = fadeIn() + expandHorizontally() + scaleIn(initialScale = 0.4f),
                    exit = fadeOut() + shrinkHorizontally() + scaleOut(targetScale = 0.4f),
                ) {
                    Box(Modifier.padding(start = 8.dp).size(7.dp).background(Ca.colors.gitModified, RoundedCornerShape(Ca.radius.pill)))
                }
                Box(
                    Modifier.padding(start = 8.dp).size(16.dp).clickable { onClose(file) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CaIcons.close, "Close", Modifier.size(12.dp), tint = Ca.colors.textTertiary)
                }
            }
        }
    }
    // a hairline under the tabs
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
}

/** Scope breadcrumb (solid): path segments; the last is primary/600, earlier ones secondary. As the caret
 *  moves the segments change — the row crossfades (and resizes) between states rather than snapping. */
@Composable
fun Breadcrumb(segments: List<String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Ca.colors.editorBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = segments,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "breadcrumb",
        ) { segs ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                segs.forEachIndexed { index, seg ->
                    val last = index == segs.lastIndex
                    Text(
                        seg,
                        color = if (last) Ca.colors.textPrimary else Ca.colors.textSecondary,
                        style = Ca.type.caption,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (!last) Icon(CaIcons.chevronRight, null, Modifier.size(13.dp), tint = Ca.colors.textTertiary)
                }
            }
        }
    }
}

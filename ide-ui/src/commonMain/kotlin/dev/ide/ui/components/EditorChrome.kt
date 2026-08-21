package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.UiActionItem
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.edchrome_build_console
import dev.ide.ui.generated.resources.edchrome_build_variant
import dev.ide.ui.generated.resources.edchrome_command_palette
import dev.ide.ui.generated.resources.edchrome_compose_preview
import dev.ide.ui.generated.resources.edchrome_find_replace
import dev.ide.ui.generated.resources.edchrome_gradle_compat
import dev.ide.ui.generated.resources.edchrome_gradle_compatibility_mode
import dev.ide.ui.generated.resources.edchrome_hide_inlay_hints
import dev.ide.ui.generated.resources.edchrome_hide_resolution_details
import dev.ide.ui.generated.resources.edchrome_hide_unresolved_dependencies
import dev.ide.ui.generated.resources.edchrome_indexed
import dev.ide.ui.generated.resources.edchrome_indexing
import dev.ide.ui.generated.resources.edchrome_indexing_percent
import dev.ide.ui.generated.resources.edchrome_more_actions
import dev.ide.ui.generated.resources.edchrome_no_matching_tasks
import dev.ide.ui.generated.resources.edchrome_no_variants
import dev.ide.ui.generated.resources.edchrome_nothing_to_run
import dev.ide.ui.generated.resources.edchrome_optimize_imports
import dev.ide.ui.generated.resources.edchrome_reformat_code
import dev.ide.ui.generated.resources.edchrome_resolving_dependencies
import dev.ide.ui.generated.resources.edchrome_search_tasks
import dev.ide.ui.generated.resources.edchrome_show_inlay_hints
import dev.ide.ui.generated.resources.edchrome_show_resolution_details
import dev.ide.ui.generated.resources.edchrome_show_unresolved_dependencies
import dev.ide.ui.generated.resources.edchrome_toggle_inlay_hints
import dev.ide.ui.generated.resources.edchrome_toggle_navigator
import dev.ide.ui.generated.resources.edchrome_unresolved_dependencies
import dev.ide.ui.generated.resources.redo
import dev.ide.ui.generated.resources.edview_no_file_open_hint
import dev.ide.ui.generated.resources.edview_no_file_open_title
import dev.ide.ui.generated.resources.retry
import dev.ide.ui.generated.resources.run
import dev.ide.ui.generated.resources.save
import dev.ide.ui.generated.resources.tab_close_all
import dev.ide.ui.generated.resources.tab_close_others
import dev.ide.ui.generated.resources.tab_close_to_the_left
import dev.ide.ui.generated.resources.tab_close_to_the_right
import dev.ide.ui.generated.resources.tab_open_files
import dev.ide.ui.generated.resources.undo
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.icons.fileIconId
import dev.ide.ui.theme.resolveTint
import dev.ide.ui.platform.secondaryClickable
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

/**
 * Top bar (a solid Material `surfaceContainer` toolbar), pared back to the essentials: sidebar toggle · project name · index status ·
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
    /** Live navigator-open fraction (0 closed → 1 open) driving the sidebar icon's miniature-screen
     *  animation — the drawer's gesture fraction on phone, an eased toggle on desktop. Deferred read. */
    navFraction: () -> Float = { 0f },
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
    onOptimizeImports: () -> Unit = {},
    onToggleConsole: () -> Unit = {},
    consoleOpen: Boolean = false,
    /** Compact (phone) top bar only: a single button opening the RIGHT tool-window drawer — the phone has no
     *  right activity rail (that's the desktop surface), so this is the entry point. Null icon = no plugin
     *  contributes a RIGHT tool window, so nothing shows. On desktop the right rail owns this instead. */
    rightToolIconId: String? = null,
    rightToolTitle: String = "",
    rightToolOpen: Boolean = false,
    onToggleRightTool: () -> Unit = {},
    inlayHintsOn: Boolean = true,
    onToggleInlayHints: () -> Unit = {},
    showPreview: Boolean = false,
    onPreview: () -> Unit = {},
    previewBusy: Boolean = false,
    onIndexClick: () -> Unit = {},
    /** True when the project was imported from Gradle (compatibility mode) — shows the amber compat chip. */
    compatibilityMode: Boolean = false,
    /** Tapped on the compat chip: re-opens the compatibility-mode details banner. */
    onCompatClick: () -> Unit = {},
    /** Plugin-contributed toolbar actions (the `mainToolbar` place), rendered just before Run. Empty by
     *  default — built-in chrome stays native; this is the seam a plugin adds a button through. */
    pluginActions: List<UiActionItem> = emptyList(),
    onPluginAction: (String) -> Unit = {},
    compact: Boolean = false,
) {
    val dim = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            SidebarToggleButton(navFraction, onToggleNav)
            // The name takes the flexible middle and truncates — the right-hand cluster keeps its size.
            Text(
                projectName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (compatibilityMode) CompatModeChip(compact = compact, onClick = onCompatClick)
            IndexStatusChip(indexStatus, compact = compact, onClick = onIndexClick)
            // Accent-tinted while there are unsaved changes; saves the active tab (Cmd/Ctrl-S also works).
            IconButtonCa(CaIcons.save, stringResource(Res.string.save), onSave, active = hasUnsavedChanges)
            if (compact) {
                // Save · Undo · Redo · Run are the prioritized inline actions even on a phone — one tap away,
                // never buried. Everything else (find, reformat, palette, inlay hints, console, preview, the AI
                // panel, and the build-variant switcher) collapses into the single ⋯ overflow menu.
                if (hasActiveFile) {
                    IconButtonCa(CaIcons.undo, stringResource(Res.string.undo), onUndo, tint = if (canUndo) null else dim)
                    IconButtonCa(CaIcons.redo, stringResource(Res.string.redo), onRedo, tint = if (canRedo) null else dim)
                }
                PluginToolbarActions(pluginActions, dim, onPluginAction)
                RunControl(runTasks, onPickTask, compact = true)
                EditorOverflowMenu(
                    onOpenPalette = onOpenPalette,
                    hasActiveFile = hasActiveFile,
                    onFind = onFind,
                    onReformat = onReformat,
                    onOptimizeImports = onOptimizeImports,
                    inlayHintsOn = inlayHintsOn,
                    onToggleInlayHints = onToggleInlayHints,
                    consoleOpen = consoleOpen,
                    onToggleConsole = onToggleConsole,
                    showPreview = showPreview,
                    onPreview = onPreview,
                    rightToolIconId = rightToolIconId,
                    rightToolTitle = rightToolTitle,
                    rightToolOpen = rightToolOpen,
                    onToggleRightTool = onToggleRightTool,
                    activeVariant = activeVariant,
                    variants = variants,
                    onPickVariant = onPickVariant,
                )
            } else {
                // Edit actions (undo/redo/find) sit just before Run — disabled-tinted with no file open.
                if (hasActiveFile) {
                    IconButtonCa(CaIcons.undo, stringResource(Res.string.undo), onUndo, tint = if (canUndo) null else dim)
                    IconButtonCa(CaIcons.redo, stringResource(Res.string.redo), onRedo, tint = if (canRedo) null else dim)
                    IconButtonCa(CaIcons.search, stringResource(Res.string.edchrome_find_replace), onFind)
                    IconButtonCa(CaIcons.braces, stringResource(Res.string.edchrome_reformat_code), onReformat)
                }
                IconButtonCa(CaIcons.command, stringResource(Res.string.edchrome_command_palette), onOpenPalette)
                IconButtonCa(
                    CaIcons.eye,
                    stringResource(Res.string.edchrome_toggle_inlay_hints),
                    onToggleInlayHints,
                    active = inlayHintsOn
                )
                IconButtonCa(
                    CaIcons.terminal,
                    stringResource(Res.string.edchrome_build_console),
                    onToggleConsole,
                    active = consoleOpen
                )
                // Shown when the open file has @Preview composables — renders/checks them via the interpreter.
                if (showPreview) IconButtonCa(
                    CaIcons.image,
                    stringResource(Res.string.edchrome_compose_preview),
                    onPreview,
                    active = previewBusy
                )
                PluginToolbarActions(pluginActions, dim, onPluginAction)
                if (activeVariant != null) VariantChip(
                    activeVariant,
                    variants,
                    onPickVariant,
                    compact = false
                )
                RunControl(runTasks, onPickTask, compact = false)
            }
        }
    }
}

/**
 * The navigator toggle: a **miniature of the screen** whose drawer pane grows and tints accent exactly in
 * step with the real drawer ([fraction] 0→1) — the divider is the drawer's edge, so a swipe drags the icon
 * live and a toggle glides it. Drawn in the draw phase off a deferred [fraction] read: per-frame drawer
 * movement invalidates only this canvas, never recomposing the bar.
 */
@Composable
fun SidebarToggleButton(fraction: () -> Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val toggleNavLabel = stringResource(Res.string.edchrome_toggle_navigator)
    Box(
        modifier
            .size(34.dp)
            .pressScale(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = toggleNavLabel },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 19.dp, height = 15.dp)) {
            val f = fraction().coerceIn(0f, 1f)
            val stroke = 1.5.dp.toPx()
            val inset = stroke / 2f
            val corner = CornerRadius(3.5.dp.toPx())
            // The divider = the drawer's edge: rides right as the drawer opens (a stylized travel, not
            // the literal screen ratio, so the glyph stays legible at 19px).
            val divider = inset + (size.width - 2 * inset) * (0.34f + 0.30f * f)
            val frame = Path().apply {
                addRoundRect(
                    RoundRect(
                        inset,
                        inset,
                        size.width - inset,
                        size.height - inset,
                        corner
                    )
                )
            }
            clipPath(frame) {
                drawRect(
                    color = lerp(outline.copy(alpha = 0.32f), accent, f),
                    topLeft = Offset(inset, inset),
                    size = Size(divider - inset, size.height - 2 * inset),
                )
            }
            drawRoundRect(
                color = outline,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
            drawLine(outline, Offset(divider, inset), Offset(divider, size.height - inset), stroke)
        }
    }
}

/** Renders the plugin-contributed toolbar actions (the `mainToolbar` action place). Disabled actions are
 *  tinted muted; clicking routes the action id back to the host, which runs it through the registry. */
@Composable
private fun PluginToolbarActions(
    actions: List<UiActionItem>,
    dim: Color,
    onAction: (String) -> Unit
) {
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
 * a phone (find, reformat, optimize imports, command palette, the AI panel, inlay-hint toggle, build console,
 * Compose preview, and the build-variant/flavor switcher). The primary edit actions (save · undo · redo · run)
 * stay inline, so they aren't here. Toggled items show their on-state in the accent color; the build variant
 * opens a flyout submenu of the module's variants.
 */
@Composable
private fun EditorOverflowMenu(
    onOpenPalette: () -> Unit,
    hasActiveFile: Boolean,
    onFind: () -> Unit,
    onReformat: () -> Unit,
    onOptimizeImports: () -> Unit,
    inlayHintsOn: Boolean,
    onToggleInlayHints: () -> Unit,
    consoleOpen: Boolean,
    onToggleConsole: () -> Unit,
    showPreview: Boolean,
    onPreview: () -> Unit,
    /** The AI panel (a RIGHT-anchored tool window). Null icon = no such plugin, so the item is omitted. */
    rightToolIconId: String?,
    rightToolTitle: String,
    rightToolOpen: Boolean,
    onToggleRightTool: () -> Unit,
    /** Active build variant, or null for a non-Android project (then the variant submenu is omitted). */
    activeVariant: String?,
    variants: () -> List<String>,
    onPickVariant: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // The variant list scans the module, so it's fetched only when its submenu opens. Both the menu and the
    // submenu reset closed whenever the ⋯ menu is re-opened (`remember(open)`).
    var variantsOpen by remember(open) { mutableStateOf(false) }
    var variantItems by remember(open) { mutableStateOf(emptyList<String>()) }
    Box {
        IconButtonCa(CaIcons.ellipsis, stringResource(Res.string.edchrome_more_actions), { open = true })
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasActiveFile) {
                OverflowItem(CaIcons.search, stringResource(Res.string.edchrome_find_replace)) { open = false; onFind() }
                OverflowItem(CaIcons.braces, stringResource(Res.string.edchrome_reformat_code)) { open = false; onReformat() }
                OverflowItem(CaIcons.layers, stringResource(Res.string.edchrome_optimize_imports)) { open = false; onOptimizeImports() }
            }
            OverflowItem(CaIcons.command, stringResource(Res.string.edchrome_command_palette)) { open = false; onOpenPalette() }
            // The AI assistant panel — a toggle, its on-state accented like the other tool windows.
            if (rightToolIconId != null) {
                OverflowItem(actionIcon(rightToolIconId), rightToolTitle, active = rightToolOpen) {
                    open = false; onToggleRightTool()
                }
            }
            OverflowItem(
                CaIcons.eye,
                if (inlayHintsOn) stringResource(Res.string.edchrome_hide_inlay_hints) else stringResource(Res.string.edchrome_show_inlay_hints),
                active = inlayHintsOn,
            ) { open = false; onToggleInlayHints() }
            OverflowItem(CaIcons.terminal, stringResource(Res.string.edchrome_build_console), active = consoleOpen) {
                open = false; onToggleConsole()
            }
            if (showPreview) OverflowItem(CaIcons.image, stringResource(Res.string.edchrome_compose_preview)) {
                open = false; onPreview()
            }
            // The build-variant (flavor) switcher — a flyout submenu of the module's variants, shown only for an
            // Android module (a non-null active variant). Picking one closes the whole menu.
            if (activeVariant != null) {
                OverflowMenuDivider()
                CaSubmenuItem(
                    label = "${stringResource(Res.string.edchrome_build_variant)}: $activeVariant",
                    icon = CaIcons.layers,
                    expanded = variantsOpen,
                    onExpandedChange = { want -> variantsOpen = want; if (want) variantItems = variants() },
                ) {
                    if (variantItems.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(Res.string.edchrome_no_variants),
                                    color = MaterialTheme.colorScheme.outline,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = {}, enabled = false,
                        )
                    }
                    variantItems.forEach { v ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    v,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (v == activeVariant) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = {
                                if (v == activeVariant) Icon(
                                    CaIcons.check, null, Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                ) else Box(Modifier.size(14.dp))
                            },
                            onClick = { open = false; onPickVariant(v) },
                        )
                    }
                }
            }
        }
    }
}

/** A thin separator inside the overflow dropdown, grouping the build-variant switcher off from the actions. */
@Composable
private fun OverflowMenuDivider() {
    Box(
        Modifier.fillMaxWidth().height(1.dp).padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
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
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        },
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(CaIcons.pkg, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    state.message.ifBlank { stringResource(Res.string.edchrome_resolving_dependencies) },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                )
                // Expand to a live log of what the resolver is doing (POMs walked, artifacts downloaded).
                if (state.log.isNotEmpty()) {
                    IconButtonCa(
                        if (expanded) CaIcons.caretDown else CaIcons.caretRight,
                        if (expanded) stringResource(Res.string.edchrome_hide_resolution_details) else stringResource(Res.string.edchrome_show_resolution_details),
                        { expanded = !expanded }, boxSize = 24, iconSize = 14,
                    )
                }
            }
            if (state.fraction in 0.0..1.0) {
                LinearProgressIndicator(
                    progress = { state.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            } else {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }
            AnimatedVisibility(expanded && state.log.isNotEmpty()) {
                val scroll = rememberScrollState()
                // Follow the tail as new lines stream in.
                LaunchedEffect(state.log.size) { scroll.scrollTo(scroll.maxValue) }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(top = 8.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for (line in state.log) {
                        Text(
                            line,
                            color = MaterialTheme.colorScheme.outline,
                            style = Ide.type.codeSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
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
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(CaIcons.error, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text(
                        pluralStringResource(Res.plurals.edchrome_unresolved_dependencies, n, n),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.unresolved.first().reason, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Retry: re-resolve the declared set (cache-first; recovers once the network is back).
                Row(
                    Modifier.background(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                        RoundedCornerShape(Ca.radius.pill)
                    )
                        .clickable(onClick = onRetry).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(CaIcons.refresh, stringResource(Res.string.retry), Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(Res.string.retry),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButtonCa(
                    if (expanded) CaIcons.caretDown else CaIcons.caretRight,
                    if (expanded) stringResource(Res.string.edchrome_hide_unresolved_dependencies) else stringResource(Res.string.edchrome_show_unresolved_dependencies),
                    { expanded = !expanded }, boxSize = 24, iconSize = 14,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (u in state.unresolved) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                u.coordinate,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = Ide.type.codeSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${u.reason}  ·  ${u.module}",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
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
            IconButtonCa(CaIcons.play, stringResource(Res.string.run), onClick = openMenu, tint = MaterialTheme.colorScheme.primary)
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
                Icon(CaIcons.play, stringResource(Res.string.run), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(Res.string.run),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
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
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.sm))
                        .clickable { searchTapped = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (query.isEmpty()) Text(
                        stringResource(Res.string.edchrome_search_tasks),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(searchFocus)
                            .focusProperties { canFocus = searchTapped },
                    )
                }
            }
            val filtered = items.filter {
                query.isBlank() || it.label.contains(query, ignoreCase = true) || it.group.contains(
                    query,
                    ignoreCase = true
                )
            }
            if (items.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(Res.string.edchrome_nothing_to_run),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {}, enabled = false,
                )
            }
            if (filtered.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(Res.string.edchrome_no_matching_tasks),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {}, enabled = false,
                )
            }
            Box(Modifier.heightIn(max = 320.dp)) {
                Column {
                    filtered.forEach { task ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        task.label,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        task.group,
                                        color = MaterialTheme.colorScheme.outline,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    CaIcons.play,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
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
            Icon(
                CaIcons.layers,
                stringResource(Res.string.edchrome_build_variant),
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!compact) Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Icon(CaIcons.caretDown, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (items.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(Res.string.edchrome_no_variants),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {}, enabled = false,
                )
            }
            items.forEach { v ->
                DropdownMenuItem(
                    text = {
                        Text(
                            v,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (v == label) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        if (v == label) Icon(
                            CaIcons.check,
                            null,
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        ) else Box(Modifier.size(14.dp))
                    },
                    onClick = { open = false; onPick(v) },
                )
            }
        }
    }
}

/**
 * An amber pill marking that the project was imported from Gradle and runs in compatibility mode. Always
 * present while such a project is open (so the limitation is never out of sight); tapping it re-opens the
 * details banner. Collapses to an icon-only chip on a phone.
 */
@Composable
private fun CompatModeChip(compact: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Ca.radius.pill)
    Row(
        Modifier.clip(shape).clickable(onClick = onClick)
            .background(Ide.colors.warning.copy(alpha = 0.16f), shape)
            .padding(horizontal = if (compact) 6.dp else 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            CaIcons.warning,
            stringResource(Res.string.edchrome_gradle_compatibility_mode),
            Modifier.size(13.dp),
            tint = Ide.colors.warning
        )
        if (!compact) Text(
            stringResource(Res.string.edchrome_gradle_compat),
            color = Ide.colors.warning,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * A compact indexing indicator: accent spinner + percent while building, a faint check when ready. When
 * [onClick] is supplied the chip is tappable, opening the index-status dialog (what's being indexed).
 */
@Composable
fun IndexStatusChip(
    status: IndexUiStatus,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(Ca.radius.pill)
    // On a phone the idle "Indexed" chip is just clutter that crowds the Run button — show only while building.
    if (compact && !status.building) return
    val base = Modifier.clip(shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    if (status.building) {
        Row(
            base.background(MaterialTheme.colorScheme.primaryContainer, shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(13.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
            val label =
                if (status.fraction in 0.0..1.0) stringResource(Res.string.edchrome_indexing_percent, (status.fraction * 100).toInt()) else stringResource(Res.string.edchrome_indexing)
            Text(
                label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    } else {
        Row(
            base.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(CaIcons.check, null, Modifier.size(12.dp), tint = Ide.colors.success)
            Text(stringResource(Res.string.edchrome_indexed), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Tabs strip (solid editor-bg): active tab gets an accent tint + border, a modified dot, a close icon. Each
 * tab opens a close-operations context menu on right-click (desktop) / long-press (touch). Rendered in a
 * [LazyRow] so a session with many open files only composes the tabs that are actually on screen.
 *
 * When the tabs overflow the strip a pinned down-chevron appears at the trailing edge (IntelliJ-style): it
 * opens a dropdown listing every open file so any of them — including those scrolled off-screen — can be
 * jumped to; picking one also scrolls its tab into view.
 */
@Composable
fun TabsStrip(
    openFiles: List<OpenFile>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (OpenFile) -> Unit,
    onCloseOthers: (OpenFile) -> Unit = {},
    onCloseToRight: (OpenFile) -> Unit = {},
    onCloseToLeft: (OpenFile) -> Unit = {},
    onCloseAll: () -> Unit = {},
) {
    if (openFiles.isEmpty()) return

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The chevron only earns its place when the tabs actually spill past the strip's width.
    val overflowing = listState.canScrollForward || listState.canScrollBackward

    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(vertical = 4.dp)
            .background(Ide.colors.editorBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            Modifier.weight(1f).fillMaxHeight(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Keyed by the tab's stable unique id, NOT its path: two tabs can transiently share a path
            // (a re-point after rename/move, or a concurrent open on a slow device), and a duplicate LazyRow
            // key hard-crashes the measure pass — an id per tab makes that impossible (see OpenFile.tabId).
            itemsIndexed(openFiles, key = { _, f -> f.tabId }) { index, file ->
                EditorTab(
                    // Fade a newly-opened tab in, fade a closed one out, and slide the rest into place — so
                    // opening/closing tabs animates instead of snapping (LazyRow item animation, keyed by tab id).
                    modifier = Modifier.animateItem(),
                    file = file,
                    active = index == activeIndex,
                    canCloseOthers = openFiles.size > 1,
                    canCloseRight = index < openFiles.lastIndex,
                    canCloseLeft = index > 0,
                    onSelect = { onSelect(index) },
                    onClose = { onClose(file) },
                    onCloseOthers = { onCloseOthers(file) },
                    onCloseToRight = { onCloseToRight(file) },
                    onCloseToLeft = { onCloseToLeft(file) },
                    onCloseAll = onCloseAll,
                )
            }
        }
        if (overflowing) {
            // A hairline separates the pinned chevron from the scrolling tabs.
            Box(Modifier.fillMaxHeight(0.55f).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            TabsOverflowMenu(openFiles, activeIndex) { index ->
                onSelect(index)
                scope.launch { runCatching { listState.animateScrollToItem(index) } }
            }
        }
    }
    // a hairline under the tabs
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * The trailing overflow affordance: a down-chevron that drops a menu of every open file (file-type icon +
 * name, the active one accented, a dot for unsaved changes). [onSelect] jumps to the picked file by index.
 */
@Composable
private fun TabsOverflowMenu(openFiles: List<OpenFile>, activeIndex: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 4.dp)) {
        IconButtonCa(
            CaIcons.chevronDown,
            stringResource(Res.string.tab_open_files),
            onClick = { open = true },
            active = open,
            boxSize = 28,
            iconSize = 18,
        )
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            openFiles.forEachIndexed { index, file ->
                OpenFileMenuItem(file, active = index == activeIndex) {
                    open = false
                    onSelect(index)
                }
            }
        }
    }
}

/** One row of the open-files overflow menu: leading file-type glyph, name (accented + bold when active), and
 *  a trailing unsaved-changes dot — mirroring how the tab itself reads. */
@Composable
private fun OpenFileMenuItem(file: OpenFile, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (file.modified) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(7.dp).background(Ide.colors.gitModified, RoundedCornerShape(Ca.radius.pill)))
                }
            }
        },
        leadingIcon = { TabFileIcon(file.name) },
        onClick = onClick,
    )
}

/** One tab. The row selects on click; right-click (desktop) or long-press (touch) opens the close menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorTab(
    file: OpenFile,
    active: Boolean,
    canCloseOthers: Boolean,
    canCloseRight: Boolean,
    canCloseLeft: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseToRight: () -> Unit,
    onCloseToLeft: () -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxHeight()
                .background(
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(Ca.radius.md)
                )
                .combinedClickable(onClick = onSelect, onLongClick = { menuOpen = true })
                .secondaryClickable { menuOpen = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading file-type icon (same glyph/badge as the file tree), so a tab is identifiable at a glance.
            TabFileIcon(file.name)
            Spacer(Modifier.width(8.dp))
            Text(
                file.name,
                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            // The unsaved-changes dot fades + scales while its slot (the dot *and* its leading gap, so the
            // tab smoothly grows/shrinks) expands/collapses — instead of the tab jumping to a new width.
            AnimatedVisibility(
                visible = file.modified,
                enter = fadeIn() + expandHorizontally() + scaleIn(initialScale = 0.4f),
                exit = fadeOut() + shrinkHorizontally() + scaleOut(targetScale = 0.4f),
            ) {
                Box(
                    Modifier.padding(start = 8.dp).size(7.dp)
                        .background(Ide.colors.gitModified, RoundedCornerShape(Ca.radius.pill))
                )
            }
            Box(
                Modifier.padding(start = 8.dp).size(16.dp).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    CaIcons.close,
                    stringResource(Res.string.close),
                    Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
        CaDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            OverflowItem(CaIcons.close, stringResource(Res.string.close)) { menuOpen = false; onClose() }
            OverflowItem(CaIcons.close, stringResource(Res.string.tab_close_others), enabled = canCloseOthers) {
                menuOpen = false; onCloseOthers()
            }
            OverflowItem(CaIcons.chevronRight, stringResource(Res.string.tab_close_to_the_right), enabled = canCloseRight) {
                menuOpen = false; onCloseToRight()
            }
            OverflowItem(CaIcons.chevronLeft, stringResource(Res.string.tab_close_to_the_left), enabled = canCloseLeft) {
                menuOpen = false; onCloseToLeft()
            }
            OverflowItem(CaIcons.close, stringResource(Res.string.tab_close_all)) { menuOpen = false; onCloseAll() }
        }
    }
}

/** Leading file-type icon for a tab — the same glyph/badge the file tree shows, resolved from the file name
 *  ([fileIconId]). Files are always leaves, so a Folder-shaped id collapses to its closed glyph. */
@Composable
private fun TabFileIcon(name: String) {
    when (val ic = TreeIcons.resolve(fileIconId(name))) {
        is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(15.dp), tint = resolveTint(ic.tint))
        is TreeIcon.Folder -> Icon(ic.closed, null, Modifier.size(15.dp), tint = resolveTint(ic.tint))
        is TreeIcon.Badge -> LetterBadge(ic.text, ic.color, 15)
    }
}

/** The editor's empty state — shown in place of the code canvas when no tab is open: a muted glyph, a short
 *  title, and a hint, centered. Nicer than a bare line of text.
 *
 *  A rightward swipe anywhere here opens the navigator: the view is a horizontal scroll that consumes nothing
 *  (same `reverseDirection` config the editor uses), so the drag leaks to the compact layout's [PushDrawer]
 *  nested-scroll exactly as swiping the editor at its horizontal start does — finger-following, then settling.
 *  Where there's no drawer (the expanded layout) it's an inert no-op. */
@Composable
fun NoOpenFilesView(modifier: Modifier = Modifier) {
    val drawerSwipe = rememberScrollableState { 0f }
    Box(
        modifier
            .background(Ide.colors.editorBg)
            .scrollable(drawerSwipe, Orientation.Horizontal, reverseDirection = true),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.docText, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(Res.string.edview_no_file_open_title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.edview_no_file_open_hint),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview
@Composable
fun TabsStripPreview() {
    val openFiles = listOf(
        OpenFile("Test", "Test.kt", ""),
        OpenFile("Second", "Second.kt", "")
    )
    CodeAssistTheme {
        TabsStrip(
            openFiles = openFiles,
            activeIndex = 0,
            onSelect = {},
            onClose = {}
        )
    }
}

/** Scope breadcrumb (solid): path segments; the last is primary/600, earlier ones secondary. As the caret
 *  moves the segments change — the row crossfades (and resizes) between states rather than snapping. */
@Composable
fun Breadcrumb(segments: List<String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Ide.colors.editorBg)
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
                        color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (!last) Icon(
                        CaIcons.chevronRight,
                        null,
                        Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

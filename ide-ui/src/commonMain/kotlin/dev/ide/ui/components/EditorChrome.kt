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
import dev.ide.ui.icons.CaIcons
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
    onRun: () -> Unit,
    runTasks: () -> List<RunTaskOption> = { emptyList() },
    onPickTask: (RunTaskOption) -> Unit = {},
    onSave: () -> Unit = {},
    hasUnsavedChanges: Boolean = false,
    onToggleConsole: () -> Unit = {},
    consoleOpen: Boolean = false,
    inlayHintsOn: Boolean = true,
    onToggleInlayHints: () -> Unit = {},
    showPreview: Boolean = false,
    onPreview: () -> Unit = {},
    previewBusy: Boolean = false,
    compact: Boolean = false,
) {
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
            IndexStatusChip(indexStatus, compact = compact)
            // Accent-tinted while there are unsaved changes; saves the active tab (Cmd/Ctrl-S also works).
            IconButtonCa(CaIcons.save, "Save", onSave, active = hasUnsavedChanges)
            if (compact) {
                // On a phone the bar can't hold every control, so Run stays inline and the rest collapse
                // into a single ⋯ overflow menu — keeping the bar uncluttered yet everything one tap away.
                RunControl(runTasks, onRun, onPickTask, compact = true)
                EditorOverflowMenu(
                    onOpenPalette = onOpenPalette,
                    inlayHintsOn = inlayHintsOn,
                    onToggleInlayHints = onToggleInlayHints,
                    consoleOpen = consoleOpen,
                    onToggleConsole = onToggleConsole,
                    showPreview = showPreview,
                    onPreview = onPreview,
                )
            } else {
                IconButtonCa(CaIcons.command, "Command palette", onOpenPalette)
                IconButtonCa(CaIcons.eye, "Toggle inlay hints", onToggleInlayHints, active = inlayHintsOn)
                IconButtonCa(CaIcons.terminal, "Build console", onToggleConsole, active = consoleOpen)
                // Shown when the open file has @Preview composables — renders/checks them via the interpreter.
                if (showPreview) IconButtonCa(CaIcons.image, "Compose preview", onPreview, active = previewBusy)
                RunControl(runTasks, onRun, onPickTask, compact = false)
            }
        }
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
            OverflowItem(CaIcons.command, "Command palette", active = false) { open = false; onOpenPalette() }
            OverflowItem(
                CaIcons.eye, if (inlayHintsOn) "Hide inlay hints" else "Show inlay hints", active = inlayHintsOn,
            ) { open = false; onToggleInlayHints() }
            OverflowItem(CaIcons.terminal, "Build console", active = consoleOpen) { open = false; onToggleConsole() }
            if (showPreview) OverflowItem(CaIcons.image, "Compose preview", active = false) { open = false; onPreview() }
        }
    }
}

@Composable
private fun OverflowItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (active) Ca.colors.accent else Ca.colors.textPrimary,
                style = Ca.type.footnote, fontWeight = FontWeight.Medium,
            )
        },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = if (active) Ca.colors.accent else Ca.colors.textSecondary) },
        onClick = onClick,
    )
}

/**
 * A thin progress strip under the top bar shown while the engine resolves dependencies (a newly-created
 * project's template deps, or an add from the Dependencies screen). Backed by the shared `depsState`, so it
 * shows everywhere in the app while resolution runs in the background — the user needn't stay on the
 * Dependencies screen.
 */
@Composable
fun DepsProgressBar(state: DepsResolveState) {
    if (!state.resolving) return
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

/** The Run button + a dropdown to pick which task to run/assemble (with a search box once the list grows). */
@Composable
private fun RunControl(
    tasks: () -> List<RunTaskOption>,
    onRun: () -> Unit,
    onPickTask: (RunTaskOption) -> Unit,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<RunTaskOption>()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PrimaryButton("Run", onRun, icon = CaIcons.play, iconOnly = compact)
        Box {
            // Resolve the task list lazily on open (it scans sources), not on every recomposition.
            IconButtonCa(CaIcons.chevronDown, "Choose task to run", { query = ""; items = tasks(); open = true })
            CaDropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                if (items.size > 3) {
                    Box(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(240.dp)
                            .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.sm))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (query.isEmpty()) Text("Search tasks…", color = Ca.colors.textTertiary, style = Ca.type.footnote)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                            cursorBrush = SolidColor(Ca.colors.accent),
                            modifier = Modifier.fillMaxWidth(),
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
}

/** A compact indexing indicator: accent spinner + percent while building, a faint check when ready. */
@Composable
fun IndexStatusChip(status: IndexUiStatus, compact: Boolean = false) {
    val shape = RoundedCornerShape(Ca.radius.pill)
    // On a phone the idle "Indexed" chip is just clutter that crowds the Run button — show only while building.
    if (compact && !status.building) return
    if (status.building) {
        Row(
            Modifier.background(Ca.colors.accentSoft, shape).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(Modifier.size(13.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
            val label = if (status.fraction in 0.0..1.0) "Indexing… ${(status.fraction * 100).toInt()}%" else "Indexing…"
            Text(label, color = Ca.colors.accent, style = Ca.type.caption, fontWeight = FontWeight.Medium)
        }
    } else {
        Row(
            Modifier.background(Ca.colors.surface2, shape).padding(horizontal = 10.dp, vertical = 5.dp),
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

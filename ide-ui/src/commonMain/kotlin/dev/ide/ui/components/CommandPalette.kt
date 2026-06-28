package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionEffect
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.ext.BuiltInUiActions
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

class PaletteEntry(val section: String, val label: String, val sub: String?, val run: () -> Unit)

/**
 * The IntelliJ-style scope tabs across the top of the palette: a narrowing lens over the result sections.
 * [All] searches everything; the rest restrict the result list (and the index queries) to one kind, so
 * "Symbols" won't bury a class under file-name or command matches. [sections] is the set of [PaletteEntry]
 * section labels this tab keeps (empty = keep all); Tab cycles forward through them.
 */
enum class PaletteFilter(val label: String, val sections: Set<String>) {
    All("All", emptySet()),
    Commands("Commands", setOf("Commands", "Run")),
    Files("Files", setOf("Files", "Go to")),
    Symbols("Symbols", setOf("Symbols")),
    Members("Members", setOf("Members"));

    fun keeps(section: String): Boolean = sections.isEmpty() || section in sections
    val wantsSymbols: Boolean get() = this == All || this == Symbols
    val wantsMembers: Boolean get() = this == All || this == Members
}

/**
 * The command palette: drops from the top (glass-thick). One input over commands, files, and — backed
 * by the index — **Go-to-Symbol** (navigable project declarations) and **Member search** across the
 * classpath, with IntelliJ-style scope tabs (All / Commands / Files / Symbols / Members) to narrow the
 * results. Enter runs the top result; Tab cycles the scope; Esc closes.
 */
@Composable
fun CommandPalette(
    files: List<TreeNode>,
    backend: IdeBackend,
    uiHost: UiActionHost,
    onOpenFile: (TreeNode) -> Unit,
    onOpenAt: (String, Int) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PaletteFilter.All) }
    var symbols by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    var members by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // The palette merges two command sources, both from the action registries:
    //  - engine commands (Run/Stop/Re-index + any dex plugin) via IdeBackend.actionsFor (data-driven), and
    //  - UI-navigation commands (Settings/Dependencies/SDK/Toggle theme + in-UI plugins) via UiActionRegistry.
    BuiltInUiActions.ensureRegistered()
    val pluginCommands = remember { backend.actions.actionsFor(UiActionContext(place = UiActionPlaces.COMMAND_PALETTE)) }
    val uiCommands = UiActionRegistry.forPlace(UiActionPlaces.COMMAND_PALETTE, uiHost)
    fun runCommand(id: String) {
        scope.launch {
            val result = runCatching {
                backend.actions.invokeAction(id, UiActionContext(place = UiActionPlaces.COMMAND_PALETTE))
            }.getOrNull() ?: return@launch
            for (effect in result.effects) when (effect) {
                is UiActionEffect.OpenFile -> onOpenAt(effect.path, effect.offset ?: 0)
                else -> {} // Navigate/RefreshTree/ReloadFile need a richer host; the palette ignores them.
            }
        }
    }
    // Only hit the index for the kinds the active scope actually shows — picking "Files" shouldn't pay for a
    // member scan. Re-runs when the scope changes so switching tabs fills in results that were skipped.
    LaunchedEffect(query, filter) {
        val q = query.trim()
        if (q.length >= 2) {
            symbols = if (filter.wantsSymbols) runCatching { backend.search.searchSymbols(q, 20) }.getOrDefault(emptyList()) else emptyList()
            members = if (filter.wantsMembers) runCatching { backend.search.searchMembers(q, 20) }.getOrDefault(emptyList()) else emptyList()
        } else { symbols = emptyList(); members = emptyList() }
    }

    val q = query.trim()
    val allEntries = buildList {
        if (q.isEmpty()) {
            // Engine commands (Run/Stop/Re-index + dex plugins) and UI commands (nav/theme + in-UI plugins).
            pluginCommands.forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null) { runCommand(cmd.id) }) }
            uiCommands.forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null) { cmd.perform(uiHost) }) }
            files.take(12).forEach { f -> add(PaletteEntry("Go to", f.name, null) { onOpenFile(f) }) }
        } else {
            symbols.forEach { s ->
                add(PaletteEntry("Symbols", s.name, s.detail) {
                    if (s.filePath != null && s.offset != null) onOpenAt(s.filePath, s.offset)
                })
            }
            files.filter { it.name.contains(q, ignoreCase = true) }.take(8)
                .forEach { f -> add(PaletteEntry("Files", f.name, null) { onOpenFile(f) }) }
            members.forEach { m -> add(PaletteEntry("Members", m.name, m.detail) {}) }
            pluginCommands.filter { it.text.contains(q, ignoreCase = true) }
                .forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null) { runCommand(cmd.id) }) }
            uiCommands.filter { it.text.contains(q, ignoreCase = true) }
                .forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null) { cmd.perform(uiHost) }) }
        }
    }
    val entries = allEntries.filter { filter.keeps(it.section) }

    // The scrim + drop-from-top entrance are provided by the hosting DropdownOverlay; this is just the
    // glass body. Adaptive width: full-bleed (minus 12dp margins) on phone, capped at 600 on desktop.
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(CaIcons.command, null, Modifier.size(20.dp), tint = Ca.colors.accent)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Run a command, jump to a file or symbol…", color = Ca.colors.textTertiary, style = Ca.type.body)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = Ca.type.body.copy(color = Ca.colors.textPrimary),
                    cursorBrush = SolidColor(Ca.colors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Escape -> { onClose(); true }
                            Key.Enter -> { entries.firstOrNull()?.let { it.run(); onClose() }; true }
                            // Tab cycles the scope (Search-Everywhere style); Shift-Tab steps back.
                            Key.Tab -> {
                                val all = PaletteFilter.entries
                                val step = if (ev.isShiftPressed) -1 else 1
                                filter = all[(filter.ordinal + step + all.size) % all.size]
                                true
                            }
                            else -> false
                        }
                    },
                )
            }
            Chip("esc", fill = Ca.colors.surface3, textColor = Ca.colors.textSecondary)
        }
        // The scope tabs: a narrowing lens over the result sections (IntelliJ's All/Classes/Files/… tabs).
        FilterTabs(filter) { filter = it }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp)) {
                Text(
                    if (q.isEmpty() && filter != PaletteFilter.All) "Type to search ${filter.label.lowercase()}…"
                    else "No matches",
                    color = Ca.colors.textTertiary, style = Ca.type.subhead,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(entries) { entry -> PaletteRow(entry, onClose) }
            }
        }
    }
}

/** The horizontal scope-tab strip: a pill per [PaletteFilter], the active one filled in the accent tint. */
@Composable
private fun FilterTabs(active: PaletteFilter, onSelect: (PaletteFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaletteFilter.entries.forEach { f ->
            val selected = f == active
            Box(
                Modifier
                    .clip(RoundedCornerShape(Ca.radius.pill))
                    .background(if (selected) Ca.colors.accentSoft else Ca.colors.surface3)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    f.label,
                    color = if (selected) Ca.colors.accent else Ca.colors.textSecondary,
                    style = Ca.type.caption,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable { entry.run(); onClose() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) {
            Text(entry.section.uppercase(), color = Ca.colors.textTertiary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            entry.label, color = Ca.colors.textPrimary, style = Ca.type.subhead,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (entry.sub != null) {
            Spacer(Modifier.width(8.dp))
            Text(entry.sub, color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

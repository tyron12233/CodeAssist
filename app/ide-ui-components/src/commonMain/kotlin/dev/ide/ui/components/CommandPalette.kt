package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
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
import dev.ide.ui.backend.UiCaretContext
import dev.ide.ui.backend.UiActionItem
import dev.ide.ui.backend.UiActionEffect
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.palette_filter_all
import dev.ide.ui.generated.resources.palette_filter_commands
import dev.ide.ui.generated.resources.palette_filter_files
import dev.ide.ui.generated.resources.palette_filter_members
import dev.ide.ui.generated.resources.palette_filter_symbols
import dev.ide.ui.generated.resources.palette_hint
import dev.ide.ui.generated.resources.palette_no_matches
import dev.ide.ui.generated.resources.palette_section_goto
import dev.ide.ui.generated.resources.palette_type_to_search
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.ide.ui.generated.resources.palette_key_close
import dev.ide.ui.generated.resources.palette_key_navigate
import dev.ide.ui.generated.resources.palette_key_open
import dev.ide.ui.generated.resources.palette_key_scope
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.platform.hasHardwareKeyboard
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

class PaletteEntry(
    val section: String,
    val label: String,
    val sub: String?,
    /** The command's action id, for its shortcut hint; null for a file or a symbol. */
    val actionId: String? = null,
    /** What the row's leading glyph shows: a command's action icon id, or a file's [dev.ide.ui.icons.TreeIcons] id. */
    val iconId: String? = null,
    val run: () -> Unit,
)

/**
 * The IntelliJ-style scope tabs across the top of the palette: a narrowing lens over the result sections.
 * [All] searches everything; the rest restrict the result list (and the index queries) to one kind, so
 * "Symbols" won't bury a class under file-name or command matches. [sections] is the set of [PaletteEntry]
 * section labels this tab keeps (empty = keep all); Tab cycles forward through them.
 */
enum class PaletteFilter(val labelRes: StringResource, val sections: Set<String>) {
    All(Res.string.palette_filter_all, emptySet()),
    Commands(Res.string.palette_filter_commands, setOf("Commands", "Run")),
    Files(Res.string.palette_filter_files, setOf("Files", "Go to")),
    Symbols(Res.string.palette_filter_symbols, setOf("Symbols")),
    Members(Res.string.palette_filter_members, setOf("Members"));

    fun keeps(section: String): Boolean = sections.isEmpty() || section in sections
    val wantsSymbols: Boolean get() = this == All || this == Symbols
    val wantsMembers: Boolean get() = this == All || this == Members
}

/**
 * The command palette: drops from the top on an opaque surface. One input over commands, files, and — backed
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
    /** The focused editor, when there is one: what an editor-aware command resolves against. Null leaves
     *  the palette to the file-independent commands. */
    editorTarget: PaletteEditorTarget? = null,
    /** Applies a command's effects. Supplied by the host, which owns the editor and navigation; without it
     *  the palette can only honor "open this file". */
    onEffects: suspend (List<UiActionEffect>) -> Unit = { },
    /** The scope tab to open on: the go-to shortcuts open it on Files, Symbols, ... */
    initialFilter: PaletteFilter = PaletteFilter.All,
    /** Text the search field opens with. */
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }
    var filter by remember(initialFilter) { mutableStateOf(initialFilter) }
    var symbols by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    var members by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // The palette merges two command sources, both from the action registries:
    //  - engine commands (Run/Stop/Re-index + any dex plugin) via IdeBackend.actionsFor (data-driven), and
    //  - UI-navigation commands (Settings/Dependencies/SDK/Toggle theme + in-UI plugins) via UiActionRegistry.
    UiPluginHost.ensureLoaded()

    // The caret snapshot for the focused editor, fetched once when the palette opens. Commands read it off
    // the context to decide whether they apply (and what to act on), so an editor-aware command is listed
    // only where it makes sense, and is never re-resolved as the user types a query.
    var caret by remember(editorTarget?.path) { mutableStateOf<UiCaretContext?>(null) }
    LaunchedEffect(editorTarget?.path, editorTarget?.selectionStart) {
        val t = editorTarget ?: return@LaunchedEffect
        caret = runCatching { backend.editor.caretContext(t.path, t.text, t.selectionStart) }.getOrNull()
    }

    fun contextFor(place: String) = UiActionContext(
        place = place,
        activeFilePath = editorTarget?.path,
        selectionStart = editorTarget?.selectionStart,
        selectionEnd = editorTarget?.selectionEnd,
        caret = caret,
        documentText = editorTarget?.text,
    )

    // Engine commands: those placed in the palette, plus the editor actions when an editor is focused,
    // which makes them searchable by name instead of reachable only through the caret popup.
    var pluginCommands by remember { mutableStateOf<List<UiActionItem>>(emptyList()) }
    LaunchedEffect(editorTarget?.path, caret) {
        val palette = runCatching { backend.actions.actionsFor(contextFor(UiActionPlaces.COMMAND_PALETTE)) }
            .getOrDefault(emptyList())
        val editor = if (editorTarget == null) emptyList() else
            runCatching { backend.actions.actionsFor(contextFor(UiActionPlaces.EDITOR)) }
                .getOrDefault(emptyList())
        // A command placed in BOTH places must be listed once.
        pluginCommands = (palette + editor).distinctBy { it.id }
    }
    val uiCommands = UiActionRegistry.forPlace(UiActionPlaces.COMMAND_PALETTE, uiHost)
    fun runCommand(id: String) {
        scope.launch {
            // An editor-placed command is invoked in its own place so it sees the caret it was listed for.
            val place =
                if (editorTarget != null && pluginCommands.any { it.id == id }) UiActionPlaces.EDITOR
                else UiActionPlaces.COMMAND_PALETTE
            val result = runCatching {
                backend.actions.invokeAction(id, contextFor(place))
            }.getOrNull() ?: return@launch
            onEffects(result.effects)
            for (effect in result.effects) when (effect) {
                is UiActionEffect.OpenFile -> onOpenAt(effect.path, effect.offset ?: 0)
                else -> {} // everything else is the host's job, via onEffects.
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
    val root = backend.project.rootPath.replace('\\', '/').trimEnd('/')
    fun fileEntry(section: String, f: TreeNode) =
        PaletteEntry(section, f.name, f.filePath?.let { folderOf(it, root) }, iconId = f.iconId) { onOpenFile(f) }
    val allEntries = buildList {
        if (q.isEmpty()) {
            // Engine commands (Run/Stop/Re-index + dex plugins) and UI commands (nav/theme + in-UI plugins).
            pluginCommands.forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null, cmd.id, cmd.iconId) { runCommand(cmd.id) }) }
            uiCommands.forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null, cmd.id, cmd.iconId) { cmd.perform(uiHost) }) }
            files.take(12).forEach { f -> add(fileEntry("Go to", f)) }
        } else {
            symbols.forEach { s ->
                add(PaletteEntry("Symbols", s.name, s.detail, iconId = s.kind) {
                    if (s.filePath != null && s.offset != null) onOpenAt(s.filePath!!, s.offset!!)
                })
            }
            files.filter { it.name.contains(q, ignoreCase = true) }.take(8).forEach { f -> add(fileEntry("Files", f)) }
            members.forEach { m -> add(PaletteEntry("Members", m.name, m.detail, iconId = m.kind) {}) }
            pluginCommands.filter { it.text.contains(q, ignoreCase = true) }
                .forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null, cmd.id, cmd.iconId) { runCommand(cmd.id) }) }
            uiCommands.filter { it.text.contains(q, ignoreCase = true) }
                .forEach { cmd -> add(PaletteEntry("Commands", cmd.text, null, cmd.id, cmd.iconId) { cmd.perform(uiHost) }) }
        }
    }
    // The engine and the UI can each contribute a command of the same name (Re-index); list it once.
    val entries = allEntries.filter { filter.keeps(it.section) }
        .distinctBy { if (it.section == "Commands") "cmd:" + it.label.lowercase() else it }
    // How many results each scope tab would show, so the tabs say where the matches are before they are picked.
    val counts = if (q.isEmpty()) emptyMap() else PaletteFilter.entries.associateWith { f -> allEntries.count { f.keeps(it.section) } }

    // The keyboard selection: Up/Down move it, Enter runs it, the pointer follows hover. Back to the top whenever
    // the results are recomputed for a new query or scope.
    var selected by remember { mutableStateOf(0) }
    LaunchedEffect(q, filter) { selected = 0 }
    val sel = selected.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    // The rows as laid out: a heading opens each run of one section (only when more than one is on show, since a
    // single section is already named by its tab), so a section's label is written once rather than on every row.
    val showHeadings = entries.map { it.section }.distinct().size > 1
    val rows = buildList {
        var last: String? = null
        entries.forEachIndexed { i, e ->
            if (showHeadings && e.section != last) add(PaletteRowItem.Heading(e.section))
            last = e.section
            add(PaletteRowItem.Result(e, i))
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(sel, rows.size) {
        // Back at the top, show the top, heading included.
        if (sel == 0) { listState.scrollToItem(0); return@LaunchedEffect }
        val at = rows.indexOfFirst { it is PaletteRowItem.Result && it.index == sel }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (at < 0 || visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        if (at <= first) listState.scrollToItem(at)
        else if (at >= last) listState.scrollToItem((at - (last - first) + 1).coerceAtLeast(0))
    }

    // The scrim + drop-from-top entrance are provided by the hosting DropdownOverlay; this is just the
    // palette body. Adaptive width: full-bleed (minus 12dp margins) on phone, capped at 640 on desktop.
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ca.radius.xl))
            // Opaque: the code behind a translucent palette competed with its results.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.xl)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(CaIcons.search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(Res.string.palette_hint), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Escape -> { onClose(); true }
                            Key.Enter -> { entries.getOrNull(sel)?.let { it.run(); onClose() }; true }
                            Key.DirectionDown -> { if (entries.isNotEmpty()) selected = (sel + 1) % entries.size; true }
                            Key.DirectionUp -> { if (entries.isNotEmpty()) selected = (sel - 1 + entries.size) % entries.size; true }
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
            if (query.isNotEmpty()) {
                Icon(
                    CaIcons.close, null,
                    Modifier.size(28.dp).clip(RoundedCornerShape(Ca.radius.pill)).clickable { query = "" }.padding(6.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The scope tabs: a narrowing lens over the result sections (IntelliJ's All/Classes/Files/... tabs).
        FilterTabs(filter, counts) { filter = it }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (q.isEmpty() && filter != PaletteFilter.All) stringResource(Res.string.palette_type_to_search, stringResource(filter.labelRes).lowercase())
                    else stringResource(Res.string.palette_no_matches),
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is PaletteRowItem.Heading -> SectionHeading(sectionLabel(row.section))
                        is PaletteRowItem.Result -> PaletteRow(
                            row.entry, query = q, selected = row.index == sel,
                            onHover = { selected = row.index }, onClose = onClose,
                        )
                    }
                }
            }
        }
        if (hasHardwareKeyboard()) KeyHints()
    }
}

/** One row of the result list: a section heading, or a result at [index] among the selectable entries. */
private sealed interface PaletteRowItem {
    val key: String

    class Heading(val section: String) : PaletteRowItem {
        override val key get() = "h:$section"
    }

    class Result(val entry: PaletteEntry, val index: Int) : PaletteRowItem {
        override val key get() = "r:$index"
    }
}

/** The horizontal scope-tab strip: a pill per [PaletteFilter], the active one filled, each with its match count. */
@Composable
private fun FilterTabs(active: PaletteFilter, counts: Map<PaletteFilter, Int>, onSelect: (PaletteFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaletteFilter.entries.forEach { f ->
            val selected = f == active
            val count = counts[f]
            // A scope with nothing for this query stays clickable but recedes.
            val empty = count == 0
            Row(
                Modifier
                    .clip(RoundedCornerShape(Ca.radius.pill))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.pill))
                    .clickable { onSelect(f) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .alpha(if (empty && !selected) 0.5f else 1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(f.labelRes),
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (count != null && count > 0) {
                    Text(
                        count.toString(),
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** Localized display for a [PaletteEntry.section] key (the key itself stays English for filtering/grouping). */
@Composable
private fun sectionLabel(section: String): String = when (section) {
    "Commands" -> stringResource(Res.string.palette_filter_commands)
    "Symbols" -> stringResource(Res.string.palette_filter_symbols)
    "Files" -> stringResource(Res.string.palette_filter_files)
    "Members" -> stringResource(Res.string.palette_filter_members)
    "Go to" -> stringResource(Res.string.palette_section_goto)
    else -> section
}

@Composable
private fun SectionHeading(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun PaletteRow(entry: PaletteEntry, query: String, selected: Boolean, onHover: () -> Unit, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Ca.radius.md))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null) { entry.run(); onClose() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryIcon(entry)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                highlighted(entry.label, query, MaterialTheme.colorScheme.primary),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (entry.sub != null) {
                Text(
                    entry.sub, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        entry.actionId?.let { ShortcutHint(it) }
    }
}

/** A command's action icon, a file's type badge, or a symbol's kind, in a fixed 20dp slot so labels line up. */
@Composable
private fun EntryIcon(entry: PaletteEntry) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (entry.section) {
            "Files", "Go to" -> TemplateGlyph(entry.iconId ?: "file", 16.dp, fallbackTint = muted)
            "Symbols", "Members" -> Icon(symbolIcon(entry.iconId), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            else -> Icon(if (entry.iconId == null) CaIcons.command else actionIcon(entry.iconId), null, Modifier.size(16.dp), tint = muted)
        }
    }
}

/** A glyph for a symbol kind (`class`, `method`, `field`, ...); anything else reads as code. */
private fun symbolIcon(kind: String?): ImageVector = when (kind?.lowercase()) {
    "class", "interface", "enum", "object", "record", "annotation", "type" -> CaIcons.box
    "method", "function", "constructor" -> CaIcons.braces
    "field", "property", "variable", "constant" -> CaIcons.dot
    else -> CaIcons.code
}

/** [text] with every case-insensitive occurrence of [query] drawn in [color], so the row shows why it matched. */
private fun highlighted(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    if (query.isEmpty()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (i < text.length) {
        val at = text.indexOf(query, i, ignoreCase = true)
        if (at < 0) { append(text.substring(i)); break }
        append(text.substring(i, at))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(at, at + query.length)) }
        i = at + query.length
    }
}

/** A file's folder relative to the project root, shown after its name so two `build.gradle`s tell apart. */
private fun folderOf(path: String, root: String): String {
    val p = path.replace('\\', '/')
    val rel = if (root.isNotEmpty() && p.startsWith("$root/")) p.removePrefix("$root/") else p
    return rel.substringBeforeLast('/', "")
}

/** The palette's keys, said once at the bottom instead of a lone "esc" chip in the search field. */
@Composable
private fun KeyHints() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyHint("↑↓", stringResource(Res.string.palette_key_navigate))
        KeyHint("↵", stringResource(Res.string.palette_key_open))
        KeyHint("⇥", stringResource(Res.string.palette_key_scope))
        KeyHint("esc", stringResource(Res.string.palette_key_close))
    }
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) { Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
        Text(label, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The editor the palette should resolve editor-aware commands against: the focused tab's path, its live
 * text, and the selection. A snapshot taken when the palette opens; the palette is modal, so the buffer
 * cannot move underneath it.
 */
class PaletteEditorTarget(
    val path: String,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

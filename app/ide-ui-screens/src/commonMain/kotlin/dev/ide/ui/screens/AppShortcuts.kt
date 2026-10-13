package dev.ide.ui.screens

import dev.ide.ui.generated.resources.tests_none_in_file
import dev.ide.ui.backend.TestOutput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.LeftPanelId
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.components.DropdownOverlay
import dev.ide.ui.components.ShortcutHint
import dev.ide.ui.components.TabFileIcon
import dev.ide.ui.ext.APP_COMMAND_EQUIVALENTS
import dev.ide.ui.ext.APP_KEY_DEFAULTS
import dev.ide.ui.ext.AppCommandHost
import dev.ide.ui.ext.AppCommands
import dev.ide.ui.ext.SimpleUiAction
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.shortcut_no_build_task
import dev.ide.ui.generated.resources.shortcut_nothing_to_reopen
import dev.ide.ui.generated.resources.switcher_hint
import dev.ide.ui.generated.resources.switcher_no_match
import dev.ide.ui.generated.resources.switcher_recent_hint
import dev.ide.ui.generated.resources.switcher_recent_title
import dev.ide.ui.generated.resources.switcher_title
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The app-wide keyboard commands for an open project: the [AppCommandHost] runner for every command in
 * [AppCommands], the Ctrl+Tab switcher / Ctrl+E Recent Files popup, the commands' palette entries, and a
 * small toast for a command with nothing to act on. Emitted inside the editor screen's root `Box`, so the
 * popup and the toast draw over both layouts.
 */
@Composable
internal fun BoxScope.AppShortcutsHost(
    state: IdeUiState,
    onOpenSettings: () -> Unit,
    onNewFile: (dir: String) -> Unit,
) {
    val switcher = remember { FileSwitcherState() }
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf<HiddenToolWindows?>(null) }
    val openSettings by rememberUpdatedState(onOpenSettings)
    val newFile by rememberUpdatedState(onNewFile)
    val noBuildTask = stringResource(Res.string.shortcut_no_build_task)
    val noTestsHere = stringResource(Res.string.tests_none_in_file)
    val nothingToReopen = stringResource(Res.string.shortcut_nothing_to_reopen)

    fun focusEditor() { state.editorFocusEpoch++ }

    fun openPalette(filter: String?) {
        state.paletteFilter = filter
        state.paletteOpen = true
    }

    /** A build that only builds: the active variant's assemble, else a module's build, else none. */
    fun buildTaskId(): String? {
        val tasks = state.backend.build.runTasks()
        val assemble = tasks.filter { it.id.startsWith("assemble:") }
        val activeVariant = assemble.firstOrNull { t ->
            val (_, module, variant) = t.id.split(':').let { Triple(it[0], it.getOrNull(1), it.getOrNull(2)) }
            module != null && variant != null && state.backend.build.activeVariant(module) == variant
        }
        return (activeVariant ?: assemble.firstOrNull() ?: tasks.firstOrNull { it.id.startsWith("build:") }
            ?: tasks.firstOrNull { it.id.startsWith("assembleAar:") })?.id
    }

    fun toggleToolWindows(): Boolean {
        val saved = hidden
        val anyOpen = state.selectedLeftPanel != null || state.selectedRightPanel != null || state.consoleOpen
        when {
            saved == null && anyOpen -> {
                hidden = HiddenToolWindows(state.selectedLeftPanel, state.selectedRightPanel, state.consoleOpen)
                state.selectedLeftPanel = null
                state.selectedRightPanel = null
                state.consoleOpen = false
                focusEditor()
            }
            saved != null -> {
                hidden = null
                saved.left?.let { state.selectLeftPanel(it) }
                saved.right?.let { state.selectRightPanel(it) }
                state.consoleOpen = saved.console
            }
            else -> return false
        }
        return true
    }

    fun commitSwitcher(index: Int = switcher.selected) {
        val item = switcher.visible.getOrNull(index)
        switcher.close()
        if (item == null) return
        val open = state.openFiles.indexOfFirst { it.path == item.path }
        when {
            open >= 0 -> { state.activeIndex = open; focusEditor() }
            item.path.startsWith(LIBRARY_PREFIX) -> state.openLibrary(item.path)
            // Focus once the tab exists: its editor only answers a focus request made after it appears.
            else -> scope.launch { state.openSuspend(item.path, item.name); focusEditor() }
        }
    }

    fun switcherKey(ev: KeyEvent): Boolean {
        if (!switcher.open) return false
        val down = ev.type == KeyEventType.KeyDown
        val size = switcher.visible.size
        fun move(delta: Int) { if (size > 0) switcher.selected = ((switcher.selected + delta) % size + size) % size }
        when {
            // The Switcher commits when Ctrl goes up, as in IntelliJ: tap Ctrl+Tab to flip to the last file, hold
            // Ctrl and tap Tab to walk further back.
            !switcher.sticky && ev.type == KeyEventType.KeyUp && (ev.key == Key.CtrlLeft || ev.key == Key.CtrlRight) ->
                commitSwitcher()
            !down -> Unit
            ev.key == Key.Tab -> move(if (ev.isShiftPressed) -1 else 1)
            ev.key == Key.DirectionDown -> move(1)
            ev.key == Key.DirectionUp -> move(-1)
            ev.key == Key.Enter || ev.key == Key.NumPadEnter -> commitSwitcher()
            ev.key == Key.Escape -> switcher.close()
            // Delete closes the highlighted tab without leaving the list (Backspace too, outside Recent Files,
            // where it edits the filter).
            ev.key == Key.Delete || (ev.key == Key.Backspace && !switcher.sticky) -> {
                val item = switcher.visible.getOrNull(switcher.selected)
                state.openFiles.firstOrNull { it.path == item?.path }?.let { tab ->
                    state.close(tab)
                    switcher.items = switcher.items.map { if (it.path == tab.path) it.copy(open = false) else it }
                        .filter { switcher.sticky || it.open }
                    switcher.selected = switcher.selected.coerceAtMost((switcher.visible.size - 1).coerceAtLeast(0))
                    if (switcher.visible.isEmpty()) switcher.close()
                }
            }
            ev.key == Key.Backspace -> { switcher.filter = switcher.filter.dropLast(1); switcher.selected = 0 }
            switcher.sticky && !ev.isCtrlPressed && !ev.isAltPressed && !ev.isMetaPressed && ev.utf16CodePoint >= 32 -> {
                switcher.filter += ev.utf16CodePoint.toChar()
                switcher.selected = 0
            }
        }
        // Everything while it is open: a stray letter must not land in the editor underneath.
        return true
    }

    fun openSwitcher(sticky: Boolean, backwards: Boolean): Boolean {
        val tabs = state.openTabsByRecency().map { SwitcherItem(it.path, it.name, open = true, modified = it.modified) }
        val items = if (!sticky) tabs else {
            val open = tabs.associateBy { it.path }
            val recent = state.recentPaths.map { p -> open[p] ?: SwitcherItem(p, state.recentName(p), open = false, modified = false) }
            recent + tabs.filter { t -> recent.none { it.path == t.path } }
        }
        if (items.isEmpty()) return false
        switcher.items = items
        switcher.sticky = sticky
        switcher.filter = ""
        // The current file is first; start on the one before it, so a quick Ctrl+Tab flips between the two.
        switcher.selected = when {
            items.size == 1 -> 0
            backwards -> items.lastIndex
            else -> 1
        }
        switcher.open = true
        return true
    }

    val runner = remember {
        AppCommandHost.Runner { id ->
            val active = state.active
            when {
                id == AppCommands.SWITCHER || id == AppCommands.SWITCHER_BACK ->
                    return@Runner switcher.open || openSwitcher(sticky = false, backwards = id == AppCommands.SWITCHER_BACK)
                id == AppCommands.RECENT_FILES -> return@Runner openSwitcher(sticky = true, backwards = false)
                id == AppCommands.NEXT_TAB || id == AppCommands.PREVIOUS_TAB -> {
                    if (state.openFiles.size < 2) return@Runner false
                    state.cycleTab(if (id == AppCommands.NEXT_TAB) 1 else -1)
                    focusEditor()
                }
                id == AppCommands.CLOSE_TAB -> state.close(active ?: return@Runner false)
                id == AppCommands.CLOSE_OTHER_TABS -> state.closeOthers(active ?: return@Runner false)
                id == AppCommands.CLOSE_ALL_TABS -> if (state.openFiles.isEmpty()) return@Runner false else state.closeAll()
                id == AppCommands.REOPEN_CLOSED_TAB -> if (!state.reopenClosedTab()) toast = nothingToReopen
                id.startsWith(AppCommands.GO_TO_TAB_PREFIX) -> {
                    val n = id.removePrefix(AppCommands.GO_TO_TAB_PREFIX).toIntOrNull() ?: return@Runner false
                    val index = if (n == 9) state.openFiles.lastIndex else n - 1
                    if (index !in state.openFiles.indices) return@Runner false
                    state.activeIndex = index
                    focusEditor()
                }

                id == AppCommands.SEARCH_EVERYWHERE -> openPalette(null)
                id == AppCommands.GO_TO_FILE -> openPalette("Files")
                id == AppCommands.GO_TO_CLASS -> openPalette("Symbols")
                id == AppCommands.GO_TO_SYMBOL -> openPalette("Members")
                id == AppCommands.FIND_ACTION -> openPalette("Commands")
                id == AppCommands.FIND_IN_FILES -> {
                    // The selection seeds the query, as in IntelliJ; a multi-line selection is not a search.
                    state.findInFilesSeed = active?.session?.selectedText()?.takeIf { it.isNotBlank() && '\n' !in it }
                    state.findInFilesEpoch++
                    state.selectLeftPanel(LeftPanelId.SEARCH)
                }

                id == AppCommands.TOGGLE_PROJECT -> {
                    if (state.selectedLeftPanel == LeftPanelId.FILES) { state.selectedLeftPanel = null; focusEditor() }
                    else state.selectLeftPanel(LeftPanelId.FILES)
                }
                id == AppCommands.TOGGLE_CONSOLE -> {
                    state.consoleOpen = !state.consoleOpen
                    if (!state.consoleOpen) focusEditor()
                }
                id == AppCommands.HIDE_ALL_TOOL_WINDOWS -> return@Runner toggleToolWindows()
                id == AppCommands.FOCUS_EDITOR -> if (active == null) return@Runner false else focusEditor()

                id == AppCommands.RUN -> {
                    if (!state.backend.build.supported()) return@Runner false
                    state.consoleOpen = true
                    state.requestRun { state.backend.build.runBuild() }
                }
                id == AppCommands.RUN_TESTS -> {
                    // The file's test classes, from the run marks its editor already shows.
                    val taskIds = active?.session?.gutterMarks.orEmpty()
                        .mapNotNull { it.actionId?.takeIf { a -> a.startsWith(TestOutput.GUTTER_ACTION_PREFIX) } }
                        .map { it.removePrefix(TestOutput.GUTTER_ACTION_PREFIX) }
                    val targets = taskIds.mapNotNull { TestOutput.parseTaskId(it) }
                        .filter { (_, t) -> t.singleOrNull()?.contains('#') == false }
                    val module = targets.firstOrNull()?.first
                    // Local or instrumented, as the file's own marks run it.
                    val kind = taskIds.firstNotNullOfOrNull { TestOutput.kindOf(it) } ?: TestOutput.LOCAL
                    if (module == null) toast = noTestsHere
                    else {
                        state.consoleOpen = true
                        state.requestRun { state.backend.build.runTask(TestOutput.taskId(module, targets.flatMap { it.second }.distinct(), kind)) }
                    }
                }
                id == AppCommands.BUILD -> {
                    if (!state.backend.build.supported()) return@Runner false
                    val task = buildTaskId()
                    if (task == null) toast = noBuildTask
                    else {
                        state.consoleOpen = true
                        state.requestRun { state.backend.build.runTask(task) }
                    }
                }
                id == AppCommands.STOP -> {
                    if (state.backend.build.buildState.value.status != RunStatus.Running) return@Runner false
                    state.backend.build.stopBuild()
                }
                id == AppCommands.SAVE_ALL -> state.openFiles.filter { it.modified && !it.readOnly }.forEach(state::save)
                id == AppCommands.SETTINGS -> openSettings()
                id == AppCommands.NEW_FILE -> {
                    val path = active?.path?.takeUnless { it.startsWith(LIBRARY_PREFIX) }
                    val dir = path?.substring(0, maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')).coerceAtLeast(0))
                        ?.takeIf { it.isNotEmpty() }
                    newFile(dir ?: state.backend.project.rootPath)
                }
                else -> return@Runner false
            }
            true
        }
    }

    DisposableEffect(runner) {
        val registration = AppCommandHost.install(runner)
        val interceptor: (KeyEvent) -> Boolean = ::switcherKey
        AppCommandHost.interceptor = interceptor
        // The commands are also palette entries ("Find action"), each showing its shortcut. Not the ones that
        // open the palette themselves, nor the per-position tab jumps, which would only crowd the list.
        val inPalette = APP_KEY_DEFAULTS.filter { d ->
            d.actionId !in PALETTE_EXCLUDED && d.actionId !in APP_COMMAND_EQUIVALENTS.values &&
                !d.actionId.startsWith(AppCommands.GO_TO_TAB_PREFIX)
        }
        val actions = inPalette.map { d ->
            UiActionRegistry.register(
                SimpleUiAction(d.actionId, d.title, setOf(UiActionPlaces.COMMAND_PALETTE), order = 2000) {
                    AppCommandHost.run(d.actionId)
                },
            )
        }
        onDispose {
            registration.dispose()
            if (AppCommandHost.interceptor === interceptor) AppCommandHost.interceptor = null
            actions.forEach { it.dispose() }
        }
    }

    FileSwitcher(switcher, onPick = { commitSwitcher(it) }, onDismiss = { switcher.close() })

    LaunchedEffect(toast) {
        if (toast != null) { delay(2600); toast = null }
    }
    AnimatedVisibility(
        visible = toast != null,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Text(
            toast.orEmpty(),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(Ca.radius.md))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A decompiled/attached-source library tab's path scheme (AppState's own constant is module-internal). */
private const val LIBRARY_PREFIX = "library://"

/** Commands that open the palette themselves, or only make sense held down, so the palette does not list them. */
private val PALETTE_EXCLUDED = setOf(
    AppCommands.SEARCH_EVERYWHERE, AppCommands.FIND_ACTION, AppCommands.GO_TO_FILE, AppCommands.GO_TO_CLASS,
    AppCommands.GO_TO_SYMBOL, AppCommands.SWITCHER, AppCommands.SWITCHER_BACK,
)

/** The tool windows "Hide all" put away, to bring back on the second press. */
private class HiddenToolWindows(val left: String?, val right: String?, val console: Boolean)

internal data class SwitcherItem(val path: String, val name: String, val open: Boolean, val modified: Boolean)

/** The Ctrl+Tab switcher (commits when Ctrl is released) or, [sticky], Ctrl+E's Recent Files (stays open). */
@Stable
internal class FileSwitcherState {
    var open by mutableStateOf(false)
    var sticky by mutableStateOf(false)
    var items by mutableStateOf<List<SwitcherItem>>(emptyList())
    var selected by mutableIntStateOf(0)
    var filter by mutableStateOf("")

    val visible: List<SwitcherItem>
        get() = if (filter.isEmpty()) items else items.filter { it.name.contains(filter, ignoreCase = true) }

    fun close() {
        open = false
        filter = ""
    }
}

@Composable
private fun FileSwitcher(state: FileSwitcherState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    DropdownOverlay(visible = state.open, onDismiss = onDismiss, topPadding = 96.dp) {
        val items = state.visible
        val list = rememberLazyListState()
        LaunchedEffect(state.selected, items.size) {
            if (state.selected in items.indices) list.animateScrollToItem(state.selected)
        }
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                .padding(vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (state.sticky) Res.string.switcher_recent_title else Res.string.switcher_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (state.filter.isNotEmpty()) {
                    Text(state.filter, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    ShortcutHint(if (state.sticky) AppCommands.RECENT_FILES else AppCommands.SWITCHER)
                }
            }
            if (items.isEmpty()) {
                Text(
                    stringResource(Res.string.switcher_no_match),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightInRows(items.size), state = list) {
                itemsIndexed(items, key = { _, it -> it.path }) { index, item ->
                    SwitcherRow(item, selected = index == state.selected, onClick = { onPick(index) })
                }
            }
            Text(
                stringResource(if (state.sticky) Res.string.switcher_recent_hint else Res.string.switcher_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Up to ten rows tall; the list scrolls past that. */
private fun Modifier.heightInRows(count: Int): Modifier =
    this.height((40 * count.coerceIn(1, 10)).dp)

@Composable
private fun SwitcherRow(item: SwitcherItem, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 6.dp)
            .background(if (selected) scheme.secondaryContainer else scheme.surface.copy(alpha = 0f), RoundedCornerShape(Ca.radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TabFileIcon(item.name)
        // The name and its unsaved dot take the free width; the folder sits at the row's end.
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                item.name,
                modifier = Modifier.weight(1f, fill = false),
                color = when {
                    selected -> scheme.onSecondaryContainer
                    item.open -> scheme.onSurface
                    else -> scheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.modified) Box(Modifier.size(6.dp).background(Ide.colors.warning, CircleShape))
        }
        Text(
            item.path.substringBeforeLast('/').substringAfterLast('/'),
            color = scheme.outline,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

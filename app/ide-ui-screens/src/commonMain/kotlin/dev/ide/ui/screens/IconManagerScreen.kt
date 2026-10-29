package dev.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IconSnippets
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconRef
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.UiNavTarget
import dev.ide.ui.backend.UiVectorGroup
import dev.ide.ui.backend.UiVectorNode
import dev.ide.ui.backend.UiVectorPath
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.drawCheckerboard
import dev.ide.ui.editor.preview.drawUiDrawable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.icons_add_as
import dev.ide.ui.generated.resources.icons_add_count
import dev.ide.ui.generated.resources.icons_app_icon_short
import dev.ide.ui.generated.resources.icons_app_icon_subtitle
import dev.ide.ui.generated.resources.icons_app_icon_title
import dev.ide.ui.generated.resources.icons_batch_added
import dev.ide.ui.generated.resources.icons_batch_existing
import dev.ide.ui.generated.resources.icons_batch_failed
import dev.ide.ui.generated.resources.icons_colour
import dev.ide.ui.generated.resources.icons_colour_original
import dev.ide.ui.generated.resources.icons_compose_add_hint
import dev.ide.ui.generated.resources.icons_compose_empty
import dev.ide.ui.generated.resources.icons_configurations
import dev.ide.ui.generated.resources.icons_conflict_body
import dev.ide.ui.generated.resources.icons_conflict_title
import dev.ide.ui.generated.resources.icons_copied
import dev.ide.ui.generated.resources.icons_copy
import dev.ide.ui.generated.resources.icons_copy_to
import dev.ide.ui.generated.resources.icons_delete
import dev.ide.ui.generated.resources.icons_delete_body
import dev.ide.ui.generated.resources.icons_delete_body_used
import dev.ide.ui.generated.resources.icons_delete_title
import dev.ide.ui.generated.resources.icons_edit_settings
import dev.ide.ui.generated.resources.icons_empty
import dev.ide.ui.generated.resources.icons_filled
import dev.ide.ui.generated.resources.icons_finding_usages
import dev.ide.ui.generated.resources.icons_import
import dev.ide.ui.generated.resources.icons_import_svg
import dev.ide.ui.generated.resources.icons_insert
import dev.ide.ui.generated.resources.icons_license
import dev.ide.ui.generated.resources.icons_load_set
import dev.ide.ui.generated.resources.icons_loading_set
import dev.ide.ui.generated.resources.icons_long_press_hint
import dev.ide.ui.generated.resources.icons_matches_in
import dev.ide.ui.generated.resources.icons_more_actions
import dev.ide.ui.generated.resources.icons_name
import dev.ide.ui.generated.resources.icons_no_repositories
import dev.ide.ui.generated.resources.icons_open_file
import dev.ide.ui.generated.resources.icons_project_empty
import dev.ide.ui.generated.resources.icons_reference
import dev.ide.ui.generated.resources.icons_rename
import dev.ide.ui.generated.resources.icons_rename_note
import dev.ide.ui.generated.resources.icons_rename_title
import dev.ide.ui.generated.resources.icons_replace
import dev.ide.ui.generated.resources.icons_res_type
import dev.ide.ui.generated.resources.icons_search_hint
import dev.ide.ui.generated.resources.icons_selected_count
import dev.ide.ui.generated.resources.icons_size
import dev.ide.ui.generated.resources.icons_style_outlined
import dev.ide.ui.generated.resources.icons_style_rounded
import dev.ide.ui.generated.resources.icons_style_sharp
import dev.ide.ui.generated.resources.icons_tab_compose
import dev.ide.ui.generated.resources.icons_tab_library
import dev.ide.ui.generated.resources.icons_tab_project
import dev.ide.ui.generated.resources.icons_target
import dev.ide.ui.generated.resources.icons_title
import dev.ide.ui.generated.resources.icons_unused
import dev.ide.ui.generated.resources.icons_usages_title
import dev.ide.ui.generated.resources.icons_used_in
import dev.ide.ui.generated.resources.icons_used_once
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsKeyed
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import dev.ide.ui.uniqueKeysIndexed
import org.jetbrains.compose.resources.stringResource

/** The widest the content grows to before it stays centred, so a desktop window is not one giant grid row. */
private val CONTENT_MAX = 880.dp

/** A control cluster is narrower than the grid: a 900dp-wide text field is unusable. */
private val FORM_MAX = 620.dp

/**
 * The Icon Manager: browse the project's own drawables, the registered icon libraries, and the Compose icons
 * on the module's classpath, then add one to the project, reference it from the file you are editing, or send
 * it to the app-icon studio. Long-pressing tiles ticks several for one batch import.
 *
 * Everything renders from the same neutral [UiDrawable] the resource preview pane uses, so a grid tile looks
 * exactly like the file that gets written. Content is width-capped and centred rather than stretched, which
 * keeps the tile grid and the forms readable from a phone up to a desktop window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconManagerScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    /** Open the app-icon studio, with [UiIconLayer] as its foreground when the user picked an icon for it. */
    onOpenAppIconStudio: (UiIconLayer?) -> Unit = {},
    /** Write a reference to the icon into the editor, in the form that file's language wants. */
    onInsert: ((UiIconRef) -> Unit)? = null,
    /** The editor buffer an insertion would go into, for labelling the action. Null when no tab is open. */
    insertionTarget: UiInsertionTarget? = null,
    /** A `res/` directory to preselect, when opened from a file-tree node. */
    initialResDir: String? = null,
    fileActions: FileActions = FileActions.None,
    /** Open a project file in the editor at an offset; null where there is no editor to open it in. */
    onOpenFile: ((path: String, offset: Int) -> Unit)? = null,
    /** Files were renamed (old path to new) or deleted here, so open editor tabs can follow. */
    onFilesRelocated: (Map<String, String>) -> Unit = {},
) {
    val state = rememberIconManagerState(backend, initialResDir)
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val copiedLabel = stringResource(Res.string.icons_copied)
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var showingUsages by remember { mutableStateOf(false) }

    state.message?.let { text ->
        LaunchedEffect(text) {
            snackbar.showSnackbar(text)
            state.dismissMessage()
        }
    }
    state.batchOutcome?.let { outcome ->
        val text = batchOutcomeText(outcome)
        LaunchedEffect(outcome) {
            snackbar.showSnackbar(text)
            state.dismissBatchOutcome()
        }
    }

    ExpressiveScaffold(
        title = stringResource(Res.string.icons_title),
        onBack = onBack,
        large = false,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // The sheet is pinned rather than modal so the grid stays visible, which means it has to be capped:
            // with every control open it is taller than a phone, and an uncapped sheet would squeeze the grid to
            // nothing. Above the cap its content scrolls.
            val sheetMax = maxHeight * 0.55f
            Column(Modifier.fillMaxSize()) {
                IconBrowser(
                    state = state,
                    modifier = Modifier.weight(1f),
                    // While several are ticked, a tap adds to the batch rather than opening one icon.
                    onClick = { item -> if (state.checked.isNotEmpty()) state.toggleChecked(item) else state.select(item) },
                    onLongClick = state::toggleChecked,
                    isSelected = { item -> state.selection?.let(state::keyOf) == state.keyOf(item) },
                    projectHeader = {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIconCard { onOpenAppIconStudio(null) }
                            if (fileActions.canPickFile) SvgImportRow(state, fileActions)
                        }
                    },
                )

                val selection = state.selection
                when {
                    state.checked.isNotEmpty() -> BatchSheet(state, sheetMax)
                    selection != null -> DetailSheet(
                        state = state,
                        selection = selection,
                        insertionTarget = insertionTarget,
                        maxHeight = sheetMax,
                        onInsert = onInsert,
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            state.showMessage(copiedLabel)
                        },
                        onUseAsAppIcon = { layer -> onOpenAppIconStudio(layer) },
                        onRename = { renaming = true },
                        onDelete = { confirmingDelete = true },
                        onShowUsages = { showingUsages = true },
                        onOpenFile = onOpenFile,
                    )
                }
            }
        }
    }

    state.conflictPath?.let { existing ->
        val name = existing.substringAfterLast('/').substringAfterLast('\\')
        AlertDialog(
            onDismissRequest = state::dismissConflict,
            title = { Text(stringResource(Res.string.icons_conflict_title, name)) },
            text = { Text(stringResource(Res.string.icons_conflict_body, existing)) },
            confirmButton = {
                TextButton(onClick = { state.import(replace = true) }) {
                    Text(stringResource(Res.string.icons_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissConflict) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    // The dialogs act on the selected project icon, so they close with its selection.
    val project = state.selection as? IconSelection.FromProject
    LaunchedEffect(project) {
        if (project == null) {
            renaming = false
            confirmingDelete = false
            showingUsages = false
        }
    }
    if (project == null) return
    if (renaming) {
        RenameIconDialog(
            current = project.icon.name,
            onDismiss = { renaming = false },
            onRename = { name ->
                state.renameSelected(name) { ok, moved ->
                    if (ok) {
                        renaming = false
                        onFilesRelocated(moved)
                    }
                }
            },
        )
    }
    if (confirmingDelete) {
        DeleteIconDialog(
            name = project.icon.name,
            files = project.icon.configurations.size,
            usages = state.usages?.size ?: 0,
            onDismiss = { confirmingDelete = false },
            onDelete = {
                confirmingDelete = false
                state.deleteSelected { onFilesRelocated(emptyMap()) }
            },
        )
    }
    if (showingUsages) {
        UsagesDialog(
            name = project.icon.name,
            usages = state.usages.orEmpty(),
            onDismiss = { showingUsages = false },
            onOpen = onOpenFile?.let { open ->
                { target: UiNavTarget ->
                    showingUsages = false
                    open(target.path, target.offset)
                }
            },
        )
    }
}

@Composable
private fun batchOutcomeText(outcome: BatchOutcome): String = buildList {
    add(stringResource(Res.string.icons_batch_added, outcome.added))
    if (outcome.existing > 0) add(stringResource(Res.string.icons_batch_existing, outcome.existing))
    if (outcome.failed.isNotEmpty()) add(stringResource(Res.string.icons_batch_failed, outcome.failed.joinToString()))
}.joinToString(" · ")

// ---------------------------------------------------------------------------
// The browser: sections, search, style and the grid
// ---------------------------------------------------------------------------

/** What a tile does when it is tapped or long-pressed, and how it knows it is the selected one. */
private class TileActions(
    val onClick: (IconSelection) -> Unit,
    val onLongClick: ((IconSelection) -> Unit)?,
    val isSelected: (IconSelection) -> Boolean,
)

/**
 * Sections, search, the library and style filters, and the icon grid: everything about finding an icon, and
 * nothing about what happens to it. The Icon Manager puts its detail sheet under this; the app-icon studio's
 * picker uses it on its own, so both browse the same way.
 */
@Composable
internal fun IconBrowser(
    state: IconManagerState,
    onClick: (IconSelection) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((IconSelection) -> Unit)? = null,
    isSelected: (IconSelection) -> Boolean = { false },
    /** Scrolls with the Project grid, above its tiles. */
    projectHeader: (@Composable () -> Unit)? = null,
) {
    val tiles = TileActions(onClick, onLongClick, isSelected)
    Column(modifier.fillMaxWidth()) {
        SectionTabs(state)
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SearchField(state)
            if (state.tab == IconTab.Library) RepositoryFilters(state)
            StyleToolbar(state)

            Box(Modifier.weight(1f).widthIn(max = CONTENT_MAX).fillMaxWidth()) {
                when {
                    state.loadingResults && currentCount(state) == 0 ->
                        Centered { CircularProgressIndicator() }

                    state.tab == IconTab.Library -> LibraryGrid(state, tiles)
                    state.tab == IconTab.Project -> ProjectGrid(state, tiles, projectHeader)
                    else -> ComposeGrid(state, tiles)
                }
            }
        }
    }
}

private fun currentCount(state: IconManagerState): Int = when (state.tab) {
    IconTab.Library -> state.results.size
    IconTab.Project -> state.projectIcons.size
    IconTab.Compose -> state.composeIcons.size
}

@Composable
private fun sectionLabel(tab: IconTab): String = when (tab) {
    IconTab.Project -> stringResource(Res.string.icons_tab_project)
    IconTab.Library -> stringResource(Res.string.icons_tab_library)
    IconTab.Compose -> stringResource(Res.string.icons_tab_compose)
}

/** The sections, each with its match count while there is a query, so a search reaches all three at once. */
@Composable
private fun SectionTabs(state: IconManagerState) {
    val tabs = listOf(IconTab.Project, IconTab.Library, IconTab.Compose)
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(state.tab).coerceAtLeast(0)) {
        tabs.forEach { tab ->
            val count = state.matchesIn(tab)
            Tab(
                selected = state.tab == tab,
                onClick = { state.selectTab(tab) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sectionLabel(tab), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                        if (count != null) CountBadge(count, highlighted = state.tab == tab)
                    }
                },
            )
        }
    }
}

@Composable
private fun CountBadge(count: Int, highlighted: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (highlighted) scheme.primaryContainer else scheme.surfaceContainerHighest,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SearchField(state: IconManagerState) {
    OutlinedTextField(
        value = state.query,
        onValueChange = state::updateQuery,
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        placeholder = { Text(stringResource(Res.string.icons_search_hint)) },
        leadingIcon = { Icon(CaIcons.search, null, Modifier.size(18.dp)) },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { state.updateQuery("") }) {
                    Icon(CaIcons.close, stringResource(Res.string.close), Modifier.size(18.dp))
                }
            }
        },
        modifier = Modifier
            .widthIn(max = FORM_MAX)
            .fillMaxWidth()
            .padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s2),
    )
}

@Composable
private fun RepositoryFilters(state: IconManagerState) {
    if (state.repositories.isEmpty()) {
        Caption(stringResource(Res.string.icons_no_repositories), Modifier.padding(Ca.spacing.s3))
        return
    }
    val searching = state.query.isNotBlank()
    LazyRow(
        Modifier.widthIn(max = CONTENT_MAX).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        contentPadding = PaddingValues(
            horizontal = Ca.spacing.s4,
            vertical = Ca.spacing.s1,
        ),
    ) {
        itemsKeyed(state.repositories, key = { it.id }) { repo ->
            val selected = repo.id == state.selectedRepoId
            // While searching, each set shows its matches, so one with none can point at one that has some.
            val count = when {
                !searching -> repo.iconCount.takeIf { it > 0 }
                selected -> state.results.size
                else -> state.otherRepoMatches[repo.id]
            }
            FilterChip(
                selected = selected,
                onClick = { state.selectRepo(repo.id) },
                label = { Text(repo.displayName, maxLines = 1) },
                trailingIcon = when {
                    state.loadingRepoId == repo.id -> {
                        { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
                    }

                    count != null -> {
                        { Text(count.toString(), style = MaterialTheme.typography.labelSmall) }
                    }

                    else -> null
                },
            )
        }
    }

    val repo = state.selectedRepo()
    if (repo != null && repo.requiresNetwork && !repo.loaded) {
        Row(
            Modifier.padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        ) {
            if (state.loadingRepoId == repo.id) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Caption(stringResource(Res.string.icons_loading_set))
            } else {
                // Selecting the set downloads it; this is only reached when that failed, as a retry.
                FilledTonalButton(onClick = { state.loadRepo(repo.id) }) {
                    Icon(CaIcons.download, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Ca.spacing.s2))
                    Text(stringResource(Res.string.icons_load_set, repo.displayName))
                }
            }
        }
    }
}

/**
 * The style families and the fill switch, for the whole grid at once: comparing outlined against rounded is a
 * glance at the grid, not a tap on every icon. Hidden when the section has nothing to switch.
 */
@Composable
private fun StyleToolbar(state: IconManagerState) {
    val styles = state.availableStyles()
    val fill = state.sectionSupportsFill()
    if (styles.size <= 1 && !fill) return
    val chosen = state.effectiveStyle()
    // A few families share the row with the fill switch; more than that (Compose has five) scroll instead.
    val fits = styles.size <= 3
    Row(
        Modifier
            .widthIn(max = FORM_MAX)
            .fillMaxWidth()
            .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            .padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        if (styles.size > 1) {
            SingleChoiceSegmentedButtonRow(if (fits) Modifier.weight(1f) else Modifier) {
                styles.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = chosen == style,
                        onClick = { state.selectVariant(state.variant.copy(style = style)) },
                        shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                        // No check mark: the selected segment's fill says it, and the row stays narrow.
                        icon = {},
                        label = { Text(styleLabel(style), maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        if (fill) {
            FilterChip(
                selected = state.variant.filled,
                onClick = { state.selectVariant(state.variant.copy(filled = !state.variant.filled)) },
                label = { Text(stringResource(Res.string.icons_filled), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SvgImportRow(state: IconManagerState, fileActions: FileActions) {
    FilledTonalButton(
        onClick = { fileActions.pickFile { path -> if (path != null) state.importSvgFile(path) } },
        enabled = !state.importing,
        modifier = Modifier.padding(vertical = Ca.spacing.s1),
    ) {
        Icon(CaIcons.download, null, Modifier.size(16.dp))
        Spacer(Modifier.size(Ca.spacing.s2))
        Text(stringResource(Res.string.icons_import_svg))
    }
}

// ---------------------------------------------------------------------------
// Grids
// ---------------------------------------------------------------------------

@Composable
private fun LibraryGrid(state: IconManagerState, tiles: TileActions) {
    val entries = state.results
    if (entries.isEmpty()) {
        NoMatches(state, stringResource(Res.string.icons_empty))
        return
    }
    IconGrid(entries.size, key = { entries[it].name }) { index ->
        val entry = entries[index]
        val item = IconSelection.FromRepo(entry)
        val key = state.entryKey(entry)
        LaunchedEffect(key) { state.ensureRepoArtwork(entry) }
        IconTile(
            label = entry.displayName,
            drawable = state.artworkFor(key),
            selected = tiles.isSelected(item),
            checked = state.isChecked(item),
            onClick = { tiles.onClick(item) },
            onLongClick = tiles.onLongClick?.let { long -> { long(item) } },
        )
    }
}

@Composable
private fun ProjectGrid(state: IconManagerState, tiles: TileActions, header: (@Composable () -> Unit)?) {
    val icons = state.filteredProjectIcons()
    if (icons.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            header?.invoke()
            Box(Modifier.weight(1f)) {
                if (state.projectIcons.isEmpty()) {
                    Centered { EmptyNote(stringResource(Res.string.icons_project_empty)) }
                } else {
                    NoMatches(state, stringResource(Res.string.icons_empty))
                }
            }
        }
        return
    }
    IconGrid(
        icons.size,
        key = { "${icons[it].moduleName}/${icons[it].resType}/${icons[it].name}" },
        header = header,
    ) { index ->
        val icon = icons[index]
        // The default configuration is what a preview should show; fall back to the first declared one.
        val config = icon.configurations.firstOrNull { it.qualifier.isEmpty() } ?: icon.configurations.first()
        val item = IconSelection.FromProject(icon, config)
        LaunchedEffect(config.path) { state.ensureResourceArtwork(config) }
        IconTile(
            label = icon.name,
            drawable = state.artworkFor("res:${config.path}"),
            raster = state.rasterFor(config.path),
            badge = icon.configurations.size.takeIf { it > 1 },
            selected = tiles.isSelected(item),
            onClick = { tiles.onClick(item) },
        )
    }
}

@Composable
private fun ComposeGrid(state: IconManagerState, tiles: TileActions) {
    if (state.composeIcons.isEmpty()) {
        Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                EmptyNote(stringResource(Res.string.icons_compose_empty))
                Caption(
                    stringResource(Res.string.icons_compose_add_hint),
                    Modifier.padding(horizontal = Ca.spacing.s8),
                    align = TextAlign.Center,
                )
            }
        }
        return
    }
    val entries = state.filteredComposeIcons()
    if (entries.isEmpty()) {
        NoMatches(state, stringResource(Res.string.icons_empty))
        return
    }
    IconGrid(entries.size, key = { entries[it].name }) { index ->
        val entry = entries[index]
        val item = IconSelection.FromCompose(entry)
        val key = state.composeKey(entry)
        LaunchedEffect(key) { state.ensureComposeArtwork(entry) }
        IconTile(
            label = entry.displayName,
            drawable = state.artworkFor(key),
            selected = tiles.isSelected(item),
            checked = state.isChecked(item),
            onClick = { tiles.onClick(item) },
            onLongClick = tiles.onLongClick?.let { long -> { long(item) } },
        )
    }
}

/**
 * Nothing matches here: say so, and point at the sections and sets that do have matches, so a search for
 * something the bundled set lacks does not end at an empty page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoMatches(state: IconManagerState, text: String) {
    val elsewhere = listOf(IconTab.Project, IconTab.Library, IconTab.Compose)
        .filter { it != state.tab }
        .mapNotNull { tab -> state.matchesIn(tab)?.takeIf { it > 0 }?.let { tab to it } }
    val otherSets = if (state.tab == IconTab.Library) {
        state.repositories.mapNotNull { repo -> state.otherRepoMatches[repo.id]?.takeIf { it > 0 }?.let { repo to it } }
    } else {
        emptyList()
    }
    Centered {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        ) {
            EmptyNote(text)
            if (elsewhere.isNotEmpty() || otherSets.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2, Alignment.CenterHorizontally),
                    modifier = Modifier.padding(horizontal = Ca.spacing.s6),
                ) {
                    otherSets.forEach { (repo, count) ->
                        SuggestionChip(
                            onClick = { state.selectRepo(repo.id) },
                            label = { Text(stringResource(Res.string.icons_matches_in, count, repo.displayName)) },
                        )
                    }
                    elsewhere.forEach { (tab, count) ->
                        SuggestionChip(
                            onClick = { state.selectTab(tab) },
                            label = { Text(stringResource(Res.string.icons_matches_in, count, sectionLabel(tab))) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The icon tile grid.
 *
 * [key] is deduplicated the way [itemsKeyed] does it for lists, because every caller keys on an icon NAME and
 * a name can legitimately repeat: one repository can carry the same glyph under two styles, and a project can
 * hold the same resource name in two resource types. A repeated key throws out of the grid's measure pass and
 * takes the screen down. [header] spans the full width above the tiles and scrolls away with them.
 */
@Composable
private fun IconGrid(
    count: Int,
    key: (Int) -> Any,
    modifier: Modifier = Modifier.fillMaxSize(),
    header: (@Composable () -> Unit)? = null,
    tile: @Composable (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = Ca.spacing.s3,
            vertical = Ca.spacing.s2,
        ),
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        val keys = uniqueKeysIndexed(List(count) { it }) { _, index -> key(index) }
        items(count, key = { keys[it] }) { index -> tile(index) }
    }
}

/**
 * One icon in the grid: a tonal [Surface] whose selected state is carried by both the container colour and an
 * outline, so it reads without relying on colour alone. [badge] marks how many configurations a project
 * resource has; [checked] marks an icon ticked for a batch, with a check as well as the colour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconTile(
    label: String,
    drawable: UiDrawable?,
    raster: ByteArray? = null,
    badge: Int? = null,
    selected: Boolean,
    checked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val highlighted = selected || checked
    Surface(
        shape = shape,
        color = when {
            checked -> scheme.primaryContainer
            selected -> scheme.secondaryContainer
            else -> scheme.surfaceContainerLow
        },
        border = if (highlighted) BorderStroke(2.dp, scheme.primary) else null,
        modifier = Modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().padding(vertical = Ca.spacing.s3, horizontal = Ca.spacing.s2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    IconArt(drawable, raster, Modifier.size(32.dp))
                    if (badge != null) {
                        Surface(
                            shape = CircleShape,
                            color = scheme.tertiaryContainer,
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Text(
                                badge.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        checked -> scheme.onPrimaryContainer
                        selected -> scheme.onSecondaryContainer
                        else -> scheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            if (checked) {
                Surface(
                    shape = CircleShape,
                    color = scheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(CaIcons.check, null, Modifier.size(14.dp), tint = scheme.onPrimary)
                    }
                }
            }
        }
    }
}

/** Renders whichever form of artwork is available, tinted to the surface's foreground for a flat icon. */
@Composable
private fun IconArt(drawable: UiDrawable?, raster: ByteArray?, modifier: Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    when {
        raster != null -> {
            var bitmap by remember(raster) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(raster) { bitmap = decodeImageBytes(raster) }
            bitmap?.let { Image(it, null, modifier, contentScale = ContentScale.Fit) } ?: Box(modifier)
        }

        drawable != null -> {
            // Recolouring walks the node tree and COPIES it. Inside the draw lambda that ran per tile per
            // frame; it depends only on the artwork and the surface colour, so it belongs in a remember.
            val art = remember(drawable, onSurface) { recolorForSurface(drawable, onSurface) }
            Canvas(modifier) { drawUiDrawable(art, androidx.compose.ui.geometry.Offset.Zero, size) }
        }

        else -> Box(modifier)
    }
}

/**
 * A single-colour icon is published as opaque black, which disappears on a dark surface. Repainting a
 * monochrome vector in the theme's foreground colour is what a real `Icon(tint = …)` would do; a multicolour
 * drawable is left exactly as authored.
 */
private fun recolorForSurface(drawable: UiDrawable, color: Color): UiDrawable {
    if (drawable !is UiDrawable.Vector) return drawable
    val colors = HashSet<Long>()
    collectColors(drawable.nodes, colors)
    if (colors.size != 1) return drawable
    val only = colors.first()
    // Only recolour pure black or pure white, the two "unstyled" conventions.
    if (only != 0xFF000000L && only != 0xFFFFFFFFL) return drawable
    val argb = ((color.alpha * 255).toLong() shl 24) or
        ((color.red * 255).toLong() shl 16) or
        ((color.green * 255).toLong() shl 8) or
        (color.blue * 255).toLong()
    return drawable.copy(nodes = recolor(drawable.nodes, argb))
}

private fun collectColors(nodes: List<UiVectorNode>, out: MutableSet<Long>) {
    for (node in nodes) when (node) {
        is UiVectorPath -> {
            node.fillColor?.let { out += it }
            node.strokeColor?.let { out += it }
        }

        is UiVectorGroup -> collectColors(node.children, out)
    }
}

private fun recolor(nodes: List<UiVectorNode>, argb: Long): List<UiVectorNode> = nodes.map { node ->
    when (node) {
        is UiVectorPath -> node.copy(
            fillColor = node.fillColor?.let { argb },
            strokeColor = node.strokeColor?.let { argb },
        )

        is UiVectorGroup -> node.copy(children = recolor(node.children, argb))
    }
}

// ---------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------

/**
 * The pinned sheet under the grid: a scrolling [body] and an [actions] bar that does not scroll. A capped sheet
 * whose primary button scrolls out of sight is worse than no cap at all.
 */
@Composable
private fun SheetSurface(
    maxHeight: Dp,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().heightIn(max = maxHeight),
        shape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = FORM_MAX)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Ca.spacing.s4)
                    .padding(top = Ca.spacing.s3, bottom = Ca.spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
                content = body,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.widthIn(max = FORM_MAX).fillMaxWidth()
                    .padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s3),
            ) {
                actions()
            }
        }
    }
}

/**
 * The selected icon: what it looks like, its reference, and what can be done with it. How it would be written
 * (name, folder, size, colour, module) folds into one line that opens on a tap, because most adds keep the
 * defaults and a full form on every tap made browsing slow.
 */
@Composable
internal fun DetailSheet(
    state: IconManagerState,
    selection: IconSelection,
    insertionTarget: UiInsertionTarget?,
    maxHeight: Dp,
    onInsert: ((UiIconRef) -> Unit)?,
    onCopy: (String) -> Unit,
    /** Send the icon to the app-icon studio as its foreground; null where there is no studio. */
    onUseAsAppIcon: ((UiIconLayer) -> Unit)?,
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onShowUsages: () -> Unit = {},
    onOpenFile: ((String, Int) -> Unit)? = null,
) {
    val reference = state.referenceFor(insertionTarget)
    val project = selection as? IconSelection.FromProject
    SheetSurface(
        maxHeight = maxHeight,
        body = {
            DetailHeader(
                state,
                selection,
                menu = if (project != null) {
                    { ProjectIconMenu(onRename, onDelete, onOpenFile?.takeIf { !project.config.isRaster }?.let { open -> { open(project.config.path, 0) } }) }
                } else {
                    null
                },
            )
            // Directly under the header, because it is what the pinned Insert and Copy actions will write.
            if (reference != null) ReferenceRow(reference) { state.snippetFor(insertionTarget)?.let(onCopy) }
            if (state.warnings.isNotEmpty()) WarningList(state.warnings)
            if (project != null) {
                UsagesRow(state.usages, onShowUsages)
                // Copying a project icon is only a choice when there is another res/ to copy it to.
                if (state.targets.size > 1) {
                    ImportSummary(state, label = stringResource(Res.string.icons_copy_to)) {
                        FilledTonalButton(onClick = { state.import() }, enabled = !state.importing) {
                            Text(stringResource(Res.string.icons_copy), maxLines = 1)
                        }
                    }
                }
            } else {
                ImportSummary(state, label = stringResource(Res.string.icons_add_as))
                Caption(stringResource(Res.string.icons_long_press_hint))
            }
        },
        actions = {
            ActionRow(state, selection, reference, onInsert, onUseAsAppIcon)
        },
    )
}

/** Several ticked icons: the shared settings they will be added with, and one button that adds them all. */
@Composable
internal fun BatchSheet(state: IconManagerState, maxHeight: Dp) {
    val count = state.checked.size
    SheetSurface(
        maxHeight = maxHeight,
        body = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.icons_selected_count, count),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = state::clearChecked) {
                    Icon(CaIcons.close, stringResource(Res.string.close), Modifier.size(18.dp))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
                items(state.checked) { item ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            IconArt(state.artworkFor(state.keyOf(item)), null, Modifier.size(28.dp))
                        }
                    }
                }
            }
            ImportSummary(state, label = stringResource(Res.string.icons_add_as), showName = false)
        },
        actions = {
            Button(
                onClick = state::importChecked,
                enabled = !state.importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.importing) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(CaIcons.plus, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.icons_add_count, count), maxLines = 1)
            }
        },
    )
}

/**
 * The exact reference the insert and copy actions write, on its own line in a monospace chip. It lives here
 * rather than inside the button labels: a reference like `Icons.Outlined.ShoppingCart` is longer than a button
 * can show, and putting it there made the whole action row clamp and squash.
 */
@Composable
private fun ReferenceRow(reference: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
        FieldLabel(stringResource(Res.string.icons_reference))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = Ca.spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    reference,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(CaIcons.copy, stringResource(Res.string.icons_copy), Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(state: IconManagerState, selection: IconSelection, menu: (@Composable () -> Unit)? = null) {
    val key = state.keyOf(selection)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = Color.Transparent, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { drawCheckerboard(cell = 8f) }
                IconArt(
                    state.artworkFor(key),
                    (selection as? IconSelection.FromProject)?.let { state.rasterFor(it.config.path) },
                    Modifier.size(52.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                labelOf(selection),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitleOf(state, selection)?.let { Caption(it) }
        }
        menu?.invoke()
        IconButton(onClick = state::clearSelection) {
            Icon(CaIcons.close, stringResource(Res.string.close), Modifier.size(18.dp))
        }
    }
}

@Composable
private fun WarningList(warnings: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        warnings.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** How many places name the selected project icon; a tap lists them. */
@Composable
private fun UsagesRow(usages: List<UiNavTarget>?, onShow: () -> Unit) {
    val text = when {
        usages == null -> stringResource(Res.string.icons_finding_usages)
        usages.isEmpty() -> stringResource(Res.string.icons_unused)
        usages.size == 1 -> stringResource(Res.string.icons_used_once)
        else -> stringResource(Res.string.icons_used_in, usages.size)
    }
    val enabled = !usages.isNullOrEmpty()
    Surface(
        onClick = onShow,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = Ca.spacing.s3, vertical = Ca.spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        ) {
            if (usages == null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Symbol(CaSymbols.search, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (enabled) Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The write settings as one line (`drawable/ic_home · 24 dp · app/main`) that opens into the full form. [showName]
 * is false for a batch, where every icon keeps its own suggested name.
 */
@Composable
private fun ImportSummary(
    state: IconManagerState,
    label: String,
    showName: Boolean = true,
    /** Shown under the open form: the action the settings are for, when it is not in the action bar. */
    confirm: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val summary = listOfNotNull(
        if (showName) "${state.resType}/${state.resourceName}" else state.resType,
        "${state.sizeDp} dp",
        state.target?.let(::targetLabel),
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3)) {
        Surface(
            onClick = { open = !open },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = Ca.spacing.s3, vertical = Ca.spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FieldLabel(label)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.tint?.let { argb ->
                    Surface(
                        shape = CircleShape,
                        color = Color(argb.toInt()),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(16.dp),
                        content = {},
                    )
                }
                Icon(
                    if (open) CaIcons.chevronUp else CaIcons.chevronDown,
                    stringResource(Res.string.icons_edit_settings),
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open) {
            ImportControls(state, showName)
            confirm?.invoke()
        }
    }
}

@Composable
private fun ImportControls(state: IconManagerState, showName: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3)) {
        if (showName) {
            OutlinedTextField(
                value = state.resourceName,
                onValueChange = state::updateResourceName,
                label = { Text(stringResource(Res.string.icons_name)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Folder and size share a row: both are narrow, and the sheet has a height budget to keep.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
                FieldLabel(stringResource(Res.string.icons_res_type))
                val folders = listOf("drawable", "mipmap")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    SingleChoiceSegmentedButtonRow {
                        folders.forEachIndexed { index, folder ->
                            SegmentedButton(
                                selected = state.resType == folder,
                                onClick = { state.updateResType(folder) },
                                shape = SegmentedButtonDefaults.itemShape(index, folders.size),
                                label = { Text(folder, maxLines = 1) },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.sizeDp.toString(),
                onValueChange = { state.updateSize(it.filter(Char::isDigit).toIntOrNull() ?: 24) },
                label = { Text(stringResource(Res.string.icons_size)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(104.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
            FieldLabel(stringResource(Res.string.icons_colour))
            // Scrolls: the chip plus the swatches are wider than a phone, and a plain Row would clip them.
            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
                contentPadding = PaddingValues(vertical = Ca.spacing.s1),
            ) {
                item {
                    FilterChip(
                        selected = state.tint == null,
                        onClick = { state.updateTint(null) },
                        label = { Text(stringResource(Res.string.icons_colour_original), maxLines = 1) },
                    )
                }
                items(TINT_SWATCHES) { swatch ->
                    ColorSwatch(swatch, selected = state.tint == swatch) { state.updateTint(swatch) }
                }
            }
        }

        if (state.targets.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
                FieldLabel(stringResource(Res.string.icons_target))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
                    itemsKeyed(state.targets, key = { it.resDirPath }) { candidate ->
                        FilterChip(
                            selected = candidate.resDirPath == state.target?.resDirPath,
                            onClick = { state.selectTarget(candidate) },
                            label = { Text(targetLabel(candidate), maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    state: IconManagerState,
    selection: IconSelection,
    reference: String?,
    onInsert: ((UiIconRef) -> Unit)?,
    onUseAsAppIcon: ((UiIconLayer) -> Unit)?,
) {
    val project = selection as? IconSelection.FromProject
    val appIconLayer = if (onUseAsAppIcon != null) state.appIconLayerFor(selection) else null
    // Short labels only: a button whose text is wider than the row gets clamped by FlowRow and reads as
    // squashed, so the reference itself is shown above instead of inside these.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        if (project == null) {
            Button(onClick = { state.import() }, enabled = !state.importing) {
                if (state.importing) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(CaIcons.plus, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.icons_import), maxLines = 1)
            }
        }

        if (reference != null && onInsert != null) {
            // A project icon has nothing to add, so inserting it is its main action.
            val insert: @Composable () -> Unit = {
                Icon(CaIcons.code, null, Modifier.size(18.dp))
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.icons_insert), maxLines = 1)
            }
            if (project != null) {
                Button(onClick = { state.prepareInsertion(onInsert) }) { insert() }
            } else {
                FilledTonalButton(onClick = { state.prepareInsertion(onInsert) }) { insert() }
            }
        }

        if (appIconLayer != null && onUseAsAppIcon != null) {
            OutlinedButton(onClick = { onUseAsAppIcon(appIconLayer) }) {
                Icon(CaIcons.androidLogo, null, Modifier.size(18.dp))
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.icons_app_icon_short), maxLines = 1)
            }
        }
    }
}

/** A project icon's file actions, behind one button so the action bar keeps to what it is used for. */
@Composable
private fun ProjectIconMenu(onRename: () -> Unit, onDelete: () -> Unit, onOpenFile: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Symbol(CaSymbols.moreVert, stringResource(Res.string.icons_more_actions), size = 20.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.icons_rename)) },
                leadingIcon = { Symbol(CaSymbols.edit, null, size = 20.dp) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            if (onOpenFile != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.icons_open_file)) },
                    leadingIcon = { Icon(CaIcons.file, null, Modifier.size(20.dp)) },
                    onClick = {
                        open = false
                        onOpenFile()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.icons_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Symbol(CaSymbols.delete, null, size = 20.dp, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AppIconCard(onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .widthIn(max = CONTENT_MAX)
            .fillMaxWidth()
            .padding(horizontal = Ca.spacing.s1, vertical = Ca.spacing.s1),
    ) {
        Row(
            Modifier.padding(Ca.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        CaIcons.androidLogo, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.icons_app_icon_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(Res.string.icons_app_icon_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Icon(
                CaIcons.chevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Project icon dialogs
// ---------------------------------------------------------------------------

@Composable
private fun RenameIconDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    val valid = RESOURCE_NAME.matches(name) && name != current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.icons_rename_title, current)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim().lowercase().replace(' ', '_') },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Caption(stringResource(Res.string.icons_rename_note))
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = valid) {
                Text(stringResource(Res.string.icons_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun DeleteIconDialog(name: String, files: Int, usages: Int, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.icons_delete_title, name)) },
        text = {
            Text(
                if (usages > 0) stringResource(Res.string.icons_delete_body_used, files, usages)
                else stringResource(Res.string.icons_delete_body, files),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(Res.string.icons_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun UsagesDialog(
    name: String,
    usages: List<UiNavTarget>,
    onDismiss: () -> Unit,
    /** Open a usage in the editor; null where there is no editor, which leaves the list read-only. */
    onOpen: ((UiNavTarget) -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.icons_usages_title, name)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(usages) { target ->
                    val row = Modifier.fillMaxWidth()
                    Surface(
                        onClick = { onOpen?.invoke(target) },
                        enabled = onOpen != null,
                        color = Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                        modifier = row,
                    ) {
                        Text(
                            target.label,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = Ca.spacing.s2, horizontal = Ca.spacing.s1),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/** A file resource name: what `aapt2` accepts for a drawable or mipmap. */
private val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun ColorSwatch(argb: Long, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(argb.toInt()),
        border = BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
        modifier = Modifier.size(32.dp),
        content = {},
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Ca.spacing.s8),
    )
}

@Composable
private fun styleLabel(style: String): String = when (style) {
    "rounded" -> stringResource(Res.string.icons_style_rounded)
    "sharp" -> stringResource(Res.string.icons_style_sharp)
    "outlined" -> stringResource(Res.string.icons_style_outlined)
    "filled" -> stringResource(Res.string.icons_filled)
    else -> IconSnippets.styleName(style)
}

private fun labelOf(selection: IconSelection): String = when (selection) {
    is IconSelection.FromRepo -> selection.entry.displayName
    is IconSelection.FromCompose -> selection.entry.displayName
    is IconSelection.FromProject -> selection.icon.name
}

@Composable
private fun subtitleOf(state: IconManagerState, selection: IconSelection): String? = when (selection) {
    is IconSelection.FromRepo -> state.selectedRepo()?.let { stringResource(Res.string.icons_license, it.license) }
    is IconSelection.FromProject -> {
        val count = selection.icon.configurations.size
        if (count > 1) stringResource(Res.string.icons_configurations, count)
        else "${selection.icon.moduleName} · ${selection.icon.resType}"
    }

    is IconSelection.FromCompose -> selection.entry.category
}

private fun targetLabel(target: UiIconTarget): String = "${target.moduleName}/${target.sourceSetName}"

/** A small palette covering the common icon tints: the theme neutrals plus a few brand-ish accents. */
private val TINT_SWATCHES = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFF6200EEL, 0xFF03DAC5L, 0xFFE53935L, 0xFF43A047L, 0xFFFB8C00L,
)

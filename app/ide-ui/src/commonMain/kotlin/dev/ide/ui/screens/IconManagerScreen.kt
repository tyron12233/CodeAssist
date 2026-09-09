package dev.ide.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IconSnippets
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiIconRef
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.UiVectorGroup
import dev.ide.ui.backend.UiVectorNode
import dev.ide.ui.backend.UiVectorPath
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.drawCheckerboard
import dev.ide.ui.editor.preview.drawUiDrawable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.icons_app_icon_subtitle
import dev.ide.ui.generated.resources.icons_app_icon_title
import dev.ide.ui.generated.resources.icons_colour
import dev.ide.ui.generated.resources.icons_colour_original
import dev.ide.ui.generated.resources.icons_compose_add_hint
import dev.ide.ui.generated.resources.icons_compose_empty
import dev.ide.ui.generated.resources.icons_configurations
import dev.ide.ui.generated.resources.icons_conflict_body
import dev.ide.ui.generated.resources.icons_conflict_title
import dev.ide.ui.generated.resources.icons_copied
import dev.ide.ui.generated.resources.icons_app_icon_short
import dev.ide.ui.generated.resources.icons_copy
import dev.ide.ui.generated.resources.icons_empty
import dev.ide.ui.generated.resources.icons_filled
import dev.ide.ui.generated.resources.icons_import
import dev.ide.ui.generated.resources.icons_import_svg
import dev.ide.ui.generated.resources.icons_insert
import dev.ide.ui.generated.resources.icons_reference
import dev.ide.ui.generated.resources.icons_license
import dev.ide.ui.generated.resources.icons_load_set
import dev.ide.ui.generated.resources.icons_loading_set
import dev.ide.ui.generated.resources.icons_name
import dev.ide.ui.generated.resources.icons_no_repositories
import dev.ide.ui.generated.resources.icons_project_empty
import dev.ide.ui.generated.resources.icons_replace
import dev.ide.ui.generated.resources.icons_res_type
import dev.ide.ui.generated.resources.icons_search_hint
import dev.ide.ui.generated.resources.icons_size
import dev.ide.ui.generated.resources.icons_style
import dev.ide.ui.generated.resources.icons_style_outlined
import dev.ide.ui.generated.resources.icons_style_rounded
import dev.ide.ui.generated.resources.icons_style_sharp
import dev.ide.ui.generated.resources.icons_tab_compose
import dev.ide.ui.generated.resources.icons_tab_library
import dev.ide.ui.generated.resources.icons_tab_project
import dev.ide.ui.generated.resources.icons_target
import dev.ide.ui.generated.resources.icons_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/** The widest the content grows to before it stays centred, so a desktop window is not one giant grid row. */
private val CONTENT_MAX = 880.dp

/** A control cluster is narrower than the grid: a 900dp-wide text field is unusable. */
private val FORM_MAX = 620.dp

/**
 * The Icon Manager: browse the project's own drawables, the registered icon libraries, and the Compose icons
 * on the module's classpath, then add one to the project, reference it from the file you are editing, or send
 * it to the app-icon studio.
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
    /** Open the app-icon studio, optionally seeded with the icon the user has selected. */
    onOpenAppIconStudio: (iconRepoId: String?, iconName: String?) -> Unit = { _, _ -> },
    /** Write a reference to the icon into the editor, in the form that file's language wants. */
    onInsert: ((UiIconRef) -> Unit)? = null,
    /** The editor buffer an insertion would go into, for labelling the action. Null when no tab is open. */
    insertionTarget: UiInsertionTarget? = null,
    /** A `res/` directory to preselect, when opened from a file-tree node. */
    initialResDir: String? = null,
    fileActions: FileActions = FileActions.None,
) {
    val state = rememberIconManagerState(backend, initialResDir)
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val copiedLabel = stringResource(Res.string.icons_copied)

    state.message?.let { text ->
        LaunchedEffect(text) {
            snackbar.showSnackbar(text)
            state.dismissMessage()
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
        // with warnings plus every control it is taller than a phone, and an uncapped sheet would squeeze the
        // grid to nothing. Above the cap its content scrolls.
        val sheetMax = maxHeight * 0.55f
        Column(Modifier.fillMaxSize()) {
            SectionTabs(state)

            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SearchField(state)
                when (state.tab) {
                    IconTab.Library -> RepositoryFilters(state)
                    IconTab.Project -> if (fileActions.canPickFile) SvgImportRow(state, fileActions)
                    IconTab.Compose -> Unit
                }

                Box(Modifier.weight(1f).widthIn(max = CONTENT_MAX).fillMaxWidth()) {
                    when {
                        state.loadingResults && currentCount(state) == 0 ->
                            Centered { CircularProgressIndicator() }

                        state.tab == IconTab.Library -> LibraryGrid(state)
                        state.tab == IconTab.Project -> ProjectGrid(state, onOpenAppIconStudio)
                        else -> ComposeGrid(state)
                    }
                }
            }

            state.selection?.let { selection ->
                DetailSheet(
                    state = state,
                    selection = selection,
                    insertionTarget = insertionTarget,
                    maxHeight = sheetMax,
                    onInsert = onInsert,
                    onCopy = { text ->
                        clipboard.setText(AnnotatedString(text))
                        state.showMessage(copiedLabel)
                    },
                    onOpenAppIconStudio = onOpenAppIconStudio,
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
}

private fun currentCount(state: IconManagerState): Int = when (state.tab) {
    IconTab.Library -> state.results.size
    IconTab.Project -> state.projectIcons.size
    IconTab.Compose -> state.composeIcons.size
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun SectionTabs(state: IconManagerState) {
    val tabs = listOf(
        IconTab.Project to stringResource(Res.string.icons_tab_project),
        IconTab.Library to stringResource(Res.string.icons_tab_library),
        IconTab.Compose to stringResource(Res.string.icons_tab_compose),
    )
    PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.tab }.coerceAtLeast(0)) {
        tabs.forEach { (tab, label) ->
            Tab(
                selected = state.tab == tab,
                onClick = { state.selectTab(tab) },
                text = { Text(label, maxLines = 1, style = MaterialTheme.typography.titleSmall) },
            )
        }
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
    LazyRow(
        Modifier.widthIn(max = CONTENT_MAX).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        contentPadding = PaddingValues(
            horizontal = Ca.spacing.s4,
            vertical = Ca.spacing.s1,
        ),
    ) {
        items(state.repositories, key = { it.id }) { repo ->
            FilterChip(
                selected = repo.id == state.selectedRepoId,
                onClick = { state.selectRepo(repo.id) },
                label = { Text(repo.displayName, maxLines = 1) },
                trailingIcon = {
                    if (repo.iconCount > 0) {
                        Text(repo.iconCount.toString(), style = MaterialTheme.typography.labelSmall)
                    }
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
                FilledTonalButton(onClick = { state.loadRepo(repo.id) }) {
                    Icon(CaIcons.download, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Ca.spacing.s2))
                    Text(stringResource(Res.string.icons_load_set, repo.displayName))
                }
            }
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
private fun LibraryGrid(state: IconManagerState) {
    val entries = state.results
    if (entries.isEmpty()) {
        Centered { EmptyNote(stringResource(Res.string.icons_empty)) }
        return
    }
    IconGrid(entries.size, key = { entries[it].name }) { index ->
        val entry = entries[index]
        val key = state.repoKey(entry.repoId, entry.name)
        LaunchedEffect(key) { state.ensureRepoArtwork(entry.repoId, entry.name) }
        IconTile(
            label = entry.displayName,
            drawable = state.artworkFor(key),
            selected = (state.selection as? IconSelection.FromRepo)?.entry?.name == entry.name,
        ) {
            state.select(IconSelection.FromRepo(entry))
        }
    }
}

@Composable
private fun ProjectGrid(state: IconManagerState, onOpenAppIconStudio: (String?, String?) -> Unit) {
    val icons = state.filteredProjectIcons()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIconCard(onOpenAppIconStudio)
        if (icons.isEmpty()) {
            Centered { EmptyNote(stringResource(Res.string.icons_project_empty)) }
            return@Column
        }
        IconGrid(
            icons.size,
            key = { "${icons[it].moduleName}/${icons[it].resType}/${icons[it].name}" },
            modifier = Modifier.weight(1f),
        ) { index ->
            val icon = icons[index]
            // The default configuration is what a preview should show; fall back to the first declared one.
            val config = icon.configurations.firstOrNull { it.qualifier.isEmpty() } ?: icon.configurations.first()
            LaunchedEffect(config.path) { state.ensureResourceArtwork(config) }
            IconTile(
                label = icon.name,
                drawable = state.artworkFor("res:${config.path}"),
                raster = state.rasterFor(config.path),
                badge = icon.configurations.size.takeIf { it > 1 },
                selected = (state.selection as? IconSelection.FromProject)?.config?.path == config.path,
            ) {
                state.select(IconSelection.FromProject(icon, config))
            }
        }
    }
}

@Composable
private fun ComposeGrid(state: IconManagerState) {
    val entries = state.filteredComposeIcons()
    if (entries.isEmpty()) {
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
    IconGrid(entries.size, key = { entries[it].name }) { index ->
        val entry = entries[index]
        val key = "compose:${entry.name}:${state.variant.style}:${state.variant.filled}"
        LaunchedEffect(key) { state.ensureComposeArtwork(entry.name) }
        IconTile(
            label = entry.displayName,
            drawable = state.artworkFor(key),
            selected = (state.selection as? IconSelection.FromCompose)?.entry?.name == entry.name,
        ) {
            state.select(IconSelection.FromCompose(entry))
        }
    }
}

@Composable
private fun IconGrid(
    count: Int,
    key: (Int) -> Any,
    modifier: Modifier = Modifier.fillMaxSize(),
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
        items(count, key = key) { index -> tile(index) }
    }
}

/**
 * One icon in the grid: a tonal [Surface] whose selected state is carried by both the container colour and an
 * outline, so it reads without relying on colour alone. [badge] marks how many configurations a project
 * resource has.
 */
@Composable
private fun IconTile(
    label: String,
    drawable: UiDrawable?,
    raster: ByteArray? = null,
    badge: Int? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Column(
            Modifier.padding(vertical = Ca.spacing.s3, horizontal = Ca.spacing.s2),
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
                color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
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
// Detail sheet
// ---------------------------------------------------------------------------

@Composable
internal fun DetailSheet(
    state: IconManagerState,
    selection: IconSelection,
    insertionTarget: UiInsertionTarget?,
    maxHeight: Dp,
    onInsert: ((UiIconRef) -> Unit)?,
    onCopy: (String) -> Unit,
    onOpenAppIconStudio: (String?, String?) -> Unit,
) {
    val reference = state.referenceFor(insertionTarget)
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
            // The body scrolls; the actions do not. A capped sheet whose primary button scrolls out of sight
            // is worse than no cap at all, so the action bar is pinned outside the scrolling area.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = FORM_MAX)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Ca.spacing.s4)
                    .padding(top = Ca.spacing.s3, bottom = Ca.spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
            ) {
                DetailHeader(state, selection)
                // Directly under the header, because it is what the pinned Insert and Copy actions will
                // write: below the form it would be the first thing to scroll out of sight.
                if (reference != null) ReferenceRow(reference) { state.snippetFor(insertionTarget)?.let(onCopy) }
                if (state.warnings.isNotEmpty()) WarningList(state.warnings)
                VariantControls(state, selection)
                ImportControls(state)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.widthIn(max = FORM_MAX).fillMaxWidth()
                    .padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s3),
            ) {
                ActionRow(state, selection, reference, onInsert, onOpenAppIconStudio)
            }
        }
    }
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
private fun DetailHeader(state: IconManagerState, selection: IconSelection) {
    val key = state.keyOf(selection)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = Color.Transparent, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { drawCheckerboard(cell = 8f) }
                IconArt(
                    state.artworkFor(key),
                    (selection as? IconSelection.FromProject)?.let { state.rasterFor(it.config.path) },
                    Modifier.size(40.dp),
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

@Composable
private fun VariantControls(state: IconManagerState, selection: IconSelection) {
    val styles = when (selection) {
        is IconSelection.FromRepo -> selection.entry.styles
        is IconSelection.FromCompose -> selection.entry.styles
        is IconSelection.FromProject -> emptyList()
    }
    val supportsFill = when (selection) {
        is IconSelection.FromRepo -> selection.entry.supportsFill
        is IconSelection.FromCompose -> selection.entry.supportsFill
        is IconSelection.FromProject -> false
    }
    if (styles.size <= 1 && !supportsFill) return

    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
        // The fill toggle shares the label's row: on the segment row it would sit past the right edge and be
        // undiscoverable, and it is orthogonal to the style anyway.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(stringResource(Res.string.icons_style))
            if (supportsFill) {
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = state.variant.filled,
                    onClick = { state.selectVariant(state.variant.copy(filled = !state.variant.filled)) },
                    label = { Text(stringResource(Res.string.icons_filled), maxLines = 1) },
                )
            }
        }
        if (styles.size > 1) {
            // Segments cannot be lazy, so the row scrolls if the labels are wider than the sheet.
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow {
                    styles.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = state.variant.style == style,
                            onClick = { state.selectVariant(state.variant.copy(style = style)) },
                            shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                            label = { Text(styleLabel(style), maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportControls(state: IconManagerState) {
    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3)) {
        OutlinedTextField(
            value = state.resourceName,
            onValueChange = state::updateResourceName,
            label = { Text(stringResource(Res.string.icons_name)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

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
                    items(state.targets, key = { it.resDirPath }) { candidate ->
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
    onOpenAppIconStudio: (String?, String?) -> Unit,
) {
    // Short labels only: a button whose text is wider than the row gets clamped by FlowRow and reads as
    // squashed, so the reference itself is shown above instead of inside these.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
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

        if (reference != null && onInsert != null) {
            FilledTonalButton(onClick = { state.prepareInsertion(onInsert) }) {
                Icon(CaIcons.code, null, Modifier.size(18.dp))
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.icons_insert), maxLines = 1)
            }
        }
        if (selection is IconSelection.FromRepo) {
            OutlinedButton(onClick = { onOpenAppIconStudio(selection.entry.repoId, selection.entry.name) }) {
                Icon(CaIcons.androidLogo, null, Modifier.size(18.dp))
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.icons_app_icon_short), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AppIconCard(onOpenAppIconStudio: (String?, String?) -> Unit) {
    Surface(
        onClick = { onOpenAppIconStudio(null, null) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .widthIn(max = CONTENT_MAX)
            .fillMaxWidth()
            .padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s1),
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
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun ColorSwatch(argb: Long, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(argb.toInt()),
        border = androidx.compose.foundation.BorderStroke(
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

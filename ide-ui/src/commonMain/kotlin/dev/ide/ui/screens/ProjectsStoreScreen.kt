package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSection
import dev.ide.ui.components.inFlight
import dev.ide.ui.components.installActionLabel
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CategoryTile
import dev.ide.ui.components.ComingSoon
import dev.ide.ui.components.FeaturedHeroCard
import dev.ide.ui.components.PillChip
import dev.ide.ui.components.StoreListRow
import dev.ide.ui.components.TrendingTicker
import dev.ide.ui.components.motifFor
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.store_browse_by_kind
import dev.ide.ui.generated.resources.store_install
import dev.ide.ui.generated.resources.store_no_results
import dev.ide.ui.generated.resources.store_open
import dev.ide.ui.generated.resources.store_search_hint
import dev.ide.ui.generated.resources.store_search_placeholder
import dev.ide.ui.generated.resources.store_see_all
import dev.ide.ui.generated.resources.store_title
import dev.ide.ui.generated.resources.store_unavailable
import dev.ide.ui.generated.resources.store_unavailable_content
import dev.ide.ui.generated.resources.store_use
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair
import org.jetbrains.compose.resources.stringResource

/**
 * The home screen's **Explore** tab — the Projects Store.
 *
 * Browse mode is a single [LazyColumn] so the header scrolls with the content: title, a search entry that
 * is a *button* rather than a live field, the trending ticker, the featured carousel, the "browse by kind"
 * grid, then one list per catalog section. Tapping the search entry swaps the whole screen into search
 * mode, which owns the real text field — the same split the design uses, implemented as one screen with
 * two modes rather than two nav destinations, so the tab keeps its scroll position on the way back.
 *
 * Every card's tint and silhouette come from its position in its run, not from the item, so a catalog with
 * no artwork of its own still renders as a varied shelf.
 */
@Composable
fun ProjectsStoreScreen(
    backend: IdeBackend,
    onOpenItem: (UiStoreItem) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHub: (() -> Unit)? = null,
) {
    val available = remember { backend.store.storeAvailable() }
    if (!available) {
        ComingSoon(
            icon = CaIcons.grid,
            title = stringResource(Res.string.store_unavailable),
            description = stringResource(Res.string.store_unavailable_content),
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        )
        return
    }

    val state = rememberProjectsStoreState(backend)
    var searching by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
            if (searching) {
                StoreSearch(
                    state = state,
                    onOpenItem = onOpenItem,
                    onClose = {
                        searching = false
                        state.updateQuery("")
                        state.selectCategory(null)
                    },
                )
            } else {
                StoreBrowse(
                    state = state,
                    onOpenItem = onOpenItem,
                    onOpenSearch = { category ->
                        state.selectCategory(category)
                        searching = true
                    },
                )
            }
        }
    }
}

@Composable
private fun StoreBrowse(
    state: ProjectsStoreState,
    onOpenItem: (UiStoreItem) -> Unit,
    onOpenSearch: (String?) -> Unit,
) {
    val catalog = state.catalog
    val allItems = remember(catalog) { catalog.sections.flatMap { it.items } }
    val ticker = remember(catalog) { tickerLabels(catalog.sections, catalog.categories) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item("header") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)) {
                Text(
                    stringResource(Res.string.store_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SearchEntry(onClick = { onOpenSearch(null) }, modifier = Modifier.padding(top = 14.dp))
            }
        }

        if (ticker.isNotEmpty()) {
            item("ticker") {
                TrendingTicker(
                    labels = ticker,
                    pairAt = { tonalPair(it) },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }

        if (catalog.featured.isNotEmpty()) {
            item("featured") {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    flingBehavior = rememberSnapFlingBehavior(listState),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(catalog.featured, key = { _, it -> it.id }) { i, item ->
                        FeaturedHeroCard(
                            title = item.title,
                            badge = kindLabel(item.kind),
                            subtitle = listOfNotNull(item.author, item.language).joinToString(" · ")
                                .ifBlank { item.category },
                            pair = tonalPair(i),
                            motif = remember(item.id) { motifFor(item.id) },
                            rating = item.rating,
                            installs = item.installs,
                            preview = if (hasSamplePreview(item.previewKey)) {
                                { m -> SamplePreview(item.previewKey!!, m) }
                            } else null,
                            onClick = { onOpenItem(item) },
                        )
                    }
                }
            }
        }

        if (catalog.categories.isNotEmpty()) {
            item("categoriesHeader") {
                SectionHeader(stringResource(Res.string.store_browse_by_kind))
            }
            // A LazyVerticalGrid cannot nest inside a LazyColumn, so the two-column grid is emitted as
            // chunked rows instead.
            val rows = catalog.categories.chunked(2)
            itemsIndexed(rows, key = { i, _ -> "catRow$i" }) { rowIndex, row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEachIndexed { colIndex, cat ->
                        val i = rowIndex * 2 + colIndex
                        CategoryTile(
                            name = cat,
                            count = allItems.count { it.category == cat },
                            glyph = symbolForCategory(cat),
                            pair = tonalPair(i),
                            shape = cardShape(i),
                            onClick = { onOpenSearch(cat) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep a lone tile on an odd final row at one column's width rather than full bleed.
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        catalog.sections.filter { it.items.isNotEmpty() }.forEachIndexed { sectionIndex, section ->
            item("head_${section.id}") {
                // "See all" drops into search filtered to that shelf's category, which is the closest the
                // backend can answer: sections are by kind, and search filters by category.
                SectionHeader(
                    title = section.title,
                    subtitle = section.subtitle,
                    onSeeAll = { onOpenSearch(section.items.firstOrNull()?.category) },
                )
            }
            storeRows(section, onOpenItem)
            // One native ad between shelves — browse time between topics, never over the hero carousel.
            // Padded to the same 20 dp gutter as the rows so it sits in the list rather than beside it.
            if (sectionIndex == 0) {
                item("ad_${section.id}") {
                    AdSlot(AdPlacement.STORE, Modifier.padding(horizontal = 20.dp).padding(top = 16.dp))
                }
            }
        }
    }
}

/** One catalog section rendered as list rows. */
private fun LazyListScope.storeRows(section: UiStoreSection, onOpenItem: (UiStoreItem) -> Unit) {
    itemsIndexed(section.items, key = { _, it -> "${section.id}_${it.id}" }) { i, item ->
        StoreItemRow(item, i, onOpenItem)
    }
}

@Composable
internal fun StoreItemRow(
    item: UiStoreItem,
    index: Int,
    onOpenItem: (UiStoreItem) -> Unit,
    onAction: ((UiStoreItem) -> Unit)? = null,
    /** This item's in-flight install, if any. Turns the action into live progress. */
    progress: dev.ide.ui.backend.UiInstallProgress? = null,
) {
    val message = progress?.message
    StoreListRow(
        title = item.title,
        // A failure replaces the subtitle: the row is where the tap happened, so it is where the reason belongs.
        subtitle = message
            ?: listOfNotNull(item.author, item.language).joinToString(" · ").ifBlank { item.summary },
        iconId = item.iconId,
        pair = tonalPair(index),
        tileShape = tileShape(index),
        // A template routes straight into the Create-Project flow, so its action reads "Use", not "Install".
        actionLabel = installActionLabel(item, progress),
        actionFilled = item.available,
        rating = item.rating,
        installs = item.installs,
        onOpen = { onOpenItem(item) },
        onAction = { if (!progress.inFlight) (onAction ?: onOpenItem)(item) },
    )
}

/**
 * The non-editable search entry on the browse screen.
 *
 * A button that looks like a field, not a field: the real text input lives on the search screen, and a live
 * field here would put focus (and the soft keyboard) on the browse tab every time it recomposed.
 */
@Composable
internal fun SearchEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = c.surfaceContainer,
        contentColor = c.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(CaSymbols.search, contentDescription = null, size = 22.dp)
            Text(
                stringResource(Res.string.store_search_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = c.onSurfaceVariant,
            )
        }
    }
}

/**
 * Search mode: the pill-shaped app bar with the live field, the category pills, and the results.
 *
 * Results re-query as the user types (the state holder debounces by 180 ms). The design's full filter
 * sheet — sort order, multi-select language, minimum rating — is not here: [dev.ide.ui.backend.StoreService]
 * can filter by query and category only, and a sheet whose sort and rating controls silently did nothing
 * would be worse than not offering them. The category pills are the part that actually works today.
 */
@Composable
private fun StoreSearch(
    state: ProjectsStoreState,
    onOpenItem: (UiStoreItem) -> Unit,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = c.surfaceContainer,
                contentColor = c.onSurfaceVariant,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Row(
                    Modifier.padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(CaSymbols.arrowBack, contentDescription = stringResource(Res.string.store_title), size = 24.dp)
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = state::updateQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { field ->
                            if (state.query.isEmpty()) {
                                Text(
                                    stringResource(Res.string.store_search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = c.onSurfaceVariant,
                                )
                            }
                            field()
                        },
                    )
                }
            }
        }

        if (state.catalog.categories.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.catalog.categories, key = { it }) { cat ->
                    PillChip(
                        label = cat,
                        selected = state.category == cat,
                        leadingGlyph = CaSymbols.check,
                        onClick = { state.selectCategory(if (state.category == cat) null else cat) },
                    )
                }
            }
        }

        val results = state.results
        if (state.filtering && results.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Symbol(CaSymbols.searchOff, contentDescription = null, size = 44.dp, tint = c.outlineVariant)
                Text(
                    stringResource(Res.string.store_no_results, state.query),
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(results, key = { _, it -> it.id }) { i, item -> StoreItemRow(item, i, onOpenItem) }
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, subtitle: String? = null, onSeeAll: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onSeeAll != null) {
                Text(
                    stringResource(Res.string.store_see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onSeeAll).padding(vertical = 4.dp),
                )
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun kindLabel(kind: UiStoreItemKind): String = when (kind) {
    UiStoreItemKind.Template -> "Template"
    UiStoreItemKind.Sample -> "Sample"
    UiStoreItemKind.Community -> "Community"
}

/**
 * The ticker's labels, taken from the catalog's own tags rather than invented.
 *
 * The most-used tags are the honest answer to "what is trending here"; a catalog with no tags falls back
 * to its category names, and one with neither shows no ticker at all rather than a placeholder.
 */
private fun tickerLabels(sections: List<UiStoreSection>, categories: List<String>): List<String> {
    val tags = sections.flatMap { it.items }.flatMap { it.tags }
    val ranked = tags.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
    return (if (ranked.size >= 4) ranked else categories).take(8)
}

/** A store category name mapped onto a Material Symbol. Unknown categories fall back to a package glyph. */
private fun symbolForCategory(category: String): Char = when (category.lowercase()) {
    "kotlin" -> CaSymbols.bolt
    "java" -> CaSymbols.coffee
    "android" -> CaSymbols.phoneAndroid
    "compose", "multiplatform" -> CaSymbols.hub
    "games" -> CaSymbols.palette
    "web", "server", "backend" -> CaSymbols.dns
    "build", "plugins", "build plugins" -> CaSymbols.extension
    "snippets", "snippet packs" -> CaSymbols.codeBlocks
    "community" -> CaSymbols.apartment
    "console", "cli" -> CaSymbols.terminal
    else -> CaSymbols.extension
}

/**
 * A store item's glyph: whatever the backend named, else something derived from its category.
 *
 * Catalog rows carry `iconId` in the line-icon vocabulary (or, once the remote catalog lands, a Material
 * Symbols name directly), so a name that resolves is used as-is and everything else falls back.
 */
private fun symbolForStoreItem(item: UiStoreItem): Char =
    CaSymbols.forIconId(item.iconId, fallback = symbolForCategory(item.category))

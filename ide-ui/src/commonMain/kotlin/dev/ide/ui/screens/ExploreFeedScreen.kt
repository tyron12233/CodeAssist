package dev.ide.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.UiChartEntry
import dev.ide.ui.backend.UiFeedSection
import dev.ide.ui.backend.UiStoreCollection
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreMode
import dev.ide.ui.backend.UiStorePublisher
import dev.ide.ui.generated.resources.store_bundled_subtitle
import dev.ide.ui.generated.resources.store_bundled_title
import dev.ide.ui.generated.resources.store_catalogue_subtitle
import dev.ide.ui.generated.resources.store_collections_title
import dev.ide.ui.generated.resources.store_ghost_next_title
import dev.ide.ui.generated.resources.store_ghost_section_subtitle
import dev.ide.ui.generated.resources.store_ghost_section_title
import dev.ide.ui.generated.resources.store_ghostspec_charts_note
import dev.ide.ui.generated.resources.store_ghostspec_collections_note
import dev.ide.ui.generated.resources.store_ghostspec_rec_note
import dev.ide.ui.generated.resources.store_ghostspec_rec_title
import dev.ide.ui.generated.resources.store_how_publishing_title
import dev.ide.ui.generated.resources.store_meta_offline
import dev.ide.ui.generated.resources.store_top_charts
import dev.ide.ui.generated.resources.store_signin_title
import dev.ide.ui.components.SquareToneButton
import dev.ide.ui.components.inFlight
import dev.ide.ui.components.installActionLabel
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.BundledTemplateRow
import dev.ide.ui.components.ChartCard
import dev.ide.ui.components.ChartTabRow
import dev.ide.ui.components.CollectionCard
import dev.ide.ui.components.EmptyStoreHero
import dev.ide.ui.components.NotifySwitchRow
import dev.ide.ui.components.PublishingSteps
import dev.ide.ui.components.SubmissionStatusCard
import dev.ide.ui.components.defaultPublishSteps
import dev.ide.ui.components.emptyGhostNote
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.FeaturedHeroCard
import dev.ide.ui.components.GhostShelfCard
import dev.ide.ui.components.LiveDot
import dev.ide.ui.components.PosterCard
import dev.ide.ui.components.PublishPitchBand
import dev.ide.ui.components.SparseProjectCard
import dev.ide.ui.components.SpotlightCard
import dev.ide.ui.components.StoreCountBadge
import dev.ide.ui.components.TrendingTicker
import dev.ide.ui.components.chartMeta
import dev.ide.ui.components.motifFor
import org.jetbrains.compose.resources.stringResource
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.tonalPair
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.store_title
import org.jetbrains.compose.resources.stringResource

/**
 * Explore, rendered from the server-driven feed.
 *
 * The screen's whole job is to dispatch the sections it was given, in the order it was given them. It
 * does not decide which shelves exist, what they are called, or whether the store has enough content for
 * one — all of that is the feed's, so merchandising can move the page without an app release.
 *
 * The three modes differ only in which sections arrive. There is no `if (sparse)` branching over layout
 * here beyond the header: `catalogue`, `publish_pitch` and `ghost_shelves` simply never appear in a
 * populated feed, and `charts` never appears in a sparse one.
 */
@Composable
fun ExploreFeed(
    feed: UiStoreFeed,
    onOpenItem: (UiStoreItem) -> Unit,
    onInstallItem: (UiStoreItem) -> Unit,
    onOpenSearch: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCollection: (UiStoreCollection) -> Unit = {},
    onOpenPublisher: (UiStorePublisher) -> Unit = {},
    followedPublishers: Set<String> = emptySet(),
    onToggleFollow: (UiStorePublisher) -> Unit = {},
    onPublish: () -> Unit = {},
    onHowItWorks: () -> Unit = {},
    bundled: List<UiStoreItem> = emptyList(),
    onUseBundled: (UiStoreItem) -> Unit = {},
    /** Relative "Published 3 days ago" for an item, from the host which owns the clock. */
    postedLabel: (UiStoreItem) -> String? = { null },
    isRecent: (UiStoreItem) -> Boolean = { false },
    /**
     * In-flight installs by item id. Drives the action labels, so a download reports itself wherever the
     * item appears rather than only on the screen the tap happened on.
     */
    installing: Map<String, dev.ide.ui.backend.UiInstallProgress> = emptyMap(),
    /** The signed-in account's in-flight submission, shown above the hero. Null hides the card. */
    submission: dev.ide.ui.backend.UiStoreSubmission? = null,
    onWithdrawSubmission: () -> Unit = {},
    onViewSubmissionNotes: () -> Unit = {},
    onViewListing: () -> Unit = {},
    notifyOnLaunch: Boolean = false,
    onNotifyChange: (Boolean) -> Unit = {},
    /** Why the last notify toggle did not take. Shown in place of the switch's supporting line. */
    notifyMessage: String? = null,
    /** Opens the account sheet. Null hides the entry, for a host with no sign-in. */
    onAccount: (() -> Unit)? = null,
    signedIn: Boolean = false,
) {
    // Empty is a FIXED layout rather than a server-driven one: the feed carries no renderable sections
    // by definition, so the client composes the zero-data page and reads only storeState + bundled from
    // the feed. Everything else on this screen is the populated/sparse path.
    if (feed.mode == UiStoreMode.EMPTY) {
        ExploreEmpty(
            feed = feed,
            bundled = bundled,
            onOpenSearch = onOpenSearch,
            onUseBundled = onUseBundled,
            onPublish = onPublish,
            onGuide = onHowItWorks,
            submission = submission,
            onWithdrawSubmission = onWithdrawSubmission,
            onViewSubmissionNotes = onViewSubmissionNotes,
            onViewListing = onViewListing,
            notifyOnLaunch = notifyOnLaunch,
            onNotifyChange = onNotifyChange,
            notifyMessage = notifyMessage,
            modifier = modifier,
        )
        return
    }
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.widthIn(max = 820.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item("header") {
                ExploreHeader(
                    // The count badge shows in every mode except populated, where the page speaks for
                    // itself and a number would just be noise.
                    count = feed.state.publishedProjectCount.takeIf { feed.mode != UiStoreMode.POPULATED },
                    onOpenSearch = { onOpenSearch(null) },
                    onAccount = onAccount,
                    signedIn = signedIn,
                )
            }

            feed.sections.forEach { section ->
                when (section) {
                    is UiFeedSection.Ticker -> item(section.id) {
                        TrendingTicker(
                            labels = section.terms,
                            pairAt = { tonalPair(it) },
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }

                    is UiFeedSection.Featured -> item(section.id) {
                        FeaturedRow(section.items, onOpenItem)
                    }

                    is UiFeedSection.Charts -> item(section.id) {
                        ChartsSection(section, onOpenItem, onInstallItem, installing)
                    }

                    is UiFeedSection.Collections -> item(section.id) {
                        CollectionsRow(section, onOpenCollection)
                    }

                    is UiFeedSection.Categories -> categoriesGrid(section, onOpenSearch)

                    is UiFeedSection.Personalized -> item(section.id) {
                        PersonalizedRow(section, onOpenItem)
                    }

                    is UiFeedSection.Spotlight -> item(section.id) {
                        Column {
                            Spacer(Modifier.height(26.dp))
                            SpotlightCard(
                                publisher = section.publisher,
                                following = section.publisher.id in followedPublishers,
                                onOpen = { onOpenPublisher(section.publisher) },
                                onToggleFollow = { onToggleFollow(section.publisher) },
                            )
                        }
                    }

                    is UiFeedSection.ItemList -> {
                        item("head_${section.id}") { SectionHeader(section.title) }
                        itemsIndexed(section.items, key = { _, it -> "${section.id}_${it.id}" }) { i, item ->
                            StoreItemRow(item, i, onOpenItem, onInstallItem, installing[item.id])
                        }
                        item("ad_${section.id}") {
                            AdSlot(AdPlacement.STORE, Modifier.padding(horizontal = 20.dp).padding(top = 16.dp))
                        }
                    }

                    is UiFeedSection.Catalogue -> {
                        item("head_${section.id}") {
                            SectionHeader(section.title, subtitle = stringResource(Res.string.store_catalogue_subtitle))
                        }
                        itemsIndexed(section.items, key = { _, it -> "cat_${it.id}" }) { i, item ->
                            Spacer(Modifier.height(12.dp))
                            SparseProjectCard(
                                item = item,
                                index = i,
                                postedLabel = postedLabel(item),
                                isRecent = isRecent(item),
                                actionLabel = installActionLabel(item, installing[item.id]),
                                onOpen = { onOpenItem(item) },
                                onAction = { if (!installing[item.id].inFlight) onInstallItem(item) },
                            )
                        }
                    }

                    is UiFeedSection.PublishPitch -> item(section.id) {
                        Column {
                            Spacer(Modifier.height(22.dp))
                            PublishPitchBand(
                                projectCount = section.projectCount,
                                onPublish = onPublish,
                                onHowItWorks = onHowItWorks,
                            )
                        }
                    }

                    is UiFeedSection.Bundled -> {
                        // Rows come from the device's own templates, so an empty list means this build
                        // ships no scaffolds — not that the section was wrong to appear.
                        if (bundled.isNotEmpty()) {
                            item("head_${section.id}") {
                                SectionHeader(
                                    stringResource(Res.string.store_bundled_title),
                                    subtitle = stringResource(Res.string.store_bundled_subtitle),
                                )
                            }
                            itemsIndexed(bundled, key = { _, it -> "bundled_${it.id}" }) { i, item ->
                                Spacer(Modifier.height(10.dp))
                                BundledTemplateRow(
                                    title = item.title,
                                    meta = listOfNotNull(item.language, stringResource(Res.string.store_meta_offline)).joinToString(" · "),
                                    iconId = item.iconId,
                                    index = i,
                                    onUse = { onUseBundled(item) },
                                )
                            }
                        }
                    }

                    is UiFeedSection.GhostShelves -> {
                        item("head_${section.id}") {
                            SectionHeader(
                                stringResource(Res.string.store_ghost_section_title),
                                subtitle = stringResource(Res.string.store_ghost_section_subtitle),
                            )
                        }
                        itemsIndexed(section.shelves, key = { _, it -> "ghost_${it.key}" }) { _, shelf ->
                            Spacer(Modifier.height(12.dp))
                            val spec = ghostSpec(shelf.key, shelf.need)
                            GhostShelfCard(
                                shelf = shelf,
                                title = spec.title,
                                note = spec.note,
                                glyph = spec.glyph,
                                slotHeight = spec.slotHeight,
                                slotCount = spec.slotCount,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreHeader(
    count: Int?,
    onOpenSearch: () -> Unit,
    /**
     * The account / publish entry, or null where the host cannot sign in.
     *
     * It belongs in the header rather than only in the publish pitch: the pitch appears in the empty and
     * sparse feeds and disappears once the store fills up, which would leave a populated store with no way
     * to publish to it at all.
     */
    onAccount: (() -> Unit)? = null,
    signedIn: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Res.string.store_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (count != null) StoreCountBadge(count)
            if (onAccount != null) {
                SquareToneButton(
                    // A filled account glyph once there is an identity behind it, an outline before.
                    glyph = if (signedIn) CaSymbols.accountCircle else CaSymbols.person,
                    contentDescription = stringResource(Res.string.store_signin_title),
                    onClick = onAccount,
                    size = 46.dp,
                )
            }
        }
        SearchEntry(onClick = onOpenSearch, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun FeaturedRow(items: List<UiStoreItem>, onOpenItem: (UiStoreItem) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            FeaturedHeroCard(
                title = item.title,
                badge = item.kind.name,
                subtitle = listOfNotNull(item.author, item.language).joinToString(" · ").ifBlank { item.category },
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

/**
 * Top charts.
 *
 * The selected tab is local state: switching tabs is not a navigation event and must not re-fetch — all
 * three tabs arrived in the same payload.
 */
@Composable
private fun ChartsSection(
    section: UiFeedSection.Charts,
    onOpenItem: (UiStoreItem) -> Unit,
    onInstallItem: (UiStoreItem) -> Unit,
    installing: Map<String, dev.ide.ui.backend.UiInstallProgress> = emptyMap(),
) {
    var selected by remember(section.id) { mutableStateOf(section.tabs.firstOrNull()?.key.orEmpty()) }
    val tab = section.tabs.firstOrNull { it.key == selected } ?: section.tabs.firstOrNull() ?: return
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(Res.string.store_top_charts),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            LiveDot()
        }
        ChartTabRow(section.tabs, tab.key, onSelect = { selected = it })
        Spacer(Modifier.height(12.dp))
        ChartCard(
            entries = tab.entries,
            metaFor = { chartMeta(it, tab.key) },
            actionFor = { installActionLabel(it.item, installing[it.item.id]) },
            onOpen = { onOpenItem(it.item) },
            onAction = { if (!installing[it.item.id].inFlight) onInstallItem(it.item) },
        )
    }
}

@Composable
private fun CollectionsRow(section: UiFeedSection.Collections, onOpen: (UiStoreCollection) -> Unit) {
    val state = rememberLazyListState()
    Column {
        SectionHeader(section.title, subtitle = section.subtitle)
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(section.items, key = { _, it -> it.id }) { i, c ->
                CollectionCard(collection = c, index = i, onOpen = { onOpen(c) })
            }
        }
    }
}

@Composable
private fun PersonalizedRow(section: UiFeedSection.Personalized, onOpenItem: (UiStoreItem) -> Unit) {
    val state = rememberLazyListState()
    Column {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp)) {
            Text(
                section.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            section.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(section.items, key = { _, it -> it.id }) { i, item ->
                PosterCard(item = item, index = i, onOpen = { onOpenItem(item) })
            }
        }
    }
}

private fun LazyListScope.categoriesGrid(
    section: UiFeedSection.Categories,
    onOpenSearch: (String?) -> Unit,
) {
    item("head_${section.id}") { SectionHeader(section.title) }
    val rows = section.categories.chunked(2)
    itemsIndexed(rows, key = { i, _ -> "catrow_${section.id}_$i" }) { rowIndex, row ->
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEachIndexed { colIndex, cat ->
                val i = rowIndex * 2 + colIndex
                dev.ide.ui.components.CategoryTile(
                    name = cat,
                    count = section.counts[cat] ?: 0,
                    glyph = CaSymbols.forIconId(cat.lowercase()),
                    pair = tonalPair(i),
                    shape = dev.ide.ui.theme.cardShape(i),
                    onClick = { onOpenSearch(cat) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Which glyph, note and slot proportions a ghost shelf uses. The note names the threshold, not a date. */
private data class GhostSpec(
    val title: String,
    val note: String,
    val glyph: Char,
    val slotHeight: androidx.compose.ui.unit.Dp,
    val slotCount: Int,
)

@Composable
private fun ghostSpec(key: String, need: Int): GhostSpec = when (key) {
    "charts" -> GhostSpec(
        title = stringResource(Res.string.store_top_charts),
        note = stringResource(Res.string.store_ghostspec_charts_note, need),
        glyph = CaSymbols.trendingUp,
        slotHeight = 46.dp,
        slotCount = 3,
    )
    "collections" -> GhostSpec(
        title = stringResource(Res.string.store_collections_title),
        note = stringResource(Res.string.store_ghostspec_collections_note),
        glyph = CaSymbols.rocketLaunch,
        slotHeight = 72.dp,
        slotCount = 2,
    )
    else -> GhostSpec(
        title = stringResource(Res.string.store_ghostspec_rec_title),
        note = stringResource(Res.string.store_ghostspec_rec_note),
        glyph = CaSymbols.hub,
        slotHeight = 60.dp,
        slotCount = 3,
    )
}

/**
 * The zero-data Explore page.
 *
 * Composition order matters and is the opposite of the sparse state's: with nothing published there is
 * no content to lead with, so the publish argument comes first. (In sparse, content leads and the pitch
 * follows — see the sparse handoff's Rule 2.)
 *
 * `acceptingSubmissions = false` — an instance with publishing locked down — drops the hero and the
 * publishing steps and leaves the header, search, bundled templates and ghost shelves. One boolean, and
 * it is the difference between a useful page and an insulting one on a locked instance.
 */
@Composable
private fun ExploreEmpty(
    feed: UiStoreFeed,
    bundled: List<UiStoreItem>,
    onOpenSearch: (String?) -> Unit,
    onUseBundled: (UiStoreItem) -> Unit,
    onPublish: () -> Unit,
    onGuide: () -> Unit,
    submission: dev.ide.ui.backend.UiStoreSubmission?,
    onWithdrawSubmission: () -> Unit,
    onViewSubmissionNotes: () -> Unit,
    onViewListing: () -> Unit,
    notifyOnLaunch: Boolean,
    notifyMessage: String? = null,
    onNotifyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accepting = feed.state.acceptingSubmissions
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.widthIn(max = 820.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item("header") {
                ExploreHeader(count = feed.state.publishedProjectCount, onOpenSearch = { onOpenSearch(null) })
            }

            // Above the hero: once the user has submitted, their own submission outranks the pitch.
            if (submission != null) {
                item("submission") {
                    SubmissionStatusCard(
                        submission = submission,
                        onWithdraw = onWithdrawSubmission,
                        onViewNotes = onViewSubmissionNotes,
                        onViewListing = onViewListing,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (accepting) {
                item("hero") {
                    EmptyStoreHero(
                        hasSubmission = submission != null,
                        onPublish = onPublish,
                        onGuide = onGuide,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item("steps_head") { SectionHeader(stringResource(Res.string.store_how_publishing_title)) }
                item("steps") { PublishingSteps(defaultPublishSteps()) }
            }

            if (bundled.isNotEmpty()) {
                item("bundled_head") {
                    SectionHeader(
                        stringResource(Res.string.store_bundled_title),
                        subtitle = stringResource(Res.string.store_bundled_subtitle),
                    )
                }
                itemsIndexed(bundled, key = { _, it -> "bundled_${it.id}" }) { i, item ->
                    Spacer(Modifier.height(10.dp))
                    BundledTemplateRow(
                        title = item.title,
                        meta = listOfNotNull(item.language, stringResource(Res.string.store_meta_offline)).joinToString(" · "),
                        iconId = item.iconId,
                        index = i,
                        onUse = { onUseBundled(item) },
                    )
                }
            }

            item("ghosts_head") { SectionHeader(stringResource(Res.string.store_ghost_next_title)) }
            // No progress counters here: at zero there is nothing to be partway toward, so each note
            // states the CONDITION that fills the shelf instead.
            itemsIndexed(EMPTY_GHOSTS, key = { _, k -> "ghost_$k" }) { _, key ->
                Spacer(Modifier.height(12.dp))
                val spec = ghostSpec(key, need = 0)
                GhostShelfCard(
                    shelf = null,
                    title = spec.title,
                    note = emptyGhostNote(key),
                    glyph = spec.glyph,
                    slotHeight = spec.slotHeight,
                    slotCount = spec.slotCount,
                )
            }

            item("notify") {
                Spacer(Modifier.height(22.dp))
                NotifySwitchRow(
                    checked = notifyOnLaunch,
                    onCheckedChange = onNotifyChange,
                    message = notifyMessage,
                )
            }
        }
    }
}

/** The three shelves the empty page previews, in the order the populated page will show them. */
private val EMPTY_GHOSTS = listOf("charts", "collections", "recommendations")

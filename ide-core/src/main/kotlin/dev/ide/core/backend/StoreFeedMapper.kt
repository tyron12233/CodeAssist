package dev.ide.core.backend

import dev.ide.store.ChartEntry
import dev.ide.store.RemoteItemKind
import dev.ide.store.RemoteStoreItem
import dev.ide.store.ShelfLayout
import dev.ide.store.StoreFeed
import dev.ide.store.StoreMode
import dev.ide.store.StoreSection
import dev.ide.ui.backend.UiChartEntry
import dev.ide.ui.backend.UiChartTab
import dev.ide.ui.backend.UiFeedSection
import dev.ide.ui.backend.UiGhostShelf
import dev.ide.ui.backend.UiShelfLayout
import dev.ide.ui.backend.UiStoreCollection
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreMode
import dev.ide.ui.backend.UiStorePublisher
import dev.ide.ui.backend.UiStoreState
import dev.ide.ui.backend.UiStoreThresholds

/**
 * Maps the engine's [StoreFeed] onto the UI's [UiStoreFeed].
 *
 * Mechanical by design: the two models are deliberately separate so `ide-ui` never sees the store
 * transport, and this is the one place that knows both. The only judgement here is the **overlay**, which
 * is what makes "bundled = offline fallback, remote overlays" true on the client side.
 */
internal object StoreFeedMapper {

    /**
     * Convert a remote feed, overlaying it onto the bundled catalog.
     *
     * [bundledBySlug] carries the on-device templates keyed by the id a remote row would use. A remote item
     * whose id matches one keeps the **remote presentation** (a moderator can fix a title or a summary
     * without an app release) but inherits the bundled item's `templateId`, so it still creates a project
     * locally rather than trying to download a payload the device already has.
     */
    fun toUi(feed: StoreFeed, bundledBySlug: Map<String, UiStoreItem>): UiStoreFeed = UiStoreFeed(
        mode = when (feed.mode) {
            StoreMode.EMPTY -> UiStoreMode.EMPTY
            StoreMode.SPARSE -> UiStoreMode.SPARSE
            StoreMode.POPULATED -> UiStoreMode.POPULATED
        },
        state = UiStoreState(
            publishedProjectCount = feed.state.publishedProjectCount,
            acceptingSubmissions = feed.state.acceptingSubmissions,
            thresholds = UiStoreThresholds(
                charts = feed.state.thresholds.charts,
                collections = feed.state.thresholds.collections,
                recommendations = feed.state.thresholds.recommendations,
            ),
        ),
        sections = feed.sections.mapNotNull { section -> toUiSection(section, bundledBySlug) },
    )

    private fun toUiSection(
        section: StoreSection,
        bundled: Map<String, UiStoreItem>,
    ): UiFeedSection? = when (section) {
        is StoreSection.Ticker -> UiFeedSection.Ticker(section.id, section.terms)

        is StoreSection.Featured ->
            UiFeedSection.Featured(section.id, section.items.map { it.toUi(bundled) })

        is StoreSection.Charts -> UiFeedSection.Charts(
            id = section.id,
            tabs = section.tabs.map { tab ->
                UiChartTab(tab.key, tab.label, tab.entries.map { it.toUi(bundled) }, tab.metric)
            },
            computedAt = section.computedAt,
            title = section.title,
        )

        is StoreSection.Collections -> UiFeedSection.Collections(
            id = section.id,
            title = section.title,
            subtitle = section.subtitle,
            items = section.items.map {
                UiStoreCollection(
                    id = it.id,
                    eyebrow = it.eyebrow,
                    title = it.title,
                    iconId = it.iconKey,
                    projectCount = it.projectCount,
                    previewIconIds = it.previewIconKeys,
                )
            },
        )

        is StoreSection.Categories -> UiFeedSection.Categories(
            id = section.id,
            title = section.title,
            categories = section.items.map { it.title },
            counts = section.items.associate { it.title to it.count },
        )

        is StoreSection.Personalized -> UiFeedSection.Personalized(
            id = section.id,
            title = section.title,
            subtitle = section.subtitle,
            items = section.items.map { it.toUi(bundled) },
        )

        is StoreSection.Spotlight -> UiFeedSection.Spotlight(
            id = section.id,
            publisher = UiStorePublisher(
                id = section.publisher.id,
                handle = section.publisher.handle,
                name = section.publisher.name,
                bio = section.publisher.bio,
                verified = section.publisher.verified,
                projectCount = section.publisher.projectCount,
                installCount = section.publisher.installCount,
                rating = section.publisher.rating,
                followerCount = section.publisher.followerCount,
            ),
        )

        is StoreSection.Shelf -> UiFeedSection.Shelf(
            id = section.id,
            title = section.title,
            subtitle = section.subtitle,
            eyebrow = section.eyebrow,
            iconId = section.iconKey,
            layout = when (section.layout) {
                ShelfLayout.ROWS -> UiShelfLayout.ROWS
                ShelfLayout.CAROUSEL -> UiShelfLayout.CAROUSEL
                ShelfLayout.POSTER -> UiShelfLayout.POSTER
                ShelfLayout.GRID -> UiShelfLayout.GRID
                ShelfLayout.RANK -> UiShelfLayout.RANK
            },
            items = section.items.map { it.toUi(bundled) },
        )

        is StoreSection.Catalogue ->
            UiFeedSection.Catalogue(section.id, section.title, section.items.map { it.toUi(bundled) })

        is StoreSection.PublishPitch -> UiFeedSection.PublishPitch(section.id, section.projectCount)

        is StoreSection.Bundled -> UiFeedSection.Bundled(section.id)

        is StoreSection.GhostShelves -> UiFeedSection.GhostShelves(
            id = section.id,
            shelves = section.shelves.map { UiGhostShelf(it.key, it.have, it.need, it.title, it.note) },
        )
    }

    private fun ChartEntry.toUi(bundled: Map<String, UiStoreItem>) =
        UiChartEntry(rank = rank, previousRank = previousRank, item = project.toUi(bundled))

    /**
     * One remote item, overlaid onto its bundled counterpart if there is one.
     *
     * Two fields are taken from the bundled side even when the remote row has its own opinion:
     *
     *  - `templateId`, so a bundled item still creates locally. Without this, an overlaid template would
     *    try to download a zip that is already in the APK.
     *  - `previewKey`, because the screenshots are bundled drawables the server knows nothing about.
     */
    /**
     * One remote item as the UI sees it, with no bundled overlay.
     *
     * For surfaces that show a specific publisher's catalogue rather than the merchandised feed: there is
     * nothing to overlay there, because a publisher's own list is exactly what they published.
     */
    internal fun itemToUi(item: RemoteStoreItem): UiStoreItem = item.toUi(emptyMap())

    private fun RemoteStoreItem.toUi(bundled: Map<String, UiStoreItem>): UiStoreItem {
        val local = bundled[id]
        return UiStoreItem(
            id = id,
            kind = when (kind) {
                RemoteItemKind.TEMPLATE -> UiStoreItemKind.Template
                RemoteItemKind.SAMPLE -> UiStoreItemKind.Sample
                RemoteItemKind.COMMUNITY -> UiStoreItemKind.Community
            },
            title = title,
            summary = summary,
            description = description,
            blurb = blurb,
            category = category,
            iconId = icon ?: local?.iconId ?: "file",
            tags = tags,
            author = author,
            authorHandle = authorHandle,
            featured = featured,
            accentColor = accent?.let(::parseHexColor) ?: local?.accentColor,
            installs = installs,
            likes = likes,
            rating = rating ?: -1f,
            ratingCount = ratingCount,
            version = version,
            downloadBytes = sizeBytes,
            verified = verified,
            changelog = changelog,
            publishedAt = publishedAt,
            // A remote row that overlays a bundled template keeps creating locally.
            templateId = templateId ?: local?.templateId,
            // Available when it can be created locally OR there is a payload to fetch.
            available = (templateId ?: local?.templateId) != null || storagePath != null,
            highlights = highlights,
            language = language,
            previewKey = local?.previewKey,
        )
    }

    /** `#RRGGBB` → an `0xAARRGGBB` long, or null if the row's accent is malformed. */
    private fun parseHexColor(hex: String): Long? {
        val cleaned = hex.removePrefix("#")
        if (cleaned.length != 6) return null
        val rgb = cleaned.toLongOrNull(16) ?: return null
        return 0xFF000000L or rgb
    }
}

/**
 * The dispatcher store I/O runs on.
 *
 * Reading the feed cache and talking to the network are both blocking, and neither belongs on the
 * engine's own dispatcher — a slow store request must not sit in front of an editor operation.
 */
internal val storeIo: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

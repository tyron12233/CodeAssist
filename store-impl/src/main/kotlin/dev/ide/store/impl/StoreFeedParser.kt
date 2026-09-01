package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.ChartEntry
import dev.ide.store.ChartTab
import dev.ide.store.GhostShelf
import dev.ide.store.RemoteCategory
import dev.ide.store.StoreCollection
import dev.ide.store.StoreFeed
import dev.ide.store.StoreMode
import dev.ide.store.StorePublisher
import dev.ide.store.StoreSection
import dev.ide.store.StoreState
import dev.ide.store.StoreThresholds

/**
 * Turns the `store_explore()` document into a [StoreFeed].
 *
 * This is where the feed's two forward-compatibility rules are enforced, once, rather than at every
 * call site:
 *
 *  - A section whose `type` this build does not recognise is **dropped**. The server can ship a shelf
 *    before the app understands it, and an old client renders everything else.
 *  - A section that would render empty is **dropped**. The server already does this, but a client that
 *    also does it cannot be made to draw an empty shelf by a server bug.
 *
 * Nothing here throws. A malformed feed degrades to fewer sections, which the caller handles the same
 * way it handles being offline — by falling back to the bundled catalog.
 */
object StoreFeedParser {

    fun parse(raw: String): StoreFeed? = JsonReader.parseOrNull(raw)?.let(::parseFeed)

    fun parseFeed(root: Any?): StoreFeed {
        val stateJson = JsonReader.obj(root)?.get("storeState")
        val thresholdsJson = JsonReader.obj(stateJson)?.get("thresholds")
        return StoreFeed(
            version = JsonReader.int(root, "version"),
            generatedAt = JsonReader.str(root, "generatedAt"),
            mode = StoreMode.of(JsonReader.str(root, "mode")),
            state = StoreState(
                publishedProjectCount = JsonReader.int(stateJson, "publishedProjectCount"),
                acceptingSubmissions = JsonReader.bool(stateJson, "acceptingSubmissions", default = true),
                bundledTemplatesVersion = JsonReader.str(stateJson, "bundledTemplatesVersion"),
                thresholds = StoreThresholds(
                    charts = JsonReader.int(thresholdsJson, "charts", 10),
                    collections = JsonReader.int(thresholdsJson, "collections", 12),
                    recommendations = JsonReader.int(thresholdsJson, "recommendations", 8),
                ),
            ),
            sections = JsonReader.arr(JsonReader.obj(root)?.get("sections")).mapNotNull(::parseSection),
        )
    }

    /** Returns null for an unknown type OR an empty section — both mean "do not render this". */
    private fun parseSection(v: Any?): StoreSection? {
        val id = JsonReader.str(v, "id") ?: return null
        val items = JsonReader.arr(JsonReader.obj(v)?.get("items"))
        return when (JsonReader.str(v, "type")) {
            "ticker" -> items.filterIsInstance<String>()
                .takeIf { it.isNotEmpty() }
                ?.let { StoreSection.Ticker(id, it) }

            "featured" -> items.mapNotNull(SupabaseStoreSource::parseItem)
                .takeIf { it.isNotEmpty() }
                ?.let { StoreSection.Featured(id, it) }

            "charts" -> JsonReader.arr(JsonReader.obj(v)?.get("tabs"))
                .mapNotNull(::parseChartTab)
                // Every tab empty means the chart has nothing to say, whatever the server sent.
                .takeIf { tabs -> tabs.any { it.entries.isNotEmpty() } }
                ?.let { StoreSection.Charts(id, it, JsonReader.str(v, "computedAt")) }

            "collections" -> items.mapNotNull(::parseCollection)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    StoreSection.Collections(
                        id = id,
                        title = JsonReader.str(v, "title") ?: "Collections",
                        subtitle = JsonReader.str(v, "subtitle"),
                        items = it,
                    )
                }

            "categories" -> items.mapNotNull(::parseCategory)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    StoreSection.Categories(id, JsonReader.str(v, "title") ?: "Browse by kind", it)
                }

            "personalized" -> items.mapNotNull(SupabaseStoreSource::parseItem)
                // The contract's floor. The server enforces it too; duplicating it here means a server
                // bug cannot produce a two-item "recommended for you" shelf.
                .takeIf { it.size >= MIN_PERSONALIZED }
                ?.let {
                    StoreSection.Personalized(
                        id = id,
                        seedProjectId = JsonReader.str(v, "seedProjectId"),
                        title = JsonReader.str(v, "title") ?: "Recommended",
                        subtitle = JsonReader.str(v, "subtitle"),
                        items = it,
                    )
                }

            "spotlight" -> parsePublisher(JsonReader.obj(v)?.get("publisher"))
                ?.let { StoreSection.Spotlight(id, it) }

            "list" -> items.mapNotNull(SupabaseStoreSource::parseItem)
                .takeIf { it.isNotEmpty() }
                ?.let { StoreSection.ItemList(id, JsonReader.str(v, "title") ?: "Projects", it) }

            "catalogue" -> items.mapNotNull(SupabaseStoreSource::parseItem)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    StoreSection.Catalogue(
                        id = id,
                        title = JsonReader.str(v, "title") ?: "Everything in the store",
                        order = JsonReader.str(v, "order") ?: "recency",
                        items = it,
                    )
                }

            "publish_pitch" -> StoreSection.PublishPitch(id, JsonReader.int(v, "projectCount"))

            // The rows come from the bundled templates on the device, so an empty `items` is expected
            // and is NOT a reason to drop the section.
            "bundled" -> StoreSection.Bundled(id)

            "ghost_shelves" -> items.mapNotNull(::parseGhost)
                .takeIf { it.isNotEmpty() }
                ?.let { StoreSection.GhostShelves(id, it) }

            // Unknown type: skipped in silence, by design.
            else -> null
        }
    }

    private fun parseChartTab(v: Any?): ChartTab? {
        val key = JsonReader.str(v, "key") ?: return null
        return ChartTab(
            key = key,
            label = JsonReader.str(v, "label") ?: key,
            entries = JsonReader.arr(JsonReader.obj(v)?.get("entries")).mapNotNull(::parseChartEntry),
        )
    }

    private fun parseChartEntry(v: Any?): ChartEntry? {
        val project = SupabaseStoreSource.parseItem(JsonReader.obj(v)?.get("project")) ?: return null
        val rank = JsonReader.int(v, "rank").takeIf { it > 0 } ?: return null
        // Absent means "new entrant" and must stay null — defaulting it to `rank` would silently render
        // every new entry as "flat" instead of as an arrow.
        val previous = (JsonReader.obj(v)?.get("previousRank") as? Long)?.toInt()
        return ChartEntry(rank = rank, previousRank = previous, project = project)
    }

    private fun parseCollection(v: Any?): StoreCollection? {
        val id = JsonReader.str(v, "id") ?: return null
        val title = JsonReader.str(v, "title") ?: return null
        return StoreCollection(
            id = id,
            eyebrow = JsonReader.str(v, "eyebrow").orEmpty(),
            title = title,
            iconKey = JsonReader.str(v, "iconKey"),
            projectCount = JsonReader.int(v, "projectCount"),
            previewIconKeys = JsonReader.strings(v, "previewIconKeys"),
            deeplink = JsonReader.str(v, "deeplink"),
        )
    }

    private fun parseCategory(v: Any?): RemoteCategory? {
        val id = JsonReader.str(v, "id") ?: return null
        return RemoteCategory(
            id = id,
            title = JsonReader.str(v, "title") ?: id,
            summary = JsonReader.str(v, "summary"),
            icon = JsonReader.str(v, "iconKey") ?: JsonReader.str(v, "icon"),
            color = JsonReader.str(v, "color"),
            count = JsonReader.int(v, "count"),
        )
    }

    private fun parsePublisher(v: Any?): StorePublisher? {
        val id = JsonReader.str(v, "id") ?: return null
        val name = JsonReader.str(v, "name") ?: return null
        return StorePublisher(
            id = id,
            handle = JsonReader.str(v, "handle"),
            name = name,
            bio = JsonReader.str(v, "bio"),
            location = JsonReader.str(v, "location"),
            avatarUrl = JsonReader.str(v, "avatarUrl"),
            verified = JsonReader.bool(v, "verified"),
            projectCount = JsonReader.int(v, "projectCount"),
            installCount = JsonReader.int(v, "installCount"),
            rating = JsonReader.float(v, "rating"),
            followerCount = JsonReader.int(v, "followerCount"),
        )
    }

    private fun parseGhost(v: Any?): GhostShelf? {
        val key = JsonReader.str(v, "key") ?: return null
        return GhostShelf(key = key, have = JsonReader.int(v, "have"), need = JsonReader.int(v, "need"))
    }

    /** The contract's floor for a personalized shelf. */
    const val MIN_PERSONALIZED = 4
}

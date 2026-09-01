package dev.ide.store

/**
 * The server-driven Explore feed.
 *
 * The server owns which sections exist, in what order, with what titles, and which of the three modes
 * the store is in; the client owns rendering. That split is what lets merchandising change the page —
 * or an enterprise instance move its thresholds — without an app release.
 *
 * Two rules make it safe, and both live in the parser rather than at call sites:
 *
 *  1. **Unknown section types are skipped**, not an error. The server can ship a new shelf before the
 *     app understands it; an old client silently renders the rest.
 *  2. **Empty sections never reach the UI.** The server drops them, and the parser drops any that slip
 *     through, so no screen has to decide whether an empty shelf is worth drawing.
 */

/** Which of the three Explore layouts to render. */
enum class StoreMode {
    /** Nothing published. The zero-data screen leads with the publish argument. */
    EMPTY,

    /** 1 to (charts threshold - 1) projects. One generous catalogue list, then the pitch. */
    SPARSE,

    /** Enough projects for shelves to rank honestly. */
    POPULATED;

    companion object {
        /**
         * Parse the wire value. An unrecognised mode falls back to [POPULATED] rather than [EMPTY]:
         * rendering the shelves the feed actually sent is a better failure than showing a
         * "nobody has published anything" page to a store that has content.
         */
        fun of(raw: String?): StoreMode = when (raw?.lowercase()) {
            "empty" -> EMPTY
            "sparse" -> SPARSE
            else -> POPULATED
        }
    }
}

/**
 * The thresholds at which each shelf switches on.
 *
 * Server-controlled, never hardcoded in the app — merchandising will want to tune them, and the
 * enterprise case genuinely differs. The defaults here are only what to assume if the field is absent
 * from an older response.
 */
data class StoreThresholds(
    val charts: Int = 10,
    val collections: Int = 12,
    val recommendations: Int = 8,
)

/**
 * What the store looks like right now, independent of any one section.
 *
 * [publishedProjectCount] is what the header badge shows. It is the honest disclosure that stops a
 * three-item page from reading as a bug.
 */
data class StoreState(
    val publishedProjectCount: Int = 0,
    /** False on an instance with publishing locked down: the pitch is hidden, the catalogue stays. */
    val acceptingSubmissions: Boolean = true,
    val bundledTemplatesVersion: String? = null,
    val thresholds: StoreThresholds = StoreThresholds(),
)

/** One entry on a ranked chart. */
data class ChartEntry(
    val rank: Int,
    /**
     * Where this sat on the previous snapshot, or null for a new entrant.
     *
     * Null is meaningful, not missing: the design renders an up arrow with no number for it, which is
     * why this is nullable rather than defaulted to [rank].
     */
    val previousRank: Int?,
    val project: RemoteStoreItem,
) {
    /** Positions gained (positive), lost (negative), or 0 when flat. Null for a new entrant. */
    val delta: Int? get() = previousRank?.let { it - rank }
}

/** One tab of the Top charts shelf. */
data class ChartTab(val key: String, val label: String, val entries: List<ChartEntry>)

/** An editorial shelf. The title is an outcome ("Ship your first Android app"), never a category. */
data class StoreCollection(
    val id: String,
    val eyebrow: String,
    val title: String,
    val iconKey: String? = null,
    val projectCount: Int = 0,
    /** Up to three glyph keys for the overlapping icon stack in the card's footer. */
    val previewIconKeys: List<String> = emptyList(),
    val deeplink: String? = null,
)

/** A publisher, as the spotlight card and the profile screen both need them. */
data class StorePublisher(
    val id: String,
    val handle: String? = null,
    val name: String,
    val bio: String? = null,
    val location: String? = null,
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val projectCount: Int = 0,
    val installCount: Int = 0,
    val rating: Float? = null,
    val followerCount: Int = 0,
)

/** One ghost shelf's progress toward switching on. */
data class GhostShelf(val key: String, val have: Int, val need: Int) {
    val remaining: Int get() = (need - have).coerceAtLeast(0)
}

/**
 * A section of the feed.
 *
 * Sealed so the UI's `when` is exhaustive over what it can draw, while the *parser* is what absorbs
 * section types this build has never heard of — they never become a [StoreSection] at all.
 */
sealed interface StoreSection {
    val id: String

    /** Decorative marquee of trending terms. */
    data class Ticker(override val id: String, val terms: List<String>) : StoreSection

    /** The hero carousel. */
    data class Featured(override val id: String, val items: List<RemoteStoreItem>) : StoreSection

    data class Charts(
        override val id: String,
        val tabs: List<ChartTab>,
        /** When the ranking was computed. The "live" dot is honest only because this is shown. */
        val computedAt: String? = null,
    ) : StoreSection

    data class Collections(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val items: List<StoreCollection>,
    ) : StoreSection

    data class Categories(
        override val id: String,
        val title: String,
        val items: List<RemoteCategory>,
    ) : StoreSection

    /** "Because you installed X". Never rendered below four items; the server enforces that floor. */
    data class Personalized(
        override val id: String,
        val seedProjectId: String?,
        val title: String,
        val subtitle: String? = null,
        val items: List<RemoteStoreItem>,
    ) : StoreSection

    data class Spotlight(override val id: String, val publisher: StorePublisher) : StoreSection

    /** A plain titled list ("New & updated"). */
    data class ItemList(
        override val id: String,
        val title: String,
        val items: List<RemoteStoreItem>,
    ) : StoreSection

    /**
     * The sparse state's single generous list: everything the store has, newest first.
     *
     * [order] is carried so the header can say "newest first" truthfully rather than assuming it.
     */
    data class Catalogue(
        override val id: String,
        val title: String,
        val order: String,
        val items: List<RemoteStoreItem>,
    ) : StoreSection

    /** The publish argument. [projectCount] drives the interpolated headline and its ordinal. */
    data class PublishPitch(override val id: String, val projectCount: Int) : StoreSection

    /** The offline scaffolds that ship with the IDE. Items come from the bundled templates, not the server. */
    data class Bundled(override val id: String) : StoreSection

    /** Dashed shelves with have/need counters, showing what switches on as the store grows. */
    data class GhostShelves(override val id: String, val shelves: List<GhostShelf>) : StoreSection
}

/**
 * The whole feed.
 *
 * [sections] is already filtered to what this build can render, in server order.
 */
data class StoreFeed(
    val version: Int = 0,
    val generatedAt: String? = null,
    val mode: StoreMode = StoreMode.POPULATED,
    val state: StoreState = StoreState(),
    val sections: List<StoreSection> = emptyList(),
) {
    /** Every project the feed mentions, deduplicated — what the offline cache needs to keep. */
    val allItems: List<RemoteStoreItem>
        get() = sections.flatMap {
            when (it) {
                is StoreSection.Featured -> it.items
                is StoreSection.Personalized -> it.items
                is StoreSection.ItemList -> it.items
                is StoreSection.Catalogue -> it.items
                is StoreSection.Charts -> it.tabs.flatMap { t -> t.entries.map { e -> e.project } }
                else -> emptyList()
            }
        }.distinctBy { it.id }
}

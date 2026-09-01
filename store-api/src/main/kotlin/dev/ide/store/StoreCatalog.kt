package dev.ide.store

/**
 * The remote Projects Store contract.
 *
 * These types mirror what `store_catalog()` and `store_search()` return (see
 * `supabase/migrations/20260901000500_store_api.sql`) — one JSON document per call, so the client makes
 * one round trip and can write the same bytes straight to its offline cache.
 *
 * Nothing here knows about Supabase. The engine depends on the ports; `store-impl` supplies the
 * PostgREST implementation.
 */

/** What a [RemoteStoreItem] is, which decides how opening it behaves. */
enum class RemoteItemKind { TEMPLATE, SAMPLE, COMMUNITY;

    companion object {
        /** Parse the `kind` column's lowercase value; unknown kinds fall back to [COMMUNITY]. */
        fun of(raw: String?): RemoteItemKind = when (raw?.lowercase()) {
            "template" -> TEMPLATE
            "sample" -> SAMPLE
            else -> COMMUNITY
        }
    }
}

/**
 * One catalog entry, resolved to its latest approved version.
 *
 * [id] is the row's `slug`, and it doubles as the **overlay key**: an item whose id equals a bundled
 * template id replaces that item's presentation rather than adding a second card.
 *
 * The nullable/negative defaults are load-bearing. [rating] is null when nothing has been rated —
 * the backend deliberately omits the field rather than sending `0.0`, because "rated 0.0 out of 5" and
 * "not rated" are opposite claims. [storagePath] is null for an item that routes through a bundled
 * template ([templateId]) instead of downloading a payload.
 */
data class RemoteStoreItem(
    val id: String,
    val kind: RemoteItemKind,
    val title: String,
    val summary: String,
    val description: String = summary,
    /**
     * The first sentence of [description], truncated **by the server** so every client agrees where it
     * ends. The sparse catalogue card shows this instead of the full description.
     */
    val blurb: String? = null,
    val category: String,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val highlights: List<String> = emptyList(),
    val accent: String? = null,
    val icon: String? = null,
    val author: String? = null,
    val authorHandle: String? = null,
    val verified: Boolean = false,
    val templateId: String? = null,
    val featured: Boolean = false,
    val installs: Int = 0,
    val rating: Float? = null,
    val ratingCount: Int = 0,
    val version: String? = null,
    val versionCode: Int = 0,
    /** Path inside the public `store-payloads` bucket. Null ⇒ nothing to download. */
    val storagePath: String? = null,
    val sizeBytes: Long = -1,
    /** Lowercase hex SHA-256 of the payload; the client verifies before unzipping anything. */
    val sha256: String? = null,
    val changelog: String? = null,
    /** ISO 8601. The client renders the relative string ("Published 3 days ago"). */
    val publishedAt: String? = null,
    val updatedAt: String? = null,
)

/** A filter tile on the Explore screen. */
data class RemoteCategory(
    val id: String,
    val title: String,
    val summary: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val count: Int = 0,
)

/** A titled shelf. [id] matches what the bundled catalog emits (`templates` / `samples` / `community`). */
data class RemoteSection(
    val id: String,
    val title: String,
    val summary: String? = null,
    val items: List<RemoteStoreItem> = emptyList(),
)

/**
 * The whole Explore screen in one document.
 *
 * [generatedAt] is the server's timestamp, carried through the offline cache so a stale catalog can be
 * recognised as stale rather than silently trusted forever.
 */
data class RemoteCatalog(
    val version: Int = 1,
    val generatedAt: String? = null,
    val categories: List<RemoteCategory> = emptyList(),
    val featured: List<RemoteStoreItem> = emptyList(),
    val sections: List<RemoteSection> = emptyList(),
) {
    val isEmpty: Boolean get() = featured.isEmpty() && sections.all { it.items.isEmpty() }
}

/** How search results are ordered. Maps onto `store_search`'s `p_sort`. */
enum class StoreSort(val wire: String) {
    RELEVANCE("relevance"),
    TOP_RATED("top_rated"),
    MOST_INSTALLED("most_installed"),
    NEWEST("newest"),
}

/** A search request. Every field is optional; an all-defaults query returns the newest of everything. */
data class StoreQuery(
    val text: String = "",
    val category: String? = null,
    val kind: RemoteItemKind? = null,
    val minRating: Float = 0f,
    val sort: StoreSort = StoreSort.RELEVANCE,
    val limit: Int = 50,
    val offset: Int = 0,
)

/**
 * The catalog transport.
 *
 * Every method is allowed to fail — the store is a network feature on a mobile device, and the caller's
 * job is to fall back to the bundled catalog rather than to show an error. Implementations therefore
 * return a [StoreResult] instead of throwing.
 */
interface StoreCatalogSource {
    /** Whether this source is configured at all (a build with no endpoint has none). */
    fun configured(): Boolean = true

    /**
     * The IDE build this installation is, used to hide items that need a newer app.
     *
     * A property of the source rather than an argument to every call: it cannot change between requests,
     * and the UI has no way to know it. Null means "do not filter by build".
     */
    val appBuild: Int? get() = null

    /**
     * The catalog for an IDE at [appBuild]. The build number filters out items that need a newer IDE, so
     * an old install never shows a card whose payload it cannot open.
     */
    fun catalog(appBuild: Int): StoreResult<RemoteCatalog>

    fun search(query: StoreQuery, appBuild: Int): StoreResult<List<RemoteStoreItem>>

    /**
     * The server-driven Explore feed, as its **raw JSON document**.
     *
     * Raw rather than parsed on purpose: the caller writes these exact bytes to its offline cache and
     * parses the same string, so the cached copy cannot drift from what was rendered, and no second
     * encoder is needed to write it back out.
     *
     * [seedSlug] is the device's most-recently-installed item. Only the client knows it — under the
     * anonymous personalization model the server holds install ids, not "this user's" installs.
     */
    fun feedDocument(seedSlug: String? = null): StoreResult<String> =
        StoreResult.Unavailable("No store endpoint")

    /**
     * Download an approved payload from the public bucket into [into].
     *
     * [storagePath] and [expectedSha256] come from the catalog row. The hash is **not optional**: this is
     * a zip from a public bucket that will be unpacked into the user's workspace, so it is verified
     * before anything is extracted. A mismatch fails the install rather than warning.
     *
     * [onProgress] receives 0f..1f. It is called from the calling thread, often, so it must be cheap.
     */
    fun downloadPayload(
        storagePath: String,
        expectedSha256: String?,
        expectedBytes: Long,
        into: java.io.File,
        onProgress: (Float) -> Unit = {},
    ): StoreResult<Unit> = StoreResult.Unavailable("No store endpoint")

    /**
     * Count one install of [slug], deduplicated server-side by [installId].
     *
     * [installId] is the anonymous UUID the app already generates for analytics — not an account, not a
     * device id. Fire-and-forget: a failure here must never block an install.
     */
    /**
     * The categories a submission may declare, as (slug, title).
     *
     * Read separately from the feed rather than lifted out of it: the feed's category shelf is a
     * merchandising surface that does not exist in every mode (an empty store has no shelves at all), and
     * an empty store is exactly when the first submission is made. The slug is what the backend stores, so
     * the form has to have it and cannot infer it from a display title.
     */
    fun categories(): StoreResult<List<Pair<String, String>>> =
        StoreResult.Unavailable("No store endpoint")

    fun recordInstall(slug: String, installId: String)

    companion object {
        /** A source that is not wired: the store falls back to the bundled catalog. */
        val Unconfigured: StoreCatalogSource = object : StoreCatalogSource {
            override fun configured() = false
            override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("No store endpoint")
            override fun search(query: StoreQuery, appBuild: Int) = StoreResult.Unavailable<List<RemoteStoreItem>>("No store endpoint")
            override fun feedDocument(seedSlug: String?) = StoreResult.Unavailable<String>("No store endpoint")
            override fun categories() = StoreResult.Unavailable<List<Pair<String, String>>>("No store endpoint")
            override fun downloadPayload(storagePath: String, expectedSha256: String?, expectedBytes: Long, into: java.io.File, onProgress: (Float) -> Unit) =
                StoreResult.Unavailable<Unit>("No store endpoint")
            override fun recordInstall(slug: String, installId: String) = Unit
        }
    }
}

/**
 * The outcome of a store call.
 *
 * Three cases rather than two, because the caller treats them differently: [Ok] renders,
 * [Unavailable] falls back to the bundled catalog silently (offline is the normal case on a phone), and
 * [Failed] is worth surfacing because it means the server said no for a reason the user might fix.
 */
sealed interface StoreResult<T> {
    data class Ok<T>(val value: T) : StoreResult<T>
    data class Unavailable<T>(val reason: String) : StoreResult<T>
    data class Failed<T>(val message: String, val status: Int = 0) : StoreResult<T>

    val valueOrNull: T? get() = (this as? Ok<T>)?.value
}

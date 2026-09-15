package dev.ide.store.bridge

import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreFeed
import dev.ide.store.StoreResult
import dev.ide.store.impl.StoreFeedParser
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.StoreLock
import dev.ide.store.impl.platform.parentPath
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Explore feed: fetched, memoized, cached to disk, and mapped for the UI.
 *
 * Every host asks the same three questions in the same order — is there a fresh answer in hand, can the
 * network be reached, is there a last-good copy on disk — and gets the semantics wrong in the same two
 * ways if it writes them again:
 *
 *  - A feed that came off the disk is marked [UiStoreFeed.fromCache], because presenting a stale ranking
 *    as live is a claim the app has no basis for.
 *  - Null is not an empty feed. Nothing cached and no network means "we could not reach the store", and
 *    the caller falls back to its bundled shelves; an empty feed means "nobody has published anything",
 *    which is a different screen.
 *
 * It also remembers each item's payload coordinates as they go past, **including from the cached copy**,
 * so an install after a cold offline start knows what to download instead of reporting that the item has
 * nothing to download.
 */
class StoreFeedCache(
    private val source: StoreCatalogSource,
    /** Where the last good feed document is kept, or null on a host with nowhere to write. */
    private val cachePath: () -> String?,
    /**
     * The bundled catalog keyed by the id a remote row would use, for the overlay.
     *
     * Empty on a host that bundles nothing, which is not a special case: the overlay is a join, and a
     * join with an empty side is the remote feed unchanged.
     */
    private val bundled: () -> Map<String, UiStoreItem> = { emptyMap() },
    private val liveMs: Long = FEED_MEMO_MS,
    private val cachedMs: Long = CACHED_FEED_MEMO_MS,
) {

    /** Guarded by [fetchLock], which also coalesces concurrent readers into one request. */
    private val fetchLock = Mutex()
    private val memo = FeedMemo(liveMs, cachedMs)

    private val payloads = HashMap<String, StoreInstaller.Payload>()
    private val payloadLock = StoreLock()

    /**
     * The feed for [seed], or null when there is no remote store to ask and nothing cached.
     *
     * The lock is held across the fetch on purpose: two screens asking at once make one request and share
     * the answer, rather than racing to write the same cache file.
     */
    suspend fun feed(seed: String?, refresh: Boolean): UiStoreFeed? {
        if (!source.configured()) return null
        return fetchLock.withLock {
            if (!refresh) memo.get(seed)?.let { return@withLock it }
            fetch(seed).also { loaded -> if (loaded != null) memo.put(seed, loaded) }
        }
    }

    /** What [id] needs to download, learned from a feed that has gone past. */
    fun payload(id: String): StoreInstaller.Payload? = payloadLock.withLock { payloads[id] }

    /**
     * Forget the memoized answer.
     *
     * Called after an install: the install count on a card just changed, and so did the seed the
     * personalized shelf is built from, so the memo now describes the store as it was before.
     */
    fun clear() = memo.clear()

    private suspend fun fetch(seed: String?): UiStoreFeed? = withContext(storeIo) {
        val bundledItems = bundled()
        when (val result = source.feedDocument(seed)) {
            is StoreResult.Ok -> {
                val parsed = StoreFeedParser.parse(result.value)
                if (parsed == null) {
                    // A response we cannot read is not evidence about the store, so behave as offline.
                    cached(bundledItems)
                } else {
                    // Cache the exact bytes that were just rendered, so the cached copy cannot drift.
                    write(result.value)
                    // Remember the payload coordinates so install() needs no second round trip.
                    remember(parsed)
                    StoreFeedMapper.toUi(parsed, bundledItems)
                }
            }
            // Offline or a server hiccup: fall back to whatever was last seen.
            else -> cached(bundledItems)
        }
    }

    private fun cached(bundledItems: Map<String, UiStoreItem>): UiStoreFeed? {
        val path = cachePath() ?: return null
        if (!StoreFs.isFile(path)) return null
        val raw = StoreFs.readText(path)?.takeIf { it.isNotBlank() } ?: return null
        val parsed = StoreFeedParser.parse(raw) ?: return null
        // The cached rows are as good a source of payload coordinates as the live ones, and the sha256 is
        // still checked against the bytes: without this, an install from a cached feed would fail claiming
        // the item has nothing to download.
        remember(parsed)
        return StoreFeedMapper.toUi(parsed, bundledItems).copy(fromCache = true)
    }

    /** Best effort: a cache that cannot be written must not fail the fetch that produced it. */
    private fun write(document: String) {
        val path = cachePath() ?: return
        parentPath(path)?.let { StoreFs.mkdirs(it) }
        StoreFs.writeText(path, document)
    }

    private fun remember(feed: StoreFeed) {
        payloadLock.withLock {
            feed.allItems.forEach { item ->
                val path = item.storagePath ?: return@forEach
                payloads[item.id] = StoreInstaller.Payload(
                    itemId = item.id,
                    storagePath = path,
                    sha256 = item.sha256,
                    sizeBytes = item.sizeBytes,
                    title = item.title,
                    version = item.version,
                )
            }
        }
    }

    companion object {
        /**
         * How long a fetched feed is reused.
         *
         * The Store tab is a tab, so the screen leaves composition every time the reader looks at their
         * projects; without this, "fetch when the screen appears" was a full request per visit, and the
         * deep-link lookup made a second one of its own. Short, because the feed is ranked content that
         * moves.
         */
        const val FEED_MEMO_MS = 5L * 60 * 1000

        /**
         * How long a feed that came off the disk is reused.
         *
         * Much shorter than a live one: the only reason a cached feed is on screen is that the network
         * was down a moment ago, and a moment later it may not be.
         */
        const val CACHED_FEED_MEMO_MS = 30L * 1000
    }
}

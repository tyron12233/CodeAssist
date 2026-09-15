package dev.ide.store.bridge

import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreResult
import dev.ide.store.impl.fetchHttps
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath
import dev.ide.store.impl.platform.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Remote images as local files.
 *
 * The galleries decode files rather than URLs, so this is what turns a published screenshot or a
 * publisher's avatar into something a screen can draw. Three kinds, and they differ in ways that matter:
 * a published screenshot is anonymous and in a public bucket, a submission's is private and needs the
 * moderator's session, and an avatar is not in the store's buckets at all — it is wherever the identity
 * provider serves it, which is why that one is a plain HTTPS GET with its own limits.
 */
class StoreMediaCache(
    private val source: StoreCatalogSource,
    /** The store's cache directory, or null on a host with nowhere to write. */
    private val root: () -> String?,
    /**
     * Fetches a private submission image; answers null on a host with no moderation.
     *
     * A lambda rather than the adapter itself, so a host may declare this cache beside the rest of its
     * media handling and its moderation beside the rest of its moderation, in whichever order reads best.
     */
    private val moderation: () -> StoreModeration? = { null },
) {

    /**
     * One lock per lane.
     *
     * Two jobs at once. It deduplicates: a path always takes the same lane, so two cards asking for the
     * same screenshot download it once and the second finds the file already there. And it caps
     * concurrency: a flung list used to open a socket per visible card, and now opens at most one per lane.
     */
    private val lanes = List(MEDIA_LANES) { Mutex() }

    /**
     * A published screenshot, cached under the store's own directory keyed by its storage path.
     *
     * Published screenshot keys are version-scoped (`slug/version/shot-0.png`, written by approval), so
     * the bytes at a path never change and a cached file cannot go stale.
     */
    suspend fun screenshot(storagePath: String): String? = withContext(storeIo) {
        val cached = cachePath("media", storagePath) ?: return@withContext null
        if (isCached(cached)) return@withContext cached
        laneFor(storagePath).withLock {
            // Re-checked inside the lane: whoever held it may have been fetching this very path.
            if (isCached(cached)) return@withLock cached
            when (source.downloadMedia(storagePath, cached)) {
                is StoreResult.Ok -> cached
                // A screenshot that will not load is not worth an error surface: the gallery simply shows
                // the ones that did.
                else -> null
            }
        }
    }

    /**
     * A screenshot from a submission under review.
     *
     * Kept apart from [screenshot] because the bucket is different and so is the authority: this one is in
     * the PRIVATE uploads bucket and needs the moderator's session, which the anonymous download has no
     * way to present. The cache directory is separate too, so an image from a submission that is later
     * refused is not sitting under the same prefix as published art.
     */
    suspend fun submissionImage(storagePath: String): String? = withContext(storeIo) {
        val cached = cachePath("review", storagePath) ?: return@withContext null
        if (isCached(cached)) return@withContext cached
        val fetched = moderation()?.downloadSubmissionImage(storagePath, cached) ?: false
        if (fetched) cached else null
    }

    /**
     * An avatar, by URL rather than by storage path.
     *
     * The URL is not the store's, so it gets three limits of its own: https only, a size cap, and a short
     * timeout. Re-fetched once a week even though the name has not changed, because a provider serves a
     * new picture from the same URL and caching on the URL alone would pin the first face forever.
     */
    suspend fun avatar(url: String): String? = withContext(storeIo) {
        if (!url.startsWith("https://")) return@withContext null
        val dir = root() ?: return@withContext null
        val cached = joinPath(dir, "store/avatars/${url.hashCode().toUInt().toString(16)}.img")
        if (isCached(cached) && nowMillis() - StoreFs.modifiedMillis(cached) < AVATAR_CACHE_MS) {
            return@withContext cached
        }
        laneFor(url).withLock {
            if (fetchHttps(url, cached, MAX_AVATAR_BYTES, AVATAR_TIMEOUT_MS)) cached else null
        }
    }

    private fun cachePath(kind: String, storagePath: String): String? =
        root()?.let { joinPath(it, "store/$kind/${storagePath.replace('/', '_')}") }

    private fun isCached(path: String): Boolean = StoreFs.isFile(path) && StoreFs.size(path) > 0

    /** The lane a path downloads in. Same path, same lane, which is what makes it deduplicate. */
    private fun laneFor(key: String): Mutex =
        lanes[(key.hashCode().toLong() and 0x7fffffffL).toInt() % lanes.size]

    private companion object {
        const val MEDIA_LANES = 4

        /** An avatar is a small square. Anything past this is not one. */
        const val MAX_AVATAR_BYTES = 2L * 1024 * 1024

        const val AVATAR_CACHE_MS = 7L * 24 * 60 * 60 * 1000

        const val AVATAR_TIMEOUT_MS = 8_000
    }
}

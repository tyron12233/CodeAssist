package dev.ide.store.impl

import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.StoreHttp

/**
 * Fetch [url] to [intoPath], for content that is NOT in the store's buckets.
 *
 * There is exactly one of these: the avatar an identity provider serves on the publisher row. The store's
 * own downloads go through [dev.ide.store.StoreCatalogSource], which knows the bucket, the key and the
 * checksum; this one knows none of those, which is precisely why it is separate and why it is narrow —
 * https only, a byte cap, and a short timeout, because the URL came from somewhere else.
 *
 * Returns true when the file is there and complete. A partial or oversized response leaves nothing behind.
 */
fun fetchHttps(url: String, intoPath: String, maxBytes: Long, timeoutMs: Int): Boolean {
    if (!url.startsWith("https://")) return false
    val reply = StoreHttp(timeoutMs, timeoutMs).download(
        url = url,
        headers = emptyMap(),
        intoPath = intoPath,
        limitBytes = maxBytes,
    )
    if (!reply.ok) {
        StoreFs.delete(intoPath)
        return false
    }
    return true
}

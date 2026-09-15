package dev.ide.store.impl.platform

/**
 * SHA-256 of [bytes] as lower-case hex.
 *
 * The store hashes two things: a packaged archive before it is uploaded, and a downloaded payload before
 * it is unpacked. The second is the one that matters — it is a zip from a public bucket about to be
 * written into the user's workspace — so the digest is not optional anywhere it is offered.
 *
 * Streaming digests live in [StoreHttp.download], which hashes while it writes so a 5 MB archive is never
 * held twice.
 */
expect fun sha256Hex(bytes: ByteArray): String

/** Lower-case hex for a digest. Shared so the two platforms cannot format the same bytes differently. */
internal fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return out.toString()
}

package dev.ide.deps.impl

import dev.ide.platform.writeFileAtomically

/**
 * The single injectable I/O seam of the resolver: fetch the bytes at an absolute [url] (a `.pom`,
 * `.jar`, `.aar`, or a search endpoint), or return null when the resource does not exist (HTTP 404 /
 * a repo that doesn't carry it). Implementations should return null — not throw — for "not found", so
 * the resolver can fall through to the next repository; throwing is reserved for genuine I/O failures.
 *
 * Production wires [HttpArtifactFetcher]; tests wire a fixture fetcher backed by local files, so the
 * whole engine runs offline and deterministically.
 */
fun interface ArtifactFetcher {
    fun fetch(url: String): ByteArray?

    /**
     * Stream the resource at [url] directly into [dest], returning true if it was written and false when
     * the resource is absent (404 / not carried by this repo). Used for large artifacts (jars/aars) so the
     * bytes flow socket → disk instead of buffering the whole file in heap — a real difference on ART, where
     * a dozen concurrent Compose-sized jars would otherwise be resident at once. The default buffers via
     * [fetch] so fixture fetchers (which only implement [fetch]) keep working; [HttpArtifactFetcher] streams.
     */
    fun fetchTo(url: String, dest: String, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }): Boolean {
        val bytes = fetch(url) ?: return false
        if (!writeFileAtomically(dest, bytes)) return false
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return true
    }
}

/**
 * The default [ArtifactFetcher] over whatever HTTP stack the platform has — [DepsHttp], which is
 * `HttpURLConnection` on the JVM and ART and `NSURLSession` on iOS.
 *
 * Status mapping is deliberate: ONLY 404 maps to null ("this repo doesn't carry it" — the resolver falls
 * through to the next repo and may negative-cache the miss). Every other non-2xx — INCLUDING 403 — throws,
 * so the resolver treats it as a transient failure (logged with the URL, retriable, NOT negative-cached)
 * instead of silently collapsing it into a "hard 404". A 403 is a repository REFUSING the request (WAF,
 * geo/IP block, rate-limit), not evidence the artifact is absent; conflating it with 404 made a coordinate
 * that plainly exists on Maven Central look unresolvable, with no clue in the logs as to why.
 */
class HttpArtifactFetcher(
    connectTimeoutMs: Int = 15_000,
    readTimeoutMs: Int = 30_000,
    userAgent: String = "CodeAssist-deps/1.0",
) : ArtifactFetcher {

    private val http = DepsHttp(connectTimeoutMs, readTimeoutMs, userAgent)

    override fun fetch(url: String): ByteArray? {
        val reply = http.get(url)
        return check(url, reply)?.let { reply.body }
    }

    override fun fetchTo(url: String, dest: String, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit): Boolean {
        // Pipe the response straight to disk — never hold the whole jar in heap — reporting bytes as they
        // flow so the resolver can drive a real download bar. The total is -1 when the server sends no
        // Content-Length (a chunked response); the callback then advances against an unknown total.
        val reply = http.download(url, dest, onProgress)
        return check(url, reply) != null
    }

    /**
     * Null for an absent resource, Unit for one that was fetched; anything else throws.
     *
     * A reply that never reached a server ([HttpReply.error]) is an I/O failure, not an absence: the
     * resolver must be free to retry it, and must not record a miss for a coordinate it never asked about.
     */
    private fun check(url: String, reply: HttpReply): Unit? = when {
        reply.error != null -> throw DepsHttpException("GET $url failed: ${reply.error}")
        reply.status == 404 -> null
        reply.status in 200..299 -> Unit
        // 403 = the repository REFUSED the request (WAF, geo/IP block, rate-limit) — a fetch FAILURE, not an
        // absent artifact. Throwing marks it retriable instead of recording a bogus "hard 404" for a
        // coordinate that actually exists.
        reply.status == 403 ->
            throw DepsHttpException("GET $url forbidden: HTTP 403 (repository blocked or rate-limited this request)")

        else -> throw DepsHttpException("GET $url failed: HTTP ${reply.status}")
    }
}

/** A fetch that did not produce an answer: no network, a timeout, or a status the caller cannot use. */
class DepsHttpException(message: String) : RuntimeException(message)

/**
 * What one request produced.
 *
 * [status] is 0 when the request never reached a server, in which case [error] says why. The two are kept
 * apart because the resolver treats them differently: a status the server chose may be a real 404 to
 * remember, while "no network" is the ordinary case on a phone and must never be cached as absence.
 */
class HttpReply(
    val status: Int,
    val body: ByteArray? = null,
    val error: String? = null,
)

/**
 * The one HTTP call the resolver makes, and the only thing in this module a platform supplies itself.
 *
 * Everything above it — which URL, which repository order, what a 404 means, when to retry — is identical on
 * a phone, a desktop and a simulator, so none of it is written twice. What differs is the socket:
 * `HttpURLConnection` on the JVM and ART, `NSURLSession` on iOS. The same seam `:store-impl` already draws
 * for Supabase, drawn again here rather than shared, because the two want different things of it (that one
 * posts JSON and reads strings; this one streams binaries to disk) and a single client serving both would be
 * the union of two feature sets neither needs.
 *
 * **Blocking on purpose.** The resolver runs its fetches on an IO dispatcher and bounds them with a
 * semaphore, so this mirrors that rather than introducing a second concurrency model halfway down.
 */
expect class DepsHttp(connectTimeoutMs: Int, readTimeoutMs: Int, userAgent: String) {

    /** GET [url], following redirects, and read the whole body. */
    fun get(url: String): HttpReply

    /**
     * GET [url] and stream the body into the file at [dest], calling [onProgress] as bytes arrive.
     *
     * The reply's body is null: the point is that the bytes never accumulate in memory. [onProgress] is
     * given the total from `Content-Length`, or -1 when the server sent none.
     */
    fun download(url: String, dest: String, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit): HttpReply
}

package dev.ide.deps.impl

import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

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
    fun fetchTo(url: String, dest: Path): Boolean {
        val bytes = fetch(url) ?: return false
        Files.write(dest, bytes)
        return true
    }
}

/**
 * The default [ArtifactFetcher] over `java.net.HttpURLConnection` — present on both the JVM and ART, so
 * the same code runs on desktop and on-device. Follows redirects, sets sane timeouts and a User-Agent.
 *
 * Status mapping is deliberate: ONLY 404 maps to null ("this repo doesn't carry it" — the resolver falls
 * through to the next repo and may negative-cache the miss). Every other non-2xx — INCLUDING 403 — throws,
 * so the resolver treats it as a transient failure (logged with the URL, retriable, NOT negative-cached)
 * instead of silently collapsing it into a "hard 404". A 403 is a repository REFUSING the request (WAF,
 * geo/IP block, rate-limit), not evidence the artifact is absent; conflating it with 404 made a coordinate
 * that plainly exists on Maven Central look unresolvable, with no clue in the logs as to why.
 */
class HttpArtifactFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val userAgent: String = "CodeAssist-deps/1.0",
) : ArtifactFetcher {
    override fun fetch(url: String): ByteArray? =
        openResolved(url)?.inputStream?.use { it.readBytes() }

    override fun fetchTo(url: String, dest: Path): Boolean {
        val conn = openResolved(url) ?: return false
        // Pipe the socket straight to disk — never hold the whole jar in heap.
        conn.inputStream.use { input -> Files.newOutputStream(dest).use { out -> input.copyTo(out, STREAM_BUFFER) } }
        return true
    }

    /**
     * Open [url], follow redirects, and return a connection sitting on a 200 response (its body unread), or
     * null ONLY on a 404 (the resource is absent). A 403 or any other non-2xx throws (see the class doc), so a
     * blocked/forbidden repo is never mistaken for an absent artifact. Callers read & close
     * `inputStream` themselves; the connection is never `disconnect()`ed — draining the body and closing the
     * stream returns the socket to the JVM's keep-alive pool, so the many parallel fetches against one repo
     * reuse connections instead of paying a fresh TCP+TLS handshake each time.
     */
    private fun openResolved(url: String): HttpURLConnection? {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> return conn
                // 404 is the ONLY "genuinely absent" signal: return null so the resolver falls through to the
                // next repo (and may negative-cache the miss). 403 is handled separately below — it is NOT absence.
                code == HttpURLConnection.HTTP_NOT_FOUND -> {
                    conn.errorStream?.use { it.readBytes() }   // drain so the connection can be reused
                    return null
                }
                code in REDIRECT_CODES -> {
                    conn.errorStream?.use { it.readBytes() }
                    val location = conn.getHeaderField("Location") ?: return null
                    current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                    return@repeat
                }
                // 403 = the repository REFUSED the request (WAF, geo/IP block, rate-limit) — a fetch FAILURE,
                // not an absent artifact. Throw so the resolver logs the URL + status and marks it retriable
                // instead of recording a bogus "hard 404" for a coordinate that actually exists.
                code == HttpURLConnection.HTTP_FORBIDDEN -> {
                    conn.errorStream?.use { it.readBytes() }
                    throw java.io.IOException("GET $current forbidden: HTTP 403 (repository blocked or rate-limited this request)")
                }
                else -> {
                    conn.errorStream?.use { it.readBytes() }
                    throw java.io.IOException("GET $current failed: HTTP $code")
                }
            }
        }
        throw java.io.IOException("too many redirects for $url")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val STREAM_BUFFER = 64 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

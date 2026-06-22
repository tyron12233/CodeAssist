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
 * the same code runs on desktop and on-device. Follows redirects, sets sane timeouts and a User-Agent,
 * and maps 404 to null. A non-2xx that isn't 404 throws so callers can distinguish "absent" from "broke".
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
     * null when the resource is absent (404/403). Throws for genuine I/O failures. Callers read & close
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
                code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_FORBIDDEN -> {
                    conn.errorStream?.use { it.readBytes() }   // drain so the connection can be reused
                    return null
                }
                code in REDIRECT_CODES -> {
                    conn.errorStream?.use { it.readBytes() }
                    val location = conn.getHeaderField("Location") ?: return null
                    current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                    return@repeat
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

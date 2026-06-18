package dev.ide.deps.impl

import java.net.HttpURLConnection
import java.net.URL

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
    override fun fetch(url: String): ByteArray? {
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
            // NB: never `disconnect()` — draining the body and letting the stream close returns the socket
            // to the JVM's keep-alive pool, so the many parallel fetches against one repo reuse connections
            // instead of paying a fresh TCP+TLS handshake each time. disconnect() would defeat that pooling.
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> return conn.inputStream.use { it.readBytes() }
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
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

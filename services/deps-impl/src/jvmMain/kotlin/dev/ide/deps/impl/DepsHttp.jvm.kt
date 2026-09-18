package dev.ide.deps.impl

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths

/**
 * `HttpURLConnection`, which is present on both the JVM and ART, so desktop and device run the same code.
 *
 * Redirects are followed by hand rather than through `instanceFollowRedirects`, because the JDK declines to
 * follow one that changes protocol (http → https, which several mirrors still answer with) and the resulting
 * 3xx would otherwise reach the caller as an unusable status.
 */
actual class DepsHttp actual constructor(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val userAgent: String,
) {

    actual fun get(url: String): HttpReply = request(url) { conn ->
        HttpReply(status = conn.responseCode, body = conn.inputStream.use { it.readBytes() })
    }

    actual fun download(
        url: String,
        dest: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): HttpReply = request(url) { conn ->
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            Files.newOutputStream(Paths.get(dest)).use { out ->
                val buf = ByteArray(STREAM_BUFFER)
                var readTotal = 0L
                var sinceReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    readTotal += n
                    sinceReport += n
                    // Throttled so a large jar does not flood the callback: at most one report per step,
                    // plus a final one so the bar always lands on the true total.
                    if (sinceReport >= PROGRESS_STEP) { onProgress(readTotal, total); sinceReport = 0L }
                }
                onProgress(readTotal, total)
            }
        }
        HttpReply(status = conn.responseCode)
    }

    /**
     * Open [url], follow redirects, and hand [onOk] a connection sitting on a 2xx.
     *
     * A non-2xx is returned as a status with its error body drained — draining is what lets the socket go
     * back to the keep-alive pool, so the many parallel fetches against one repository reuse connections
     * instead of paying a fresh TCP+TLS handshake each. An exception becomes [HttpReply.error]: a request
     * that never reached a server is not a status the caller can reason about.
     */
    private fun request(url: String, onOk: (HttpURLConnection) -> HttpReply): HttpReply = try {
        var current = url
        var reply: HttpReply? = null
        var redirects = 0
        while (reply == null) {
            if (redirects++ > MAX_REDIRECTS) throw IOException("too many redirects for $url")
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
                code in 200..299 -> reply = onOk(conn)
                code in REDIRECT_CODES -> {
                    conn.errorStream?.use { it.readBytes() }
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("redirect with no Location for $current")
                    current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                }

                else -> {
                    conn.errorStream?.use { it.readBytes() }
                    reply = HttpReply(status = code)
                }
            }
        }
        reply
    } catch (e: Throwable) {
        HttpReply(status = 0, error = e.message ?: e::class.simpleName ?: "I/O failure")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val STREAM_BUFFER = 64 * 1024
        const val PROGRESS_STEP = 128 * 1024   // report download progress at most once per this many bytes
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

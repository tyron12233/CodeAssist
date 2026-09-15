package dev.ide.store.impl.platform

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * `java.net.HttpURLConnection`, which both hosts on this side of the seam already have: the JDK supplies
 * it on the desktop and Android backs it with OkHttp on ART. Stdlib only, deliberately — the same path
 * the analytics sink and the dependency resolver take, with no extra dependency and no shading.
 */
internal actual class StoreHttp actual constructor(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {

    actual fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpReply = try {
        val conn = open(url, method, headers)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        // Drain either stream so the socket returns to the keep-alive pool; never disconnect().
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        HttpReply(code, text)
    } catch (e: Exception) {
        HttpReply(0, "", e.message ?: "Network unavailable")
    }

    actual fun download(
        url: String,
        headers: Map<String, String>,
        intoPath: String,
        expectedBytes: Long,
        limitBytes: Long,
        hash: Boolean,
        onProgress: (Float) -> Unit,
    ): DownloadReply {
        val into = File(intoPath)
        return try {
            val conn = open(url, "GET", headers)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.use { it.readBytes() }
                return DownloadReply(code, 0, null)
            }
            val total = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
            val digest = if (hash) MessageDigest.getInstance("SHA-256") else null
            var read = 0L
            var overflowed = false
            into.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                into.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        if (read + n > limitBytes) { overflowed = true; break }
                        digest?.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toDouble() / total).coerceIn(0.0, 1.0).toFloat())
                    }
                }
            }
            if (overflowed) {
                into.delete()
                return DownloadReply(code, read, null, tooLarge = true)
            }
            DownloadReply(code, read, digest?.digest()?.toHexString())
        } catch (e: Exception) {
            // A half-written file decodes as garbage and fails a checksum it never should have reached.
            into.delete()
            DownloadReply(0, 0, null, e.message ?: "Network unavailable")
        }
    }

    actual fun upload(
        method: String,
        url: String,
        headers: Map<String, String>,
        filePath: String,
        contentType: String,
    ): HttpReply = try {
        val file = File(filePath)
        val conn = open(url, method, headers)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        // Streamed at a known length so a multi-megabyte archive is never buffered in the JVM's own heap
        // before it goes out.
        conn.setFixedLengthStreamingMode(file.length())
        file.inputStream().buffered().use { input -> conn.outputStream.use { input.copyTo(it) } }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        HttpReply(code, text)
    } catch (e: Exception) {
        HttpReply(0, "", e.message ?: "Network unavailable")
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
}

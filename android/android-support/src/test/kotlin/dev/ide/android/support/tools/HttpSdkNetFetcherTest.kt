package dev.ide.android.support.tools

import com.sun.net.httpserver.HttpServer
import dev.ide.testkit.withTempDir
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [HttpSdkNetFetcher] against a server that acts like dl.google.com: a request that allows gzip gets its
 * range cut out of a gzip copy of the file (another length, other bytes). ART's `HttpURLConnection` allows
 * gzip on its own, so the fetcher must ask for the identity encoding, and must refuse a range of a copy.
 */
class HttpSdkNetFetcherTest {

    private val file = Random(7).nextBytes(200_000)
    private val gzipped = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(file) } }.toByteArray()
    private val encodings = mutableListOf<String?>()
    /** When set, the server answers in gzip whatever the request asked for. */
    @Volatile private var forceGzip = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/f.zip") { ex ->
            val accept = ex.requestHeaders.getFirst("Accept-Encoding")
            synchronized(encodings) { encodings += accept }
            val gzip = forceGzip || accept?.contains("gzip") == true
            val body = if (gzip) gzipped else file
            if (gzip) ex.responseHeaders.add("Content-Encoding", "gzip")
            val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
            if (range == null) {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                val start = range[0].toInt()
                val end = range[1].takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                ex.sendResponseHeaders(206, (end - start + 1).toLong())
                ex.responseBody.use { it.write(body, start, end - start + 1) }
            }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/f.zip"

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun rangeRequestsAskForTheRawBytes() {
        val got = HttpSdkNetFetcher.readRange(url, 150_000, 150_099)
        assertContentEquals(file.copyOfRange(150_000, 150_100), got)
        assertEquals(listOf<String?>("identity"), encodings)
    }

    @Test
    fun aRangeOfAnEncodedCopyIsRefused() {
        forceGzip = true
        assertNull(HttpSdkNetFetcher.readRange(url, 1_000, 1_099))
    }

    @Test
    fun aResumedDownloadAppendsRawBytes() {
        withTempDir("httpfetch") { tmp ->
            val dest = tmp.resolve("f.zip")
            Files.write(dest, file.copyOfRange(0, 120_000))
            HttpSdkNetFetcher.download(url, dest) { _, _ -> }
            assertContentEquals(file, Files.readAllBytes(dest))
            assertEquals(listOf<String?>("identity"), encodings)
        }
    }
}

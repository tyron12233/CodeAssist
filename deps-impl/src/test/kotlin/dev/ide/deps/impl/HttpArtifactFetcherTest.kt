package dev.ide.deps.impl

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [HttpArtifactFetcher] — and in particular the streaming [ArtifactFetcher.fetchTo] path that jars/
 * aars download through. The resolver's own tests use a fixture fetcher that only implements `fetch`, so
 * this is the only coverage of the real socket→disk streaming, redirects, and 404 handling.
 */
class HttpArtifactFetcherTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    // A body bigger than the 64 KB stream buffer, so a truncating/early-stop copy would be caught.
    private val body = Random(7).nextBytes(200_000)

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ok") { ex ->
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.createContext("/forbidden") { ex -> ex.sendResponseHeaders(403, -1); ex.close() }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "$base/ok")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.start()
        base = "http://${server.address.hostString}:${server.address.port}"
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val fetcher = HttpArtifactFetcher()

    @Test
    fun fetchToStreamsFullBodyToDisk() {
        val dir = createTempDirectory("fetchto")
        try {
            val dest = dir.resolve("artifact.bin")
            assertTrue(fetcher.fetchTo("$base/ok", dest))
            assertContentEquals(body, Files.readAllBytes(dest), "streamed file must match the served bytes exactly")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fetchToReturnsFalseOn404() {
        val dir = createTempDirectory("fetchto404")
        try {
            val dest = dir.resolve("artifact.bin")
            assertFalse(fetcher.fetchTo("$base/missing", dest))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fetchToFollowsRedirect() {
        val dir = createTempDirectory("fetchtoredir")
        try {
            val dest = dir.resolve("artifact.bin")
            assertTrue(fetcher.fetchTo("$base/redirect", dest))
            assertContentEquals(body, Files.readAllBytes(dest))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fetchBuffersSameBytes() {
        assertContentEquals(body, fetcher.fetch("$base/ok"))
        assertNull(fetcher.fetch("$base/missing"))
    }

    @Test
    fun fetchThrowsOn403() {
        // 403 is a repository REFUSING the request (WAF/geo/rate-limit), NOT an absent artifact — it must
        // surface as a thrown failure so the resolver logs it + retries, never null (which reads as a 404 miss).
        assertFailsWith<java.io.IOException> { fetcher.fetch("$base/forbidden") }
    }

    @Test
    fun fetchToThrowsOn403() {
        val dir = createTempDirectory("fetchto403")
        try {
            assertFailsWith<java.io.IOException> { fetcher.fetchTo("$base/forbidden", dir.resolve("artifact.bin")) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

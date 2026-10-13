package dev.ide.store.impl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ide.store.StoreResult
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Public store files come from the R2 mirror first and from Supabase Storage when the mirror cannot serve them,
 * so a mirror that lags, fails or serves the wrong bytes costs egress and never an install.
 */
class StoreMediaMirrorTest {

    private lateinit var supabase: HttpServer
    private lateinit var mirror: HttpServer
    private val hits = CopyOnWriteArrayList<String>()
    private val mirrorFiles = mutableMapOf<String, ByteArray>()
    private val supabaseFiles = mutableMapOf<String, ByteArray>()
    private lateinit var dir: File

    private fun serve(name: String, files: Map<String, ByteArray>, prefix: String): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { ex ->
                val key = ex.requestURI.path.removePrefix(prefix)
                hits += "$name:$key"
                val body = files[key]
                if (body == null) respond(ex, if (name == "supabase") 400 else 404, "missing".toByteArray())
                else respond(ex, 200, body)
            }
            start()
        }

    private fun respond(ex: HttpExchange, code: Int, body: ByteArray) {
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    @BeforeTest
    fun start() {
        supabase = serve("supabase", supabaseFiles, "/storage/v1/object/public/")
        mirror = serve("mirror", mirrorFiles, "/")
        dir = kotlin.io.path.createTempDirectory("media-mirror").toFile()
    }

    @AfterTest
    fun stop() {
        supabase.stop(0)
        mirror.stop(0)
        dir.deleteRecursively()
    }

    private fun source(withMirror: Boolean = true) = SupabaseStoreSource(
        url = "http://127.0.0.1:${supabase.address.port}",
        apiKey = "publishable",
        mediaBaseUrl = if (withMirror) "http://127.0.0.1:${mirror.address.port}/" else "",
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun aPayloadTheMirrorHasNeverTouchesSupabase() {
        val zip = "zip-bytes".toByteArray()
        mirrorFiles["store-payloads/app/1.0.0.zip"] = zip
        supabaseFiles["store-payloads/app/1.0.0.zip"] = zip
        val out = File(dir, "a.zip")

        val r = source().downloadPayload("app/1.0.0.zip", sha(zip), zip.size.toLong(), out.path) {}

        assertIs<StoreResult.Ok<Unit>>(r)
        assertEquals(listOf("mirror:store-payloads/app/1.0.0.zip"), hits)
        assertTrue(out.readBytes().contentEquals(zip))
    }

    @Test
    fun aMissingOrWrongMirrorCopyFallsBackToSupabase() {
        val zip = "the-real-zip".toByteArray()
        supabaseFiles["store-payloads/app/1.0.0.zip"] = zip
        supabaseFiles["store-payloads/app/2.0.0.zip"] = zip
        mirrorFiles["store-payloads/app/2.0.0.zip"] = "stale or tampered".toByteArray()

        val missing = File(dir, "1.zip")
        assertIs<StoreResult.Ok<Unit>>(source().downloadPayload("app/1.0.0.zip", sha(zip), zip.size.toLong(), missing.path) {})
        assertTrue(missing.readBytes().contentEquals(zip), "a mirror miss is fetched from Supabase")

        val wrong = File(dir, "2.zip")
        assertIs<StoreResult.Ok<Unit>>(source().downloadPayload("app/2.0.0.zip", sha(zip), zip.size.toLong(), wrong.path) {})
        assertTrue(wrong.readBytes().contentEquals(zip), "bytes that fail the checksum are replaced by Supabase's")
    }

    @Test
    fun aFileNeitherHasFailsAndLeavesNothingBehind() {
        val out = File(dir, "gone.zip")
        val r = source().downloadPayload("app/9.zip", "00", 3, out.path) {}
        assertIs<StoreResult.Failed<Unit>>(r)
        assertTrue(!out.exists())
    }

    @Test
    fun imagesFallBackTooAndNoMirrorMeansSupabaseOnly() {
        val png = "png".toByteArray()
        supabaseFiles["store-media/app/1/icon.png"] = png
        assertIs<StoreResult.Ok<Unit>>(source().downloadMedia("app/1/icon.png", File(dir, "i.png").path))
        assertEquals(listOf("mirror:store-media/app/1/icon.png", "supabase:store-media/app/1/icon.png"), hits)

        hits.clear()
        assertIs<StoreResult.Ok<Unit>>(source(withMirror = false).downloadMedia("app/1/icon.png", File(dir, "j.png").path))
        assertEquals(listOf("supabase:store-media/app/1/icon.png"), hits)
    }
}

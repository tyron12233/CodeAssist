package dev.ide.store.impl

import com.sun.net.httpserver.HttpServer
import dev.ide.store.StoreResult
import dev.ide.store.TranslationKind
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The client half of the translate function, against a local server answering in the function's own shapes.
 * The success body is a real response from the deployed function, so the parse is checked against what the
 * server actually sends rather than against what this file assumes it sends.
 */
class SupabaseTranslateTest {

    private lateinit var server: HttpServer
    private var status = 200
    private var response = ""
    private var lastBody = ""
    private var lastAuth: String? = null

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/functions/v1/translate") { ex ->
            lastBody = ex.requestBody.readBytes().decodeToString()
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            val bytes = response.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun service() = SupabaseReviewService("http://127.0.0.1:${server.address.port}", "sb_publishable_test")

    @Test
    fun aReviewAndItsReplyComeBackByField() {
        response = """{"target":"es","fields":{"review":"por favor, ¿cómo uso el plugin?",""" +
            """"reply":"1. Descarga el proyecto\n2. Compílalo"}}"""
        val result = service().translate(TranslationKind.REVIEW, "codeassist-libgdx-55d831", "2afb1df6-38b3-4a39-800d-4dc44f2a3373", "es")
        assertIs<StoreResult.Ok<Map<String, String>>>(result)
        assertEquals("por favor, ¿cómo uso el plugin?", result.value["review"])
        assertEquals("1. Descarga el proyecto\n2. Compílalo", result.value["reply"])
        // It names what to translate; it never sends the text.
        assertEquals(
            """{"kind":"review","item":"codeassist-libgdx-55d831","author":"2afb1df6-38b3-4a39-800d-4dc44f2a3373","target":"es"}""",
            lastBody,
        )
        // Signed out works: the publishable key is the bearer.
        assertEquals("Bearer sb_publishable_test", lastAuth)
    }

    @Test
    fun aListingRequestCarriesNoAuthor() {
        response = """{"target":"en","fields":{"summary":"DrawDroid is where you can draw anything!"}}"""
        service().translate(TranslationKind.LISTING, "drawdroid-f02338", null, "en")
        assertEquals("""{"kind":"listing","item":"drawdroid-f02338","target":"en"}""", lastBody)
    }

    @Test
    fun busyIsUnavailableWithTheServersOwnWords() {
        status = 503
        response = """{"error":"Translation is busy right now. Try again in a moment."}"""
        val result = service().translate(TranslationKind.LISTING, "x-1", null, "es")
        assertIs<StoreResult.Unavailable<Map<String, String>>>(result)
        assertEquals("Translation is busy right now. Try again in a moment.", result.reason)
    }

    @Test
    fun aRefusalIsAFailureWithItsMessage() {
        status = 400
        response = """{"error":"That language is not offered."}"""
        val result = service().translate(TranslationKind.LISTING, "x-1", null, "tlh")
        assertIs<StoreResult.Failed<Map<String, String>>>(result)
        assertTrue(result.message.contains("not offered"))
    }
}

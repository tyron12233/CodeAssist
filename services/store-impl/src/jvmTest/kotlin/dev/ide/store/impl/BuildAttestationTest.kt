package dev.ide.store.impl

import com.sun.net.httpserver.HttpServer
import dev.ide.platform.JsonReader
import dev.ide.store.BuildAttestation
import dev.ide.store.StoreResult
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app sends the `attest` function, and what it reads back. The function and the app are released
 * separately, so the wire shape is pinned here: a field renamed on one side would otherwise go unnoticed,
 * because a failed attestation is silent by design.
 */
class BuildAttestationTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private var path: String? = null
    private var authorization: String? = null
    private var body: String? = null
    private var answer = 200 to """{"official":true}"""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            path = ex.requestURI.path
            authorization = ex.requestHeaders.getFirst("Authorization")
            body = ex.requestBody.readBytes().decodeToString()
            val bytes = answer.second.toByteArray()
            ex.sendResponseHeaders(answer.first, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://${server.address.hostString}:${server.address.port}"
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun service() = SupabaseAccountService(
        url = base,
        apiKey = "sb_publishable_test",
        redirectUrl = "codeassist://auth-callback",
    )

    @Test
    fun signedOutSendsTheTokenWithThePublishableKey() {
        val r = service().attestBuild(
            BuildAttestation(
                integrityToken = "tok\"en", error = null,
                appId = "com.tyron.code", signer = "1361a1abfbb2428b", appBuild = 134,
            )
        )
        assertEquals(StoreResult.Ok(true), r)
        assertEquals("/functions/v1/attest", path)
        assertEquals("Bearer sb_publishable_test", authorization)
        val sent = JsonReader.parse(body!!)
        assertEquals("tok\"en", JsonReader.str(sent, "token"))
        assertNull(JsonReader.obj(sent)!!["error"])
        assertEquals("com.tyron.code", JsonReader.str(sent, "app_id"))
        assertEquals("1361a1abfbb2428b", JsonReader.str(sent, "signer"))
        assertEquals(134, (JsonReader.obj(sent)!!["app_build"] as Number).toInt())
    }

    @Test
    fun aDeviceWithNoTokenReportsWhy() {
        answer = 200 to """{"official":false}"""
        val r = service().attestBuild(
            BuildAttestation(
                integrityToken = null, error = "integrity_-1",
                appId = "com.example.fork", signer = "00112233445566ff", appBuild = 7,
            )
        )
        assertEquals(StoreResult.Ok(false), r)
        val sent = JsonReader.parse(body!!)
        assertNull(JsonReader.obj(sent)!!["token"])
        assertEquals("integrity_-1", JsonReader.str(sent, "error"))
    }

    @Test
    fun aBackendThatIsDownIsUnavailableSoTheHostRetries() {
        answer = 503 to "attestation unavailable"
        val r = service().attestBuild(BuildAttestation(null, "x", "com.tyron.code", "", 1))
        assertTrue(r is StoreResult.Unavailable, "expected Unavailable, got $r")
    }

    /** The app and the function each derive the request hash from this text, so it must not drift. */
    @Test
    fun theRequestTextIsStable() {
        assertEquals("codeassist-attest:v1:anon", BuildAttestation.requestText(BuildAttestation.ANONYMOUS))
    }
}

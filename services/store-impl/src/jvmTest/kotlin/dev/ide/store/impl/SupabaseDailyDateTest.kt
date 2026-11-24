package dev.ide.store.impl

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app names today with an empty date, and Postgres refuses `''` as one: what reaches the server for "today"
 * must be null, or the day never loads and the solve screen spins forever.
 */
class SupabaseDailyDateTest {

    private lateinit var server: HttpServer
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/rest/v1/rpc/") { ex ->
            bodies += ex.requestBody.use { String(it.readBytes()) }
            val answer = "{}".toByteArray()
            ex.sendResponseHeaders(200, answer.size.toLong())
            ex.responseBody.use { it.write(answer) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun anEmptyDateIsAskedForAsToday() {
        val base = "http://${server.address.hostString}:${server.address.port}"
        val service = SupabaseDailyService(url = base, apiKey = "k", judgeUrl = "", accounts = SignedOut)
        service.day("")
        service.board("  ", 10, 0)
        service.day("2026-11-24")
        service.day(null)
        assertEquals(
            listOf(
                """{"p_date":null}""",
                """{"p_date":null,"p_limit":10,"p_offset":0}""",
                """{"p_date":"2026-11-24"}""",
                """{"p_date":null}""",
            ),
            bodies.toList(),
        )
    }

    private object SignedOut : AiCreditsSession {
        override fun authAvailable(): Boolean = true
        override fun hasStoredSession(): Boolean = false
        override fun bearer(): String? = null
        override fun reauthorize(stale: String?): String? = null
    }
}

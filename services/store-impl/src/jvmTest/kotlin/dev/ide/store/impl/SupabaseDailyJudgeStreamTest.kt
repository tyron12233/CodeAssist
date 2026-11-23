package dev.ide.store.impl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ide.store.DailyJudgeEvent
import dev.ide.store.DailyLanguage
import dev.ide.store.StoreResult
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Submitting while the judge reports its steps.
 *
 * The judge answers `Accept: application/x-ndjson` with one JSON object per line: each step as it starts and
 * finishes, then the verdict. These pin that the steps reach the caller while the request is still open
 * (otherwise the progress display is a spinner again), and that every way the stream can end is read
 * correctly: a verdict, a refusal sent after the 200, an old judge that answers with one object, and a
 * connection cut before the verdict.
 */
class SupabaseDailyJudgeStreamTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** What the fake judge does with the exchange; replaced per test. */
    @Volatile private var judge: (HttpExchange) -> Unit = {}

    /** The Accept header the last submission sent. */
    @Volatile private var accept: String? = null

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/auth/v1/user") { ex ->
            respond(ex, 200, """{"id":"u-1","email":"someone@example.com","user_metadata":{"user_name":"someone"}}""")
        }
        server.createContext("/api/judge") { ex ->
            accept = ex.requestHeaders.getFirst("Accept")
            ex.requestBody.use { it.readBytes() }
            judge(ex)
        }
        server.start()
        base = "http://${server.address.hostString}:${server.address.port}"
    }

    @AfterTest
    fun stop() = server.stop(0)

    /**
     * The point of the feature: the first step arrives while the judge is still working. The fake judge
     * will not send the verdict until the client has seen that step, so a client that buffers the whole
     * response never gets past it.
     */
    @Test
    fun stepsArriveWhileTheJudgeIsStillWorking() {
        val heard = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<DailyJudgeEvent>())
        judge = { ex ->
            ex.responseHeaders.add("Content-Type", "application/x-ndjson")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                out.write("""{"type":"step","step":"boot","state":"start"}""".toByteArray() + '\n'.code.toByte())
                out.flush()
                val live = heard.await(5, TimeUnit.SECONDS)
                out.write("""{"type":"step","step":"boot","state":"done","ms":2212,"detail":{"vcpus":2}}""".toByteArray() + '\n'.code.toByte())
                out.write("""{"type":"step","step":"measure","state":"progress","detail":{"n":4000,"ops":1284483,"counted":true}}""".toByteArray() + '\n'.code.toByte())
                val verdict = if (live) "accepted" else "buffered"
                out.write("""{"type":"result","outcome":{"verdict":"$verdict","testsPassed":13,"testsTotal":13}}""".toByteArray() + '\n'.code.toByte())
            }
        }

        val r = service().submit("2026-12-01", DailyLanguage.KOTLIN, "fun f() = 1") { e ->
            events += e
            heard.countDown()
        }

        assertTrue(accept.orEmpty().contains("application/x-ndjson"), "the client must ask for the steps; sent $accept")
        assertTrue(r is StoreResult.Ok, "got $r")
        assertEquals("accepted", r.value.verdict, "the first step must reach the caller before the stream ends")
        assertEquals(13, r.value.testsPassed)
        assertEquals(listOf("boot/start", "boot/done", "measure/progress"), events.map { it.step + "/" + it.state })
        assertEquals(2212L, events[1].ms)
        // Numbers and booleans arrive as plain text: 4000, not 4000.0.
        assertEquals(mapOf("n" to "4000", "ops" to "1284483", "counted" to "true"), events[2].detail)
    }

    /** The status line went out as 200 before the judge refused, so the refusal and its real status travel in the stream. */
    @Test
    fun aRefusalInsideTheStreamKeepsItsMessageAndStatus() {
        judge = { ex ->
            stream(
                ex,
                """{"type":"step","step":"claim","state":"start"}""",
                """{"type":"error","status":429,"error":"rate_gap","message":"Give the judge a moment before submitting again."}""",
            )
        }
        val r = service().submit("2026-12-01", DailyLanguage.JAVA, "class Solution {}") {}
        assertTrue(r is StoreResult.Failed, "got $r")
        assertEquals("Give the judge a moment before submitting again.", r.message)
        assertEquals(429, r.status)
    }

    /** A judge deployed before streaming ignores the Accept header and answers with the verdict alone. */
    @Test
    fun anOlderJudgeAnswersWithOneObject() {
        judge = { ex -> respond(ex, 200, """{"verdict":"wrong_answer","testsPassed":3,"testsTotal":13}""") }
        val events = mutableListOf<DailyJudgeEvent>()
        val r = service().submit("2026-12-01", DailyLanguage.KOTLIN, "fun f() = 1") { events += it }
        assertTrue(r is StoreResult.Ok, "got $r")
        assertEquals("wrong_answer", r.value.verdict)
        assertEquals(3, r.value.testsPassed)
        assertTrue(events.isEmpty())
    }

    /** A refusal before streaming began (sign-in, validation) is an ordinary error status, as it always was. */
    @Test
    fun aRefusalBeforeTheStreamIsAnOrdinaryStatus() {
        judge = { ex -> respond(ex, 401, """{"error":"auth","message":"That sign-in is no longer valid."}""") }
        val r = service().submit("2026-12-01", DailyLanguage.KOTLIN, "fun f() = 1") {}
        assertTrue(r is StoreResult.Failed, "got $r")
        assertEquals("That sign-in is no longer valid.", r.message)
        assertEquals(401, r.status)
    }

    /**
     * Cut before the verdict: the judgement may still have been recorded, so this is reported as a failure
     * to hear the answer, never as a wrong answer.
     */
    @Test
    fun aStreamCutBeforeTheVerdictSaysSo() {
        judge = { ex -> stream(ex, """{"type":"step","step":"compile","state":"start"}""") }
        val r = service().submit("2026-12-01", DailyLanguage.KOTLIN, "fun f() = 1") {}
        assertTrue(r is StoreResult.Failed, "got $r")
        assertTrue(r.message.contains("closed before the verdict"), r.message)
    }

    private fun service(): SupabaseDailyService {
        val accounts = SupabaseAccountService(
            url = base,
            apiKey = KEY,
            redirectUrl = "codeassist://auth-callback",
            tokens = StoreTokenStore.inMemory(),
        )
        val adopted = accounts.complete(
            "codeassist://auth-callback#access_token=token-1&refresh_token=refresh-1&expires_in=3600",
        )
        assertTrue(adopted is StoreResult.Ok, "the fixture must start signed in, got $adopted")
        return SupabaseDailyService(url = base, apiKey = KEY, judgeUrl = base, accounts = accounts)
    }

    private fun stream(ex: HttpExchange, vararg lines: String) {
        ex.responseHeaders.add("Content-Type", "application/x-ndjson")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out -> for (line in lines) out.write((line + "\n").toByteArray()) }
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val KEY = "sb_publishable_test"
    }
}

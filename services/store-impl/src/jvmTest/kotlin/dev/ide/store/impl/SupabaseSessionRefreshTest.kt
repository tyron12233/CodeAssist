package dev.ide.store.impl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ide.store.StoreResult
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keeping a session alive while the app stays open.
 *
 * Supabase issues an access token good for an hour. The client used to hold the first one it was given
 * for the life of the process, so an app left open answered every authenticated call with a 401 and the
 * screens behind sign-in reported the account as unreachable until the app was restarted. These tests
 * pin both halves of the fix: the token is traded in before it expires, and a call refused anyway is
 * retried once with a fresh one.
 */
class SupabaseSessionRefreshTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** How many times the refresh endpoint was asked for a new session. */
    private val refreshes = AtomicInteger()

    /** The token the fake backend currently accepts. Anything else is answered 401. */
    @Volatile private var accepted: String = ISSUED

    /** What the refresh endpoint answers with; replaced by the tests that exercise a refusal. */
    @Volatile private var refreshResponse: Pair<Int, String> = 200 to ""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/auth/v1/token") { ex ->
            refreshes.incrementAndGet()
            val (code, body) = refreshResponse
            val text = if (code == 200 && body.isEmpty()) session(ISSUED, "refreshed-token") else body
            respond(ex, code, text)
        }
        server.createContext("/auth/v1/user") { ex ->
            respond(ex, 200, """{"id":"u-1","email":"someone@example.com","user_metadata":{"user_name":"someone"}}""")
        }
        server.createContext("/rest/v1/rpc/store_my_profile") { ex ->
            if (ex.requestHeaders.getFirst("Authorization") == "Bearer $accepted") {
                respond(ex, 200, """{"handle":"someone","displayName":"Someone"}""")
            } else {
                respond(ex, 401, """{"message":"JWT expired","code":"PGRST301"}""")
            }
        }
        server.start()
        base = "http://${server.address.hostString}:${server.address.port}"
    }

    @AfterTest
    fun stop() = server.stop(0)

    /**
     * The bug itself: an hour into a session the token in hand is spent, and the next call must not go out
     * carrying it.
     */
    @Test
    fun aSpentAccessTokenIsTradedInBeforeTheNextCall() {
        val accounts = signedIn(accessToken = jwt(expiresInSeconds = -60))
        assertEquals(ISSUED, accounts.bearer(), "a spent token must be exchanged, not reused")
        assertEquals(1, refreshes.get())
    }

    /** A token with time left on it is not exchanged, so a busy screen is not a stream of refreshes. */
    @Test
    fun aLiveAccessTokenIsUsedAsItIs() {
        val live = jwt(expiresInSeconds = 3600)
        val accounts = signedIn(accessToken = live)
        assertEquals(live, accounts.bearer())
        assertEquals(live, accounts.bearer())
        assertEquals(0, refreshes.get(), "a token good for another hour is not worth exchanging")
    }

    /** The last minute counts as spent: the call still has to travel, and the two clocks need not agree. */
    @Test
    fun aTokenInsideTheSkewWindowCountsAsSpent() {
        val accounts = signedIn(accessToken = jwt(expiresInSeconds = 30))
        assertEquals(ISSUED, accounts.bearer())
        assertEquals(1, refreshes.get())
    }

    /**
     * The backstop: a token this client believes is live but the server refuses. A revoked session and a
     * client clock an hour behind both look like this.
     */
    @Test
    fun aCallRefusedWithA401IsRetriedWithAFreshToken() {
        val accounts = signedIn(accessToken = jwt(expiresInSeconds = 3600))
        val submissions = SupabaseSubmissionService(base, KEY, accounts)

        val result = submissions.myProfile()

        assertTrue(result is StoreResult.Ok, "expected the retry to succeed, got $result")
        assertEquals("someone", result.value?.handle)
        assertEquals(1, refreshes.get(), "one refusal is worth exactly one refresh")
    }

    /** A refusal that survives the refresh is reported, not retried around forever. */
    @Test
    fun aCallStillRefusedAfterARefreshIsReported() {
        accepted = "nothing-matches-this"
        val accounts = signedIn(accessToken = jwt(expiresInSeconds = 3600))

        val result = SupabaseSubmissionService(base, KEY, accounts).myProfile()

        assertTrue(result is StoreResult.Failed, "expected a failure, got $result")
        assertEquals(401, result.status)
        assertEquals(1, refreshes.get(), "the retry happens once, not in a loop")
    }

    /**
     * Only a refusal of the refresh token itself is permanent.
     *
     * Dropping it for anything else signs the user out with no way back but the browser, and a gateway
     * having a bad minute is not a reason to make someone authenticate with GitHub again.
     */
    @Test
    fun aTransientRefreshFailureKeepsTheStoredToken() {
        refreshResponse = 400 to """{"error":"validation_failed","error_description":"missing field"}"""
        val store = StoreTokenStore.inMemory()
        val accounts = signedIn(accessToken = jwt(expiresInSeconds = -60), store = store)

        accounts.bearer()

        assertEquals("refresh-1", store.read(), "a session that may still work must not be thrown away")
    }

    @Test
    fun aRefusedRefreshTokenIsDropped() {
        refreshResponse = 400 to
            """{"error":"invalid_grant","error_description":"Invalid Refresh Token: Already Used"}"""
        val store = StoreTokenStore.inMemory()
        val accounts = signedIn(accessToken = jwt(expiresInSeconds = -60), store = store)

        assertNull(accounts.bearer(), "a session with no credential left is no session")
        assertNull(store.read(), "a refused token is dead and must not be retried on every launch")
    }

    /**
     * Refresh tokens rotate, so a spent session found by several calls at once must be exchanged once.
     *
     * The loser of that race would be handed "Already Used", which reads as a dead session and would sign
     * the user out over timing alone.
     */
    @Test
    fun concurrentCallersShareOneRefresh() {
        val accounts = signedIn(accessToken = jwt(expiresInSeconds = -60))
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String?>())
        repeat(8) {
            Thread {
                start.await()
                seen.add(accounts.bearer())
                done.countDown()
            }.apply { isDaemon = true }.start()
        }
        start.countDown()
        done.await()

        assertEquals(setOf<String?>(ISSUED), seen, "every caller gets the same fresh token")
        assertEquals(1, refreshes.get(), "the refresh token is spent once, not eight times")
    }

    /** The expiry the client schedules against comes out of the token, on every path one arrives by. */
    @Test
    fun theExpiryIsReadFromTheTokensOwnClaim() {
        val at = SupabaseAccountService.jwtExpiry(jwt(expiresInSeconds = 600))
        assertNotNull(at)
        val remaining = at - System.currentTimeMillis()
        assertTrue(remaining in 500_000..600_000, "expected roughly ten minutes, got ${remaining}ms")
    }

    /** An opaque token is not a reason to refuse a call; the 401 retry is what covers it. */
    @Test
    fun aTokenThatIsNotAJwtHasNoReadableExpiry() {
        assertNull(SupabaseAccountService.jwtExpiry("opaque-token"))
        assertNull(SupabaseAccountService.jwtExpiry(""))
        assertNull(SupabaseAccountService.jwtExpiry("head.not-base64!.sig"))
        assertNull(SupabaseAccountService.jwtExpiry("head.${b64("""{"sub":"u-1"}""")}.sig"))
    }

    // ---- fixtures ----

    /**
     * A service holding [accessToken], adopted the way a sign-in redirect delivers one.
     *
     * The implicit flow is used because it puts the token in the URL, which is what lets a test decide how
     * much life is left on it.
     */
    private fun signedIn(
        accessToken: String,
        store: StoreTokenStore = StoreTokenStore.inMemory(),
    ): SupabaseAccountService {
        val accounts = SupabaseAccountService(
            url = base,
            apiKey = KEY,
            redirectUrl = "codeassist://auth-callback",
            tokens = store,
        )
        val adopted = accounts.complete(
            "codeassist://auth-callback#access_token=$accessToken&refresh_token=refresh-1&expires_in=3600",
        )
        assertTrue(adopted is StoreResult.Ok, "the fixture must start signed in, got $adopted")
        return accounts
    }

    /** A session document shaped like GoTrue's. */
    private fun session(accessToken: String, refreshToken: String): String =
        """{"access_token":"$accessToken","refresh_token":"$refreshToken","expires_in":3600,""" +
            """"user":{"id":"u-1","email":"someone@example.com"}}"""

    /** A token whose `exp` claim is [expiresInSeconds] from now. Unsigned: nothing here verifies one. */
    private fun jwt(expiresInSeconds: Long): String {
        val exp = System.currentTimeMillis() / 1000 + expiresInSeconds
        return "${b64("""{"alg":"HS256","typ":"JWT"}""")}.${b64("""{"sub":"u-1","exp":$exp}""")}.signature"
    }

    private fun b64(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.requestBody.use { it.readBytes() }
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val KEY = "sb_publishable_test"

        /** What the refresh endpoint issues. Distinct from the token a test signs in with. */
        const val ISSUED = "issued-2"
    }
}

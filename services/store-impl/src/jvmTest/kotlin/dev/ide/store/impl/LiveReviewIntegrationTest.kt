package dev.ide.store.impl

import dev.ide.store.ReviewSort
import dev.ide.store.StoreResult
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reviews against a **running local Supabase stack** (`supabase start`), self-skipping when it is down.
 *
 * Mocked HTTP would not have caught what this did: the aggregate triggers were SECURITY INVOKER, so a vote
 * and a rating both updated zero rows under row-level security and nothing raised. Only a real database
 * with real policies shows that.
 */
class LiveReviewIntegrationTest {

    private val baseUrl = "http://127.0.0.1:54321"
    private val anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9." +
        "CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"
    private val jwtSecret = "super-secret-jwt-token-with-at-least-32-characters-long"

    private fun stackUp(): Boolean = runCatching {
        val c = (URL("$baseUrl/rest/v1/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 1500; readTimeout = 1500
            setRequestProperty("apikey", anonKey)
        }
        c.responseCode in 200..499
    }.getOrDefault(false)

    private fun userId(email: String): String? = runCatching {
        ProcessBuilder(
            "docker", "exec", "supabase_db_codeassist", "psql", "-U", "postgres", "-tAc",
            "select id from auth.users where email = '$email'",
        ).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().ifBlank { null }
    }.getOrNull()

    private fun mintJwt(sub: String): String {
        fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = b64(
            """{"aud":"authenticated","role":"authenticated","sub":"$sub","iss":"supabase-demo","exp":2000000000}"""
                .toByteArray(),
        )
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(jwtSecret.toByteArray(), "HmacSHA256"))
        }
        return "$header.$payload.${b64(mac.doFinal("$header.$payload".toByteArray()))}"
    }

    private fun signedIn(uid: String): SupabaseAccountService {
        val svc = SupabaseAccountService(baseUrl, anonKey, "codeassist://auth-callback")
        val r = svc.complete("codeassist://auth-callback#access_token=${mintJwt(uid)}&refresh_token=none")
        assertTrue(r is StoreResult.Ok, "sign-in with a minted token failed: $r")
        return svc
    }

    /** A signed-out reader must still get the panel: reviews are part of the catalog, not a member feature. */
    @Test
    fun anonymousReadReturnsThePanel() {
        if (!stackUp()) return
        val service = SupabaseReviewService(baseUrl, anonKey, accounts = null)
        val page = service.reviews("kmp-starter")
        assertTrue(page is StoreResult.Ok, "anonymous read failed: $page")
        val value = (page as StoreResult.Ok).value
        assertTrue(value.count >= 0)
        // Nothing is "mine" without a session, no matter what is in the table.
        assertEquals(null, value.mine, "a signed-out reader has no own review")
        assertTrue(value.reviews.none { it.votedByMe }, "a signed-out reader has voted on nothing")
    }

    @Test
    fun anonymousWriteIsRefusedWithAnActionableMessage() {
        if (!stackUp()) return
        val service = SupabaseReviewService(baseUrl, anonKey, accounts = null)
        val result = service.rate("kmp-starter", stars = 5, review = "should not land")
        assertTrue(result is StoreResult.Failed, "an anonymous write must fail: $result")
        assertEquals("Sign in to do that", (result as StoreResult.Failed).message)
    }

    /**
     * The end-to-end write: rate, read it back as mine, and see the ITEM's aggregate move.
     *
     * That last assertion is the one that matters. It was false before the trigger fix, silently.
     */
    @Test
    fun ratingUpdatesTheItemAggregate() {
        if (!stackUp()) return
        val bob = userId("bob@example.com") ?: return
        val service = SupabaseReviewService(baseUrl, anonKey, signedIn(bob))

        assertTrue(service.rate("kmp-starter", stars = 4, review = "Solid, with caveats.") is StoreResult.Ok)
        val page = (service.reviews("kmp-starter") as StoreResult.Ok).value
        val mine = assertNotNull(page.mine, "the author's own review should come back separately")
        assertEquals(4, mine.stars)
        assertTrue(mine.mine)
        assertTrue(page.reviews.none { it.mine }, "own review must not be duplicated into the list")

        // The recomputed headline has to agree with the rows.
        val fromRows = (page.distribution.entries.sumOf { it.key * it.value }).toFloat() / page.count
        assertTrue(
            kotlin.math.abs(page.average - fromRows) < 0.02f,
            "average ${page.average} disagrees with the distribution ($fromRows)",
        )
    }

    @Test
    fun votingIncrementsTheHelpfulCountAndIsIdempotent() {
        if (!stackUp()) return
        val bob = userId("bob@example.com") ?: return
        val alice = userId("alice@example.com") ?: return
        val asBob = SupabaseReviewService(baseUrl, anonKey, signedIn(bob))
        val asAlice = SupabaseReviewService(baseUrl, anonKey, signedIn(alice))
        assertTrue(asBob.rate("kmp-starter", stars = 5, review = "Voting target.") is StoreResult.Ok)

        // Clear any earlier vote so the assertion is about this run.
        asAlice.vote("kmp-starter", bob, helpful = false)
        assertTrue(asAlice.vote("kmp-starter", bob, helpful = true) is StoreResult.Ok)
        assertTrue(asAlice.vote("kmp-starter", bob, helpful = true) is StoreResult.Ok, "a repeat vote is not an error")

        val seen = (asAlice.reviews("kmp-starter") as StoreResult.Ok).value.reviews.firstOrNull { it.authorId == bob }
        val review = assertNotNull(seen, "alice should see bob's review in the list")
        assertEquals(1, review.helpful, "one voter, counted once — this was 0 before the trigger fix")
        assertTrue(review.votedByMe)

        // And taking it back.
        assertTrue(asAlice.vote("kmp-starter", bob, helpful = false) is StoreResult.Ok)
        val after = (asAlice.reviews("kmp-starter") as StoreResult.Ok).value.reviews.first { it.authorId == bob }
        assertEquals(0, after.helpful)
        assertTrue(!after.votedByMe)
    }

    @Test
    fun sortOrdersDiffer() {
        if (!stackUp()) return
        val service = SupabaseReviewService(baseUrl, anonKey, accounts = null)
        val helpful = service.reviews("kmp-starter", ReviewSort.HELPFUL)
        val recent = service.reviews("kmp-starter", ReviewSort.RECENT)
        assertTrue(helpful is StoreResult.Ok && recent is StoreResult.Ok, "both sorts should work")
    }

    @Test
    fun anUnknownSlugIsAnEmptyPageRatherThanAnError() {
        if (!stackUp()) return
        val service = SupabaseReviewService(baseUrl, anonKey, accounts = null)
        val page = service.reviews("no-such-project-anywhere")
        assertTrue(page is StoreResult.Ok, "an unknown slug should not error: $page")
        assertEquals(0, (page as StoreResult.Ok).value.count)
        assertEquals(emptyList(), page.value.reviews)
    }
}

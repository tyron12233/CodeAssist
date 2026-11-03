package dev.ide.store.impl

import dev.ide.store.LearnStepRecord
import dev.ide.store.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real Learn client against a real database: sync, discussions, shared solutions and moderation, as
 * three signed-in users. Opt-in, because it needs a backend with the Learn migrations applied (see
 * supabase/tests/learn/README.md for the throwaway Postgres + PostgREST setup):
 *
 *   LEARN_LIVE_URL=http://127.0.0.1:33000 LEARN_LIVE_KEY=<anon jwt> LEARN_LIVE_SECRET=<jwt secret> \
 *     ./gradlew :store-impl:jvmTest --tests '*LiveLearnCommunityIntegrationTest'
 *
 * Users 1111…, 2222… and 3333… must exist (with publisher rows); 3333… must be a store admin.
 */
class LiveLearnCommunityIntegrationTest {

    private val url = System.getenv("LEARN_LIVE_URL").orEmpty()
    private val anonKey = System.getenv("LEARN_LIVE_KEY").orEmpty()
    private val secret = System.getenv("LEARN_LIVE_SECRET").orEmpty()

    private val asker = "11111111-1111-1111-1111-111111111111"
    private val helper = "22222222-2222-2222-2222-222222222222"
    private val moderator = "33333333-3333-3333-3333-333333333333"

    private fun mintJwt(sub: String): String {
        fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = b64("""{"role":"authenticated","sub":"$sub","exp":2000000000}""".toByteArray())
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        }
        return "$header.$payload.${b64(mac.doFinal("$header.$payload".toByteArray()))}"
    }

    private fun client(uid: String?): SupabaseLearnService {
        val accounts = SupabaseAccountService(url, anonKey, "codeassist://auth-callback")
        if (uid != null) {
            val r = accounts.complete("codeassist://auth-callback#access_token=${mintJwt(uid)}&refresh_token=none")
            assertTrue(r is StoreResult.Ok, "sign-in with a minted token failed: $r")
        }
        return SupabaseLearnService(url, anonKey, accounts)
    }

    private fun <T> StoreResult<T>.ok(what: String): T {
        assertTrue(this is StoreResult.Ok, "$what: $this")
        return (this as StoreResult.Ok).value
    }

    @Test
    fun theWholeCommunityFlow() {
        if (url.isBlank() || anonKey.isBlank() || secret.isBlank()) {
            println("LiveLearnCommunityIntegrationTest skipped: set LEARN_LIVE_URL, LEARN_LIVE_KEY and LEARN_LIVE_SECRET")
            return
        }
        val a = client(asker)
        val h = client(helper)
        val m = client(moderator)
        val anon = client(null)
        val lesson = "kt-basics-capstone"
        val step = "kt-cap-i"

        // Sync returns the account state, including the new shareSolutions flag.
        val state = a.syncProgress(listOf(LearnStepRecord(lesson, "kt-cap-c", System.currentTimeMillis())), emptyList(), "UTC").ok("sync")
        assertTrue(state.shareSolutions)
        assertTrue((lesson to "kt-cap-c") in state.steps)

        // Ask, list, count; anonymous readers see it; anonymous writes are refused before reaching the server.
        val threadId = a.createThread(lesson, step, "  Stuck on the score report  ", "It prints nothing", "fun main() {}", "kotlin", "Test 1 of 4 failed").ok("ask")
        val list = anon.threads(lesson, step).ok("list")
        assertEquals(listOf(threadId), list.map { it.id })
        assertEquals("Stuck on the score report", list.single().title)
        assertEquals("Ana", list.single().author.name)
        assertTrue(list.single().hasCode)
        assertEquals(1, anon.threadCount(lesson).ok("count"))
        assertTrue(anon.createThread(lesson, step, "nope", "", null, null, null) is StoreResult.Failed)

        // A helper answers; the asker votes it up and accepts it; the helper cannot vote their own answer.
        val postId = h.reply(threadId, "Read each score with readln() inside the loop", "for (i in 1..count) {}").ok("reply")
        assertEquals(1, a.vote(postId, true).ok("vote"))
        val ownVote = h.vote(postId, true)
        assertTrue(ownVote is StoreResult.Failed, "voting your own answer should be refused: $ownVote")
        a.accept(threadId, postId).ok("accept")
        val detail = assertNotNull(anon.thread(threadId).ok("get"))
        assertEquals("fun main() {}", detail.thread.code)
        assertEquals("Test 1 of 4 failed", detail.thread.errorText)
        assertEquals(postId, detail.thread.acceptedPost)
        assertEquals(1, detail.posts.single().score)
        assertTrue(detail.posts.single().accepted)
        assertFalse(detail.canModerate)
        assertTrue(assertNotNull(a.thread(threadId).ok("get mine")).thread.mine)

        // Solutions: locked until passed; a pass shares and unlocks; likes; opting out hides yours.
        assertFalse(h.solutions(lesson, step).ok("locked").unlocked)
        assertTrue(a.submitSolution(lesson, step, "fun main() { println(1) }", "kotlin").ok("submit a"))
        assertTrue(h.submitSolution(lesson, step, "fun main() { println(2) }", "kotlin").ok("submit h"))
        val page = h.solutions(lesson, step).ok("unlocked")
        assertTrue(page.unlocked)
        assertEquals(2, page.solutions.size)
        val asksSolution = page.solutions.first { !it.mine }
        assertEquals(1, h.likeSolution(asksSolution.id, true).ok("like"))
        assertEquals(asksSolution.id, h.solutions(lesson, step, "top").ok("top").solutions.first().id)
        a.setShareSolutions(false).ok("opt out")
        assertFalse(a.syncProgress(emptyList(), emptyList(), "UTC").ok("sync after opt out").shareSolutions)
        assertEquals(1, h.solutions(lesson, step).ok("after opt out").solutions.size)
        assertFalse(a.submitSolution(lesson, step, "x", "kotlin").ok("submit opted out"))

        // Reports reach the moderator queue; a non-moderator cannot read it; hiding removes the thread.
        h.report("thread", threadId, "spam", "test").ok("report")
        assertTrue(h.reportQueue() is StoreResult.Failed)
        val queue = m.reportQueue().ok("queue")
        val item = queue.single { it.targetId == threadId }
        assertEquals("thread", item.kind)
        assertEquals(1, item.reports)
        m.resolveReport(item.id, hide = true).ok("hide")
        assertEquals(null, anon.thread(threadId).ok("hidden"))
        assertEquals(0, anon.threadCount(lesson).ok("count after hide"))

        // Deleting your own content.
        val second = a.createThread(lesson, null, "Another question", "", null, null, null).ok("ask 2")
        a.deleteMine("thread", second).ok("delete")
        assertTrue(anon.threads(lesson).ok("after delete").none { it.id == second })
    }
}

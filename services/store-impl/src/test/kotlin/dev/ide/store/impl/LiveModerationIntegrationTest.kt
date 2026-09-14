package dev.ide.store.impl

import dev.ide.store.StoreResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Moderation, end to end against a **running local Supabase stack** (`supabase start`).
 *
 * Self-skipping like [LiveSubmissionIntegrationTest]: with no stack reachable every test returns early, so
 * this stays green in CI and on a machine with Docker off.
 *
 * A mock cannot stand in for what matters here. Approving is two systems agreeing — an object copied
 * between Storage buckets, and a row flipped by a definer function that reads the path the copy produced —
 * and the failures worth catching are exactly the ones that only appear when both are real: an approval
 * whose payload is not actually served, a public key that still carries the submitter's uuid, a
 * non-moderator getting further than they should.
 *
 * The local stack's keys and JWT secret are fixed, well-known development values printed by
 * `supabase start` and identical for every project, so hard-coding them here reveals nothing.
 */
class LiveModerationIntegrationTest {

    private val baseUrl = "http://127.0.0.1:54321"
    private val anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9." +
        "CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"
    private val jwtSecret = "super-secret-jwt-token-with-at-least-32-characters-long"

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temps.forEach { it.deleteRecursively() }
        psql("delete from public.store_items where slug like 'modtest-%';")
    }

    private fun stackUp(): Boolean = runCatching {
        val c = (URL("$baseUrl/rest/v1/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1500
            readTimeout = 1500
            setRequestProperty("apikey", anonKey)
        }
        c.responseCode in 200..499
    }.getOrDefault(false)

    private fun psql(sql: String): String = runCatching {
        ProcessBuilder("docker", "exec", "supabase_db_codeassist", "psql", "-U", "postgres", "-tAqc", sql)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")

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

    private fun accountsFor(uid: String): SupabaseAccountService {
        val svc = SupabaseAccountService(baseUrl, anonKey, "codeassist://auth-callback")
        val r = svc.complete("codeassist://auth-callback#access_token=${mintJwt(uid)}&refresh_token=none")
        assertTrue(r is StoreResult.Ok, "sign-in with a minted token failed: $r")
        return svc
    }

    private fun userId(email: String): String? =
        psql("select id from auth.users where email = '$email'").ifBlank { null }

    /**
     * A moderator account, created if the local stack has none.
     *
     * The token columns are set to `''` for the same reason `seed.sql` does it: GoTrue scans them into Go
     * strings and cannot read a NULL, so a row inserted without them makes `GET /auth/v1/user` answer 500 —
     * and adopting a session reads /user, so signing in as this account would fail with "the account has
     * no id" and nothing would say why.
     */
    private fun moderator(): String {
        psql(
            "insert into auth.users (id, instance_id, aud, role, email, encrypted_password, created_at, " +
                "updated_at, confirmation_token, recovery_token, email_change_token_new, " +
                "email_change_token_current, email_change, phone_change, phone_change_token) " +
                "select gen_random_uuid(), '00000000-0000-0000-0000-000000000000', 'authenticated', " +
                "'authenticated', 'mod@example.test', '', now(), now(), '', '', '', '', '', '', '' " +
                "where not exists (select 1 from auth.users where email = 'mod@example.test');",
        )
        val id = assertNotNull(userId("mod@example.test"))
        // Repaired rather than only set on insert: a stack where this account already exists from an older
        // run of this test still has the NULLs, and `where not exists` above would skip right past them.
        psql(
            "update auth.users set confirmation_token = coalesce(confirmation_token, ''), " +
                "recovery_token = coalesce(recovery_token, ''), " +
                "email_change_token_new = coalesce(email_change_token_new, ''), " +
                "email_change_token_current = coalesce(email_change_token_current, ''), " +
                "email_change = coalesce(email_change, ''), " +
                "phone_change = coalesce(phone_change, ''), " +
                "phone_change_token = coalesce(phone_change_token, '') where id = '$id';",
        )
        psql("insert into public.store_admins (user_id) values ('$id') on conflict do nothing;")
        return id
    }

    /**
     * A pending submission with a REAL archive in the private bucket.
     *
     * The upload goes through [SupabaseSubmissionService], so the object is where a submitter's would be
     * and under the prefix the storage policy allows — which is the thing the approval copy has to be able
     * to read across an account boundary.
     */
    private fun seedSubmission(slug: String, publisher: String): String {
        val category = psql("select slug from public.store_categories order by sort_order limit 1")
        val root = kotlin.io.path.createTempDirectory("ca-modtest-").toFile().also { temps += it }
        File(root, "settings.gradle.kts").writeText("include(\":app\")")
        File(root, "app/src/main/kotlin").mkdirs()
        File(root, "app/src/main/kotlin/Main.kt").writeText("fun main() = println(\"hi\")")

        val accounts = accountsFor(publisher)
        val submissions = SupabaseSubmissionService(baseUrl, anonKey, accounts)
        val packed = submissions.pack(root.absolutePath)
        assertTrue(packed is StoreResult.Ok, "packaging failed: $packed")

        psql(
            "insert into public.store_items (slug, kind, title, summary, description, category, publisher_id, status) " +
                "values ('$slug', 'community', 'Mod Test', 'A summary', 'A description', '$category', " +
                "'$publisher', 'pending');",
        )
        val result = submissions.submit(
            dev.ide.store.StoreSubmissionRequest(
                itemSlug = slug,
                title = "Mod Test",
                summary = "A summary",
                description = "A description",
                category = category,
                version = "1.0.0",
            ),
            packed.value,
        )
        assertTrue(result is StoreResult.Ok, "seeding the submission failed: $result")
        return psql(
            "select v.id from public.store_item_versions v join public.store_items i on i.id = v.item_id " +
                "where i.slug = '$slug' and v.version = '1.0.0';",
        )
    }

    private fun moderationFor(uid: String) = SupabaseModerationService(baseUrl, anonKey, accountsFor(uid))

    @Test
    fun approvingCopiesThePayloadPublicAndPublishesTheListing() {
        if (!stackUp()) return
        val publisher = userId("pub-a@example.test") ?: return
        val slug = "modtest-approve"
        psql("delete from public.store_items where slug = '$slug';")
        seedSubmission(slug, publisher)

        val moderation = moderationFor(moderator())
        val queue = moderation.queue()
        assertTrue(queue is StoreResult.Ok, "the queue could not be read: $queue")
        val submission = assertNotNull(
            queue.value.pending.firstOrNull { it.listing.slug == slug },
            "the seeded submission is not in the queue",
        )
        // Still under the submitter's uuid, in the private bucket, which is precisely what must not be
        // published as-is.
        assertTrue(submission.storagePath.startsWith(publisher), submission.storagePath)

        val decision = moderation.approve(submission)
        assertTrue(decision is StoreResult.Ok, "approve failed: $decision")

        // The row now points at a clean, version-scoped, uuid-free key...
        assertEquals("$slug/1.0.0.zip", psql(
            "select v.storage_path from public.store_item_versions v " +
                "join public.store_items i on i.id = v.item_id where i.slug = '$slug';",
        ))
        // ...the listing is live and pointed at that version...
        assertEquals("approved|true", psql(
            "select i.status || '|' || (i.latest_version_id = v.id)::text " +
                "from public.store_items i join public.store_item_versions v on v.item_id = i.id " +
                "where i.slug = '$slug';",
        ))
        // ...the reviewer is recorded, which is the whole point of moderating as a person rather than with
        // a master key...
        assertEquals("true", psql(
            "select (v.reviewer_id is not null and v.reviewed_at is not null)::text " +
                "from public.store_item_versions v join public.store_items i on i.id = v.item_id " +
                "where i.slug = '$slug';",
        ))
        // ...and the object is actually being served anonymously, which is what an install does.
        val served = (URL("$baseUrl/storage/v1/object/public/store-payloads/$slug/1.0.0.zip")
            .openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        assertEquals(200, served.responseCode)
        served.inputStream.use { it.readBytes() }
    }

    @Test
    fun rejectingRecordsTheNoteAndLeavesTheListingUnpublished() {
        if (!stackUp()) return
        val publisher = userId("pub-a@example.test") ?: return
        val slug = "modtest-reject"
        psql("delete from public.store_items where slug = '$slug';")
        seedSubmission(slug, publisher)

        val moderation = moderationFor(moderator())
        val submission = assertNotNull(
            (moderation.queue() as StoreResult.Ok).value.pending.firstOrNull { it.listing.slug == slug },
        )

        assertTrue(moderation.reject(submission, "Add a README first.") is StoreResult.Ok)

        assertEquals("rejected|Add a README first.|pending", psql(
            "select v.status || '|' || v.review_note || '|' || i.status " +
                "from public.store_item_versions v join public.store_items i on i.id = v.item_id " +
                "where i.slug = '$slug';",
        ))
    }

    @Test
    fun aRejectionWithNoNoteIsRefusedBeforeItLeavesTheDevice() {
        if (!stackUp()) return
        val publisher = userId("pub-a@example.test") ?: return
        val slug = "modtest-blank-note"
        psql("delete from public.store_items where slug = '$slug';")
        seedSubmission(slug, publisher)

        val moderation = moderationFor(moderator())
        val submission = assertNotNull(
            (moderation.queue() as StoreResult.Ok).value.pending.firstOrNull { it.listing.slug == slug },
        )

        val refused = moderation.reject(submission, "   ")

        assertTrue(refused is StoreResult.Failed, "a blank note should be refused: $refused")
        // Still pending: a refusal on the client must not have already sent the decision.
        assertEquals("pending", psql(
            "select v.status from public.store_item_versions v join public.store_items i on i.id = v.item_id " +
                "where i.slug = '$slug';",
        ))
    }

    @Test
    fun aSecondDecisionOnTheSameVersionIsRefusedWithASentence() {
        if (!stackUp()) return
        val publisher = userId("pub-a@example.test") ?: return
        val slug = "modtest-twice"
        psql("delete from public.store_items where slug = '$slug';")
        seedSubmission(slug, publisher)

        val moderation = moderationFor(moderator())
        val submission = assertNotNull(
            (moderation.queue() as StoreResult.Ok).value.pending.firstOrNull { it.listing.slug == slug },
        )
        assertTrue(moderation.approve(submission) is StoreResult.Ok)

        // Two moderators working the same queue is the ordinary case this covers, and the second one needs
        // to read what happened rather than a constraint or a silent success.
        val again = moderation.reject(submission, "changed my mind")

        assertTrue(again is StoreResult.Failed, "a decided version should refuse a second decision: $again")
        assertTrue("already approved" in again.message, again.message)
    }

    @Test
    fun aSignedInNonModeratorCanReachNoneOfIt() {
        if (!stackUp()) return
        val publisher = userId("pub-a@example.test") ?: return
        // The publisher of the content, which is the most privileged non-moderator there is.
        val moderation = moderationFor(publisher)

        assertFalse(moderation.amIModerator())

        val queue = moderation.queue()
        assertTrue(queue is StoreResult.Failed, "a non-moderator read the queue: $queue")
        assertEquals(403, queue.status)

        val reports = moderation.reports()
        assertTrue(reports is StoreResult.Failed, "a non-moderator read the report queue: $reports")
    }

    @Test
    fun aModeratorIsRecognisedAndAPublisherIsNot() {
        if (!stackUp()) return
        val publisher = userId("pub-a@example.test") ?: return

        assertTrue(moderationFor(moderator()).amIModerator())
        assertFalse(moderationFor(publisher).amIModerator())
        // Signed out is not a moderator, and asking must not throw: this is read to decide what to draw.
        assertFalse(SupabaseModerationService(baseUrl, anonKey, SupabaseAccountService(baseUrl, anonKey, "x://y")).amIModerator())
    }

    @Test
    fun theProfileTellsAModeratorTheyAreOneAndTellsEveryoneElseNothing() {
        if (!stackUp()) return
        val publisher = userId("pub-a@example.test") ?: return
        val mod = moderator()

        val asModerator = SupabaseSubmissionService(baseUrl, anonKey, accountsFor(mod)).myProfile()
        assertTrue(asModerator is StoreResult.Ok)
        assertTrue(assertNotNull(asModerator.value).isModerator)

        val asPublisher = SupabaseSubmissionService(baseUrl, anonKey, accountsFor(publisher)).myProfile()
        assertTrue(asPublisher is StoreResult.Ok)
        assertFalse(assertNotNull(asPublisher.value).isModerator)
        // The queue size is a moderator's to know: `jsonb_strip_nulls` drops the key entirely otherwise, so
        // this is 0 rather than the store's real backlog.
        assertEquals(0, asPublisher.value!!.moderationQueue)
    }
}

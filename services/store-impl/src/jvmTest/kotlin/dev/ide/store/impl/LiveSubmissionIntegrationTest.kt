package dev.ide.store.impl

import dev.ide.store.StoreAccount
import dev.ide.store.StoreResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end against a **running local Supabase stack** (`supabase start`).
 *
 * Self-skipping: when the stack is not reachable every test returns early, so this stays green in CI and
 * on a machine with Docker off. That is the trade for testing the real thing — the alternative, a mocked
 * HTTP layer, would have passed happily against both of the schema bugs that only a real Postgres caught.
 *
 * The local stack's keys and JWT secret are fixed, well-known development values (they are printed by
 * `supabase start` and are identical for every project), so hard-coding them here reveals nothing.
 */
class LiveSubmissionIntegrationTest {

    private val baseUrl = "http://127.0.0.1:54321"
    private val anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9." +
        "CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"
    private val jwtSecret = "super-secret-jwt-token-with-at-least-32-characters-long"

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temps.forEach { it.deleteRecursively() }
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

    /** Mint a session token the way a real OAuth login would produce one. */
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
        val sig = b64(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$sig"
    }

    /** Read a user id straight out of the running Postgres, so the test needs no fixture ids. */
    private fun userId(email: String): String? = runCatching {
        ProcessBuilder(
            "docker", "exec", "supabase_db_codeassist", "psql", "-U", "postgres", "-tAc",
            "select id from auth.users where email = '$email'",
        ).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().ifBlank { null }
    }.getOrNull()

    /**
     * An account service pinned to an already-minted token — the real one would get there via a browser
     * redirect, which a test cannot drive.
     */
    private fun accountsFor(uid: String): SupabaseAccountService {
        val svc = SupabaseAccountService(baseUrl, anonKey, "codeassist://auth-callback")
        // complete() with an implicit-flow redirect is the honest way in: it exercises the same
        // adopt()/fetchUser() path a real sign-in takes, rather than reaching past it.
        val r = svc.complete("codeassist://auth-callback#access_token=${mintJwt(uid)}&refresh_token=none")
        assertTrue(r is StoreResult.Ok, "sign-in with a minted token failed: $r")
        return svc
    }

    /**
     * Delete the items previous runs of THIS test created.
     *
     * Withdrawing a version does not remove the item, so each run left one behind and after ten runs the
     * account sat permanently at the per-publisher item cap — the suite passed on a fresh database and
     * failed forever after. Matched on the test's own slug prefixes so it can never touch real content, and
     * done through psql because a submitter has no delete privilege on `store_items`, correctly.
     *
     * The uploaded archives stay in the local uploads bucket; they are a few KB in a dev container and
     * nothing reads them once the row is gone.
     */
    private fun deleteTestItems(email: String) {
        runCatching {
            ProcessBuilder(
                "docker", "exec", "supabase_db_codeassist", "psql", "-U", "postgres", "-c",
                """
                delete from store_items i
                 using auth.users u
                 where i.publisher_id = u.id
                   and u.email = '$email'
                   and (i.slug like 'live-test-%' or i.slug like 'duplicate-probe-%' or i.slug like 'quota-probe-%');
                """.trimIndent(),
            ).redirectErrorStream(true).start().waitFor()
        }
    }

    /**
     * Withdraw everything this account has pending, so a submitting test starts from a known state.
     *
     * Without this the suite only passes against a fresh database: the pending quota is three, the
     * quota-probe test deliberately fills it, and every earlier run leaves rows behind. A live test that
     * needs a pristine backend is a test that stops being run.
     */
    private fun clearPending(service: SupabaseSubmissionService) {
        val mine = service.mine()
        if (mine !is StoreResult.Ok) return
        mine.value.filter { it.status == "pending" }.forEach { pending ->
            service.withdraw(pending.itemSlug, pending.version)
        }
    }

    private fun sampleProject(): File {
        val root = kotlin.io.path.createTempDirectory("ca-live-submit-").toFile().also { temps += it }
        File(root, "settings.gradle.kts").writeText("include(\":app\")")
        File(root, "app/src/main/kotlin").mkdirs()
        File(root, "app/src/main/kotlin/Main.kt").writeText("fun main() = println(\"hi\")")
        File(root, "README.md").writeText("# Live submission test")
        // Must not reach the bucket.
        File(root, "release.jks").writeText("SIGNING-KEY")
        File(root, "local.properties").writeText("sdk.dir=/x")
        return root
    }

    @Test
    fun packagesAndSubmitsAgainstTheRealBackend() {
        if (!stackUp()) return
        val bob = userId("bob@example.com") ?: return
        val accounts = accountsFor(bob)
        val service = SupabaseSubmissionService(baseUrl, anonKey, accounts)
        assertTrue(service.submissionsAvailable())
        deleteTestItems("bob@example.com")
        clearPending(service)

        val packed = service.pack(sampleProject().absolutePath)
        assertTrue(packed is StoreResult.Ok, "pack failed: $packed")
        val p = (packed as StoreResult.Ok).value
        // The secrets were found and reported, not quietly bundled.
        assertTrue(p.excluded.contains("release.jks"), "keystore should be reported as excluded")
        assertTrue(p.files.none { it.path.endsWith(".jks") }, "keystore must not be in the archive")

        val unique = System.currentTimeMillis() % 900 + 1
        val request = dev.ide.store.StoreSubmissionRequest(
            title = "Live Test $unique",
            summary = "Submitted by the integration test",
            description = "A project packaged and uploaded end to end.",
            category = "java",
            language = "Kotlin",
            tags = listOf("test"),
            version = "1.0.$unique",
        )
        val submitted = service.submit(request, p)
        assertTrue(submitted is StoreResult.Ok, "submit failed: $submitted")
        val status = (submitted as StoreResult.Ok).value
        assertEquals("pending", status.status)

        // It shows up in the submitter's own list...
        val mine = service.mine()
        assertTrue(mine is StoreResult.Ok, "mine() failed: $mine")
        assertTrue(
            (mine as StoreResult.Ok).value.any { it.itemSlug == status.itemSlug },
            "the new submission should appear in mine(): ${mine.value.map { it.itemSlug }}",
        )

        // ...and is NOT in the anonymous catalog, because it has not been approved.
        val anonCatalog = SupabaseStoreSource(baseUrl, anonKey).catalog(84)
        assertTrue(anonCatalog is StoreResult.Ok, "catalog failed: $anonCatalog")
        val visible = (anonCatalog as StoreResult.Ok).value.sections.flatMap { it.items }.map { it.id }
        assertTrue(
            status.itemSlug !in visible,
            "a PENDING submission must not be publicly visible, but found ${status.itemSlug}",
        )

        // Withdrawing it is allowed while pending.
        assertTrue(service.withdraw(status.itemSlug, request.version) is StoreResult.Ok)
    }

    /**
     * Screenshots make the whole trip: uploaded to the private bucket, recorded on the version row, and
     * capped.
     *
     * Worth a live test because every part of it is enforced somewhere this process cannot see: the bucket
     * accepts the object under a policy, and `screenshot_paths` carries a CHECK on its length. A mocked
     * transport would agree with itself and prove nothing.
     */
    @Test
    fun screenshotsUploadAndAreRecordedOnTheVersion() {
        if (!stackUp()) return
        val bob = userId("bob@example.com") ?: return
        val accounts = accountsFor(bob)
        val service = SupabaseSubmissionService(baseUrl, anonKey, accounts)
        deleteTestItems("bob@example.com")
        clearPending(service)

        val packed = service.pack(sampleProject().absolutePath)
        assertTrue(packed is StoreResult.Ok, "pack failed: $packed")

        // More than the cap, so the take() and the CHECK are both exercised.
        val shots = (0 until SupabaseSubmissionService.MAX_SCREENSHOTS + 2).map { i ->
            File(kotlin.io.path.createTempDirectory("ca-live-shot-").toFile().also { temps += it }, "s$i.png")
                .also { it.writeBytes(onePixelPng()) }.absolutePath
        }
        val unique = System.currentTimeMillis() % 900 + 1
        val request = dev.ide.store.StoreSubmissionRequest(
            title = "Live Test Shots $unique",
            summary = "Submitted with screenshots",
            description = "Checks the screenshot upload path.",
            category = "java",
            language = "Kotlin",
            tags = listOf("test"),
            version = "1.0.$unique",
            screenshotPaths = shots,
        )
        val submitted = service.submit(request, (packed as StoreResult.Ok).value)
        assertTrue(submitted is StoreResult.Ok, "submit failed: $submitted")
        val status = (submitted as StoreResult.Ok).value

        val recorded = screenshotPathsOf(status.itemSlug, request.version)
        assertEquals(
            SupabaseSubmissionService.MAX_SCREENSHOTS,
            recorded.size,
            "the cap should be applied client-side, not left for the CHECK to reject: $recorded",
        )
        // Every recorded path is an object that actually exists, read back as the owner.
        recorded.forEach { path ->
            assertTrue(objectExists(path, accounts), "recorded screenshot is not in the bucket: $path")
        }

        assertTrue(service.withdraw(status.itemSlug, request.version) is StoreResult.Ok)
    }

    /** The smallest valid PNG, so the upload is a real image rather than bytes named `.png`. */
    private fun onePixelPng(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==",
    )

    /** `screenshot_paths` as the database holds it, read straight out of Postgres. */
    private fun screenshotPathsOf(slug: String, version: String): List<String> {
        val out = ProcessBuilder(
            "docker", "exec", "supabase_db_codeassist", "psql", "-U", "postgres", "-At", "-c",
            """
            select coalesce(array_to_string(v.screenshot_paths, '|'), '')
              from store_item_versions v
              join store_items i on i.id = v.item_id
             where i.slug = '$slug' and v.version = '$version';
            """.trimIndent(),
        ).redirectErrorStream(true).start()
        val text = out.inputStream.bufferedReader().readText().trim()
        out.waitFor()
        return text.lines().firstOrNull { it.isNotBlank() }?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
    }

    /** A HEAD on the private bucket as the submitter, which is the only role allowed to read it. */
    private fun objectExists(path: String, accounts: SupabaseAccountService): Boolean {
        val token = accounts.bearer() ?: return false
        val c = (URL("$baseUrl/storage/v1/object/store-uploads/$path").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "GET"
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 4000
                readTimeout = 4000
            }
        return runCatching { c.responseCode in 200..299 }.getOrDefault(false)
    }

    /** The quota message from the database trigger has to reach the user readably. */
    @Test
    fun pendingQuotaMessageSurfacesFromTheTrigger() {
        if (!stackUp()) return
        val alice = userId("alice@example.com") ?: return
        val accounts = accountsFor(alice)
        val service = SupabaseSubmissionService(baseUrl, anonKey, accounts)
        deleteTestItems("alice@example.com")
        clearPending(service)
        val packed = service.pack(sampleProject().absolutePath)
        if (packed !is StoreResult.Ok) return
        // Push until one is refused and
        // assert the refusal is a readable sentence rather than a raw SQLSTATE.
        val run = System.nanoTime()
        var refusal: String? = null
        for (i in 1..12) {
            val r = service.submit(
                dev.ide.store.StoreSubmissionRequest(
                    // Unique per run: reusing a title collides on the globally unique slug and would
                    // report THAT instead of the quota, which is a different code path.
                    title = "Quota Probe $run $i",
                    summary = "s", description = "d", category = "java", version = "2.0.$i",
                ),
                packed.value,
            )
            if (r is StoreResult.Failed) { refusal = r.message; break }
        }
        assertNotNull(refusal, "one of 12 submissions should have hit a quota")
        assertTrue(
            refusal.contains("limit") || refusal.contains("already has"),
            "a quota refusal should read as a sentence, got: $refusal",
        )
        assertTrue(!refusal.startsWith("store: "), "the 'store: ' prefix should be stripped for the user")
        assertTrue(
            !refusal.contains("constraint") && !refusal.contains("duplicate key"),
            "a raw Postgres error must never reach the user: $refusal",
        )
    }

    /** Re-submitting the same title is a mundane mistake and must read as one. */
    @Test
    fun duplicateTitleReportsAReadableMessageNotAConstraintName() {
        if (!stackUp()) return
        val bob = userId("bob@example.com") ?: return
        val service = SupabaseSubmissionService(baseUrl, anonKey, accountsFor(bob))
        deleteTestItems("bob@example.com")
        clearPending(service)
        val packed = service.pack(sampleProject().absolutePath)
        if (packed !is StoreResult.Ok) return
        val title = "Duplicate Probe ${System.nanoTime()}"
        fun send(v: String) = service.submit(
            dev.ide.store.StoreSubmissionRequest(
                title = title, summary = "s", description = "d", category = "java", version = v,
            ),
            packed.value,
        )
        // First one may itself be refused if bob is at his pending quota; only assert when it lands.
        if (send("1.0.0") !is StoreResult.Ok) return
        val second = send("1.0.1")
        if (second is StoreResult.Failed) {
            assertTrue(
                !second.message.contains("constraint") && !second.message.contains("duplicate key"),
                "raw Postgres error leaked to the user: ${second.message}",
            )
        }
    }

    @Test
    fun versionCodeOrdersVersionsCorrectly() {
        val v = SupabaseSubmissionService.versionCodeOf("1.2.3")
        assertEquals(1_002_003, v)
        assertTrue(SupabaseSubmissionService.versionCodeOf("1.0.10") > SupabaseSubmissionService.versionCodeOf("1.0.9"))
        assertTrue(SupabaseSubmissionService.versionCodeOf("2.0.0") > SupabaseSubmissionService.versionCodeOf("1.99.99"))
        assertEquals(0, SupabaseSubmissionService.versionCodeOf("garbage"))
    }

    @Test
    fun slugIsUrlSafeAndAccountSuffixedSoTitlesCanCollide() {
        val a = SupabaseSubmissionService.slugFor("My Cool App!", "29c43183-283c-4d35")
        val b = SupabaseSubmissionService.slugFor("My Cool App!", "32651bd7-d933-47ab")
        assertTrue(a.startsWith("my-cool-app-"), a)
        assertTrue(a != b, "two accounts submitting the same title must not collide on the unique slug")
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' }, a)
        assertTrue(SupabaseSubmissionService.slugFor("!!!", "abcdef").startsWith("project-"))
    }
}

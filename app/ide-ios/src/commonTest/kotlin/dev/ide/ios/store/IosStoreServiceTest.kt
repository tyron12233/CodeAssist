package dev.ide.ios.store

import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreAccount
import dev.ide.store.StoreAccountService
import dev.ide.store.StoreProvider
import dev.ide.store.StorePublisherProfile
import dev.ide.store.StoreCatalogSource
import dev.ide.store.PendingSubmission
import dev.ide.store.ReviewQueue
import dev.ide.store.StoreModerationService
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.store.StoreReviewService
import dev.ide.store.StoreSubmissionService
import dev.ide.store.SubmissionListing
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath
import dev.ide.ui.backend.UiInstallState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The iOS store as a whole: the feed reaching the installer, the installer reaching the disk, and what an
 * unconfigured build answers.
 *
 * The ports are fakes, and that is the point — what is worth proving on this platform is the WIRING plus
 * the parts iOS implements itself (the zip reader, the filesystem), not the Supabase transport, which is
 * shared code the JVM suite covers against fixtures captured from the real backend. The one thing a
 * mock-free test cannot reach here is the network round trip, and a passing request proves less about this
 * host than the install does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosStoreServiceTest {

    private val scratch = StoreFs.tempPath("ca-ios-store-", "")
    private val projects = joinPath(scratch, "Projects")
    private val support = joinPath(scratch, "Support")

    @AfterTest
    fun cleanUp() {
        StoreFs.deleteRecursively(scratch)
    }

    private fun service(
        source: StoreCatalogSource,
        moderation: StoreModerationService = StoreModerationService.Unsupported,
        accounts: StoreAccountService = StoreAccountService.Unsupported,
        submissions: StoreSubmissionService = StoreSubmissionService.Unsupported,
    ): IosStoreService {
        StoreFs.mkdirs(projects)
        StoreFs.mkdirs(support)
        return IosStoreService(
            source = source,
            accountService = accounts,
            submissionService = submissions,
            reviewService = StoreReviewService.Unsupported,
            moderationService = moderation,
            projectsRoot = { projects },
            cacheRoot = support,
            preferences = IosPreferences(),
            adopt = { dir -> if (StoreFs.isDirectory(dir)) null else "not a project" },
        )
    }

    // ---- a build with no store ------------------------------------------------------------------------

    /**
     * A fork building with no endpoint gets a store that is wired and inert, not one that throws.
     *
     * Every one of these is a surface the UI decides whether to DRAW from, so the answers matter: a false
     * here hides a button, and an exception would take the tab with it.
     */
    @Test
    fun anUnconfiguredBuildIsInertRatherThanBroken() = runTest {
        val store = service(StoreCatalogSource.Unconfigured)

        assertFalse(store.storeAvailable())
        assertNull(store.feed())
        assertEquals(emptyList(), store.searchPage("anything").items)
        assertEquals(emptyList(), store.searchCategories())
        assertFalse(store.moderationAvailable())
        assertFalse(store.isModerator())
        assertFalse(store.reviewsAvailable())
        assertNull(store.myProfile())
        assertTrue(store.authProviders().isEmpty(), "a provider that cannot succeed must not be offered")
    }

    /** Publishing needs a zip writer, which this platform has not got. The UI hides every entry point. */
    @Test
    fun publishingIsUnavailableOnThisHost() = runTest {
        val store = service(FakeSource())
        assertFalse(store.submissionsAvailable(), "iOS cannot package a project: there is no zip writer")
        assertNull(store.packProject(projects))
    }

    // ---- installing --------------------------------------------------------------------------------

    /**
     * The whole install: the feed teaches the store what an item downloads, the archive is verified against
     * the checksum the catalog row promised, and what comes out is a project the picker will list.
     */
    @Test
    fun installsAProjectTheFeedDescribed() = runTest {
        val source = FakeSource()
        val store = service(source)

        assertNotNull(store.feed(), "the feed should load")

        val result = store.install("nimbus")
        assertTrue(result.success, "install should succeed: ${result.message}")

        val root = assertNotNull(result.rootPath, "a successful install reports where the project landed")
        assertEquals(joinPath(projects, "nimbus"), root)
        assertEquals("include(\":app\")\n", StoreFs.readText(joinPath(root, "settings.gradle.kts")))
        assertEquals("# Nimbus\n", StoreFs.readText(joinPath(root, "README.md")))

        // Reported as finished, and with the path — the row's next tap is an Open that needs it.
        val progress = assertNotNull(store.installProgress().value["nimbus"])
        assertEquals(UiInstallState.INSTALLED, progress.state)
        assertEquals(root, progress.rootPath)

        // And remembered across launches, which is what the next cold start reads to know this item is here.
        val history = assertNotNull(StoreFs.readText(joinPath(support, "store/installed.txt")))
        assertContains(history, "nimbus")
        assertContains(history, root)

        assertEquals(1, source.installsCounted, "a finished install is counted exactly once")
    }

    /**
     * A payload whose bytes do not match the row's checksum is refused, and nothing is written.
     *
     * The archive comes out of a public bucket and is about to be unpacked into the user's projects, so
     * this is the gate that makes that safe. It is the shared installer that enforces it, but the hashing
     * underneath is CommonCrypto on this platform, so it is worth seeing hold here.
     */
    @Test
    fun refusesAnArchiveThatDoesNotMatchItsChecksum() = runTest {
        val source = FakeSource(sha256 = "0".repeat(64))
        val store = service(source)
        store.feed()

        val result = store.install("nimbus")
        assertFalse(result.success, "a checksum mismatch must fail the install")
        assertEquals(emptyList(), StoreFs.list(projects), "nothing may be left in the projects folder")
        assertEquals(0, source.installsCounted, "a failed install must not be counted")
    }

    /** An id the feed never described has nothing to fetch, and says so rather than guessing a path. */
    @Test
    fun refusesAnItemItWasNeverToldAbout() = runTest {
        val store = service(FakeSource())
        store.feed()
        assertFalse(store.install("no-such-item").success)
    }

    // ---- moderation ----------------------------------------------------------------------------------

    /**
     * The moderation surface end to end through this host: the queue arrives, a decision reaches the port,
     * and the row a decision names is the one the queue handed the screen.
     *
     * Worth asserting here rather than trusting the shared adapter alone, because the version id the UI
     * hands back is looked up in a map the queue read filled — so a host that reached the adapter through
     * two different instances would answer every decision with "that submission is no longer in the queue"
     * and nothing would say why.
     */
    @Test
    fun aModeratorCanReadTheQueueAndDecide() = runTest {
        val moderation = FakeModeration()
        val store = service(FakeSource(), moderation)

        assertTrue(store.moderationAvailable())

        val queue = store.reviewQueue()
        assertNull(queue.error, "the queue should load")
        val pending = queue.pending.single()
        assertEquals("Nimbus", pending.listing.title)
        assertEquals("nimbus", pending.listing.slug)

        assertNull(store.approveSubmission(pending.versionId, note = "Looks good"))
        assertEquals("approve:nimbus:Looks good", moderation.decisions.single())

        assertNull(store.rejectSubmission(pending.versionId, note = "Needs a README"))
        assertEquals("reject:nimbus:Needs a README", moderation.decisions[1])
    }

    /** What is DRAWN comes from [IosStoreService.isModerator], and a signed-out reader is not one. */
    @Test
    fun theModerationSurfaceIsHiddenWithoutAModeratorSession() = runTest {
        assertFalse(service(FakeSource(), FakeModeration()).isModerator())
    }

    /**
     * Signing in as a moderator has to make the surface appear.
     *
     * A session says who you are and nothing more; that this account moderates is on the publisher row,
     * which is read as the session is adopted and folded back into the account. Miss that and a real
     * moderator signs in and is told the store will not let them moderate — which is what this host did
     * until the adoption was shared with the one the other hosts use.
     */
    @Test
    fun aModeratorSessionMakesTheSurfaceAppear() = runTest {
        val store = service(
            source = FakeSource(),
            moderation = FakeModeration(),
            accounts = FakeAccounts(),
            submissions = FakeProfile(isModerator = true),
        )
        assertFalse(store.isModerator(), "nothing is drawn before anyone signs in")

        store.completeSignIn("codeassist://auth-callback#access_token=t&refresh_token=r")
        // Two states arrive: the sign-in itself, and then what adopting the account learned. The second is
        // the one under test, so this waits for the row's own fields rather than for `account != null`.
        val account = awaitAdopted(store)

        assertEquals("tyron", account?.handle, "the row's handle reaches the account")
        assertTrue(store.isModerator(), "a moderator's session has to reach isModerator()")
    }

    /** The same sign-in by an ordinary account leaves the surface hidden. */
    @Test
    fun anOrdinaryAccountSignsInWithoutTheModerationSurface() = runTest {
        val store = service(
            source = FakeSource(),
            moderation = FakeModeration(),
            accounts = FakeAccounts(),
            submissions = FakeProfile(isModerator = false),
        )

        store.completeSignIn("codeassist://auth-callback#access_token=t&refresh_token=r")
        awaitAdopted(store)

        assertFalse(store.isModerator())
    }

    /**
     * The account as it stands once the publisher row has been folded into it.
     *
     * Waited for in REAL time on a real dispatcher: adoption runs on the store's own background scope,
     * which `runTest`'s virtual clock does not drive, so a plain `withTimeout` inside it expires
     * instantly without ever letting the work run.
     */
    private suspend fun awaitAdopted(store: IosStoreService) =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10.seconds) { store.authState().first { it.account?.handle != null }.account }
        }

    /** Completes a sign-in with one account; no network anywhere. */
    private class FakeAccounts : StoreAccountService {
        override fun authAvailable() = true
        override fun providers() = listOf(StoreProvider.GITHUB)
        override fun hasStoredSession() = false
        override fun complete(redirect: String): StoreResult<StoreAccount> =
            StoreResult.Ok(StoreAccount(userId = "u1", email = "t@example.invalid"))
    }

    /** Answers one publisher row, which is where "this account moderates" actually lives. */
    private class FakeProfile(private val isModerator: Boolean) : StoreSubmissionService {
        override fun submissionsAvailable() = true
        override fun myProfile(): StoreResult<StorePublisherProfile?> = StoreResult.Ok(
            StorePublisherProfile(
                handle = "tyron",
                displayName = "Tyron Scott",
                verified = true,
                isModerator = isModerator,
            ),
        )
    }

    /** A queue with one pending submission, and a record of what was decided about it. */
    private class FakeModeration : StoreModerationService {
        val decisions = mutableListOf<String>()

        override fun moderationAvailable() = true
        override fun amIModerator() = true

        override fun queue(limit: Int): StoreResult<ReviewQueue> = StoreResult.Ok(
            ReviewQueue(
                pending = listOf(
                    PendingSubmission(
                        versionId = "v1",
                        version = "1.0.0",
                        status = "pending",
                        storagePath = "user-1/nimbus/1.0.0.zip",
                        sizeBytes = 4096,
                        sha256 = "abc123",
                        listing = SubmissionListing(
                            slug = "nimbus",
                            title = "Nimbus",
                            summary = "A weather app",
                            category = "android",
                        ),
                    ),
                ),
            ),
        )

        override fun approve(
            submission: PendingSubmission,
            note: String?,
            clearIconIfMissing: Boolean,
        ): StoreResult<String> {
            decisions += "approve:${submission.listing?.slug}:$note"
            return StoreResult.Ok(submission.versionId)
        }

        override fun reject(submission: PendingSubmission, note: String): StoreResult<String> {
            decisions += "reject:${submission.listing?.slug}:$note"
            return StoreResult.Ok(submission.versionId)
        }
    }

    /**
     * Serves one feed and one archive, the way the real source would.
     *
     * [downloadPayload] verifies the checksum itself because the real one hashes while it streams; a fake
     * that skipped it would make the mismatch test pass for the wrong reason.
     */
    private class FakeSource(private val sha256: String = ARCHIVE_SHA256) : StoreCatalogSource {
        var installsCounted = 0

        override fun configured() = true
        override val appBuild: Int? = 1
        override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("n/a")
        override fun search(query: StoreQuery, appBuild: Int) =
            StoreResult.Unavailable<List<RemoteStoreItem>>("n/a")

        override fun feedDocument(seedSlug: String?) = StoreResult.Ok(
            """
            {
              "mode": "populated",
              "version": 4,
              "storeState": { "count": 1, "acceptingSubmissions": true },
              "sections": [
                {
                  "id": "everything",
                  "type": "catalogue",
                  "items": [
                    {
                      "id": "nimbus",
                      "kind": "community",
                      "title": "Nimbus",
                      "summary": "A weather app",
                      "category": "android",
                      "version": "1.0.0",
                      "storagePath": "nimbus/1.0.0.zip",
                      "sizeBytes": $ARCHIVE_BYTES,
                      "sha256": "$sha256"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        override fun downloadPayload(
            storagePath: String,
            expectedSha256: String?,
            expectedBytes: Long,
            intoPath: String,
            onProgress: (Float) -> Unit,
        ): StoreResult<Unit> {
            val bytes = decodeBase64(ARCHIVE_BASE64)
            StoreFs.mkdirsForFile(intoPath)
            StoreFs.writeBytes(intoPath, bytes)
            onProgress(1f)
            val actual = dev.ide.store.impl.platform.sha256Hex(bytes)
            if (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true)) {
                StoreFs.delete(intoPath)
                return StoreResult.Failed("The download did not match its checksum")
            }
            return StoreResult.Ok(Unit)
        }

        override fun recordInstall(slug: String, installId: String) {
            installsCounted++
        }
    }

    private companion object {
        /**
         * A three-file project, deflated, built by `java.util.zip` rather than by anything in this module.
         *
         * iOS has no zip writer, which is the same reason publishing is unavailable here — so an archive to
         * read has to come from somewhere else, and one that did is also the honest test of the reader.
         */
        const val ARCHIVE_BASE64 =
            "UEsDBBQAAAAIAO+BL138R2XoEgAAABAAAAATAAAAc2V0dGluZ3MuZ3JhZGxlLmt0c8vMS84pTUnV" +
            "ULJKLChQ0uQCAFBLAwQUAAAACADvgS9dvsnyAyUAAAAlAAAAGwAAAGFwcC9zcmMvbWFpbi9rb3Rs" +
            "aW4vTWFpbi5rdEsrzVPITczM09BUqOZSAIKCosy8kpw8DSW/zNyk0mIlTa5aLgBQSwMEFAAAAAgA" +
            "74EvXcx9vNkLAAAACQAAAAkAAABSRUFETUUubWRTVvDLzE0qLeYCAFBLAQIUAxQAAAAIAO+BL138" +
            "R2XoEgAAABAAAAATAAAAAAAAAAAAAACAAQAAAABzZXR0aW5ncy5ncmFkbGUua3RzUEsBAhQDFAAA" +
            "AAgA74EvXb7J8gMlAAAAJQAAABsAAAAAAAAAAAAAAIABQwAAAGFwcC9zcmMvbWFpbi9rb3RsaW4v" +
            "TWFpbi5rdFBLAQIUAxQAAAAIAO+BL13MfbzZCwAAAAkAAAAJAAAAAAAAAAAAAACAAaEAAABSRUFE" +
            "TUUubWRQSwUGAAAAAAMAAwDBAAAA0wAAAAAA"
        const val ARCHIVE_BYTES = 426L
        const val ARCHIVE_SHA256 = "1a4478c7a87f2ba2bb8fe17d5687908c5287ed9f0bc220417012edaa7688e22e"

        fun decodeBase64(text: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val out = ArrayList<Byte>(text.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (c in text) {
                if (c == '=' || c == '\n') continue
                val v = alphabet.indexOf(c)
                if (v < 0) continue
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.add(((buffer shr bits) and 0xFF).toByte())
                }
            }
            return out.toByteArray()
        }
    }
}

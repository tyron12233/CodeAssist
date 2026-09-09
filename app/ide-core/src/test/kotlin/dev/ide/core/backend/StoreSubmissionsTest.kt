package dev.ide.core.backend

import dev.ide.store.PackagedFile
import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import dev.ide.store.StoreSubmissionRequest
import dev.ide.store.StoreSubmissionService
import dev.ide.store.StoreSubmissionStatus
import dev.ide.ui.backend.UiSubmissionDraft
import dev.ide.ui.backend.UiSubmissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Packaging and publishing, engine-side.
 *
 * The two things worth pinning here are that the *real* archive reaches the upload (the summary the screen
 * sees cannot be turned back into one), and that a refusal keeps the words the backend chose: quota limits
 * and duplicate titles arrive as sentences meant for the user, and a paraphrase would drop the actionable
 * part.
 */
class StoreSubmissionsTest {

    private fun archive(files: Int = 12) = PackagedProject(
        files = (1..files).map { PackagedFile("src/File$it.kt", 100L) },
        excluded = listOf("local.properties", "release.jks"),
        totalBytes = 4096,
        sha256 = "a".repeat(64),
        archivePath = "/tmp/ca-submission-test.zip",
    )

    private class FakeSubmissions(
        private val packResult: StoreResult<PackagedProject>,
        private val submitResult: StoreResult<StoreSubmissionStatus> =
            StoreResult.Ok(StoreSubmissionStatus("my-app-ab12", "1.0.0", "pending")),
        private val mineResult: StoreResult<List<StoreSubmissionStatus>> = StoreResult.Ok(emptyList()),
        private val available: Boolean = true,
    ) : StoreSubmissionService {
        var packCalls = 0
        var submitted: StoreSubmissionRequest? = null
        var uploaded: PackagedProject? = null
        var withdrawn: Pair<String, String>? = null

        override fun submissionsAvailable() = available
        override fun pack(projectRoot: String): StoreResult<PackagedProject> {
            packCalls++
            return packResult
        }
        override fun submit(request: StoreSubmissionRequest, packaged: PackagedProject): StoreResult<StoreSubmissionStatus> {
            submitted = request
            uploaded = packaged
            return submitResult
        }
        override fun mine() = mineResult
        override fun withdraw(itemSlug: String, version: String): StoreResult<Unit> {
            withdrawn = itemSlug to version
            return StoreResult.Ok(Unit)
        }
    }

    @Test
    fun packagingReportsWhatWasExcludedSoTheUserCanSeeTheSecretsWereDropped() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val packed = assertNotNull(StoreSubmissions(fake).pack("/projects/my-app"))

        assertEquals(12, packed.fileCount)
        assertEquals("/projects/my-app", packed.rootPath)
        assertEquals(listOf("local.properties", "release.jks"), packed.excluded)
        assertEquals("a".repeat(64), packed.sha256)
    }

    /** The whole reason the engine keeps the archive: the summary cannot be turned back into one. */
    @Test
    fun theRealArchiveIsUploadedNotAReconstructionOfTheSummary() {
        val fake = FakeSubmissions(StoreResult.Ok(archive(files = 7)))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/projects/my-app"))

        val result = subs.submit(UiSubmissionDraft(title = "My App", summary = "s", description = "d", category = "java"), packed)

        assertTrue(result.success, result.message)
        // A rebuilt PackagedProject would carry no files at all, and the database's CHECK on file_count
        // (1..2000) would reject the row.
        assertEquals(7, fake.uploaded?.fileCount, "the manifest has to survive to the upload")
        assertEquals(7, fake.uploaded?.files?.size)
        assertEquals(1, fake.packCalls, "the archive was already packed; submitting must not redo it")
    }

    /** A screen that outlived the engine still has to be able to submit. */
    @Test
    fun submittingWithNoRememberedArchiveRepacksRatherThanFailing() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        // A summary that this instance never produced, as if the engine had been rebuilt.
        val stale = dev.ide.ui.backend.UiPackagedProject(
            rootPath = "/projects/my-app", fileCount = 12, totalBytes = 4096,
            sha256 = "a".repeat(64), excluded = emptyList(), archivePath = "/tmp/gone.zip",
        )

        val result = subs.submit(UiSubmissionDraft(title = "T", summary = "s", description = "d", category = "java"), stale)

        assertTrue(result.success, result.message)
        assertEquals(1, fake.packCalls, "it should re-pack from the root path")
        assertEquals(12, fake.uploaded?.fileCount)
    }

    @Test
    fun theDraftIsTrimmedAndEmptyOptionalsAreDropped() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))

        subs.submit(
            UiSubmissionDraft(
                title = "  Spaced Title  ", summary = " s ", description = " d ", category = " java ",
                language = "   ", tags = listOf(" kotlin ", "", "  "), version = "  2.1.0 ", changelog = "  ",
            ),
            packed,
        )

        val sent = assertNotNull(fake.submitted)
        assertEquals("Spaced Title", sent.title)
        assertEquals("java", sent.category)
        assertEquals("2.1.0", sent.version)
        assertEquals(listOf("kotlin"), sent.tags, "blank tags are noise, not data")
        assertNull(sent.language, "a blank language is not a language")
        assertNull(sent.changelog)
    }

    @Test
    fun anEmptyVersionFallsBackRatherThanUploadingNothing() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))
        subs.submit(UiSubmissionDraft(title = "T", summary = "s", description = "d", category = "java", version = "  "), packed)
        assertEquals("1.0.0", fake.submitted?.version)
    }

    /** Quota and duplicate-title refusals are written for the user; they must arrive intact. */
    @Test
    fun aRefusalKeepsTheBackendsOwnSentence() {
        val fake = FakeSubmissions(
            packResult = StoreResult.Ok(archive()),
            submitResult = StoreResult.Failed("You already have 3 submissions waiting for review"),
        )
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))

        val result = subs.submit(UiSubmissionDraft(title = "T", summary = "s", description = "d", category = "java"), packed)

        assertFalse(result.success)
        assertEquals("You already have 3 submissions waiting for review", result.message)
        assertNull(result.submission)
    }

    @Test
    fun aPackagingFailureKeepsItsReasonForTheScreen() {
        val fake = FakeSubmissions(StoreResult.Failed("Project is too large to submit (7 MB, limit 5 MB)"))
        val subs = StoreSubmissions(fake)

        assertNull(subs.pack("/projects/huge"))
        assertEquals("Project is too large to submit (7 MB, limit 5 MB)", subs.packError("/projects/huge"))
        assertNull(subs.packError("/projects/never-tried"))
    }

    @Test
    fun aSuccessfulPackClearsAnEarlierFailure() {
        var result: StoreResult<PackagedProject> = StoreResult.Failed("Nothing to submit: every file was excluded")
        val fake = object : StoreSubmissionService {
            override fun submissionsAvailable() = true
            override fun pack(projectRoot: String) = result
        }
        val subs = StoreSubmissions(fake)
        assertNull(subs.pack("/p"))
        assertNotNull(subs.packError("/p"))

        result = StoreResult.Ok(archive())
        assertNotNull(subs.pack("/p"))
        assertNull(subs.packError("/p"), "a stale error would explain a result that no longer exists")
    }

    @Test
    fun submissionStatusStringsMapOntoTheUiStates() {
        fun statusFor(wire: String): UiSubmissionStatus {
            val fake = FakeSubmissions(
                packResult = StoreResult.Ok(archive()),
                mineResult = StoreResult.Ok(listOf(StoreSubmissionStatus("slug", "1.0.0", wire))),
            )
            return StoreSubmissions(fake).mine().single().status
        }
        assertEquals(UiSubmissionStatus.SUBMITTED, statusFor("pending"))
        assertEquals(UiSubmissionStatus.PUBLISHED, statusFor("approved"))
        assertEquals(UiSubmissionStatus.REJECTED, statusFor("rejected"))
        assertEquals(UiSubmissionStatus.CHANGES_REQUESTED, statusFor("changes_requested"))
        assertEquals(UiSubmissionStatus.BUILDING, statusFor("building"))
        // A status this build has never heard of must not blank the card.
        assertEquals(UiSubmissionStatus.SUBMITTED, statusFor("quarantined"))
    }

    @Test
    fun anUnavailableServiceReportsUnavailableRatherThanPretending() {
        val subs = StoreSubmissions(StoreSubmissionService.Unsupported)
        assertFalse(subs.available())
        assertNull(subs.pack("/p"))
        assertEquals(emptyList(), subs.mine())
        assertFalse(subs.withdraw("slug", "1.0.0"))
    }

    @Test
    fun withdrawingPassesTheItemAndVersionThrough() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        assertTrue(StoreSubmissions(fake).withdraw("my-app-ab12", "1.0.0"))
        assertEquals("my-app-ab12" to "1.0.0", fake.withdrawn)
    }
}

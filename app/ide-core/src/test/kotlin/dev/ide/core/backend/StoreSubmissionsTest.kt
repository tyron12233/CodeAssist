package dev.ide.core.backend

import dev.ide.store.PackagedFile
import dev.ide.store.PackagedProject
import dev.ide.store.StorePublishedItem
import dev.ide.store.StoreResult
import dev.ide.store.StoreSubmissionRequest
import dev.ide.store.StoreSubmissionService
import dev.ide.store.StoreSubmissionStatus
import dev.ide.ui.backend.UiSubmissionDraft
import dev.ide.ui.backend.UiSubmissionStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        private val itemsResult: StoreResult<List<StorePublishedItem>> = StoreResult.Ok(emptyList()),
    ) : StoreSubmissionService {
        var packCalls = 0
        var submitted: StoreSubmissionRequest? = null
        var uploaded: PackagedProject? = null
        var withdrawn: Pair<String, String>? = null
        var deleted: Pair<String, String>? = null
        var deleteResult: StoreResult<Unit> = StoreResult.Ok(Unit)

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
        override fun myItems() = itemsResult
        override fun withdraw(itemSlug: String, version: String): StoreResult<Unit> {
            withdrawn = itemSlug to version
            return StoreResult.Ok(Unit)
        }
        override fun deleteSubmission(itemSlug: String, version: String): StoreResult<Unit> {
            deleted = itemSlug to version
            return deleteResult
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

    // ---- publishing a new version of a listing you already own ----

    /**
     * The update path is one field: a draft carrying [UiSubmissionDraft.itemSlug] reaches the service with
     * it, which is what makes the backend add a version to that item instead of creating a second listing.
     */
    @Test
    fun anUpdateDraftReachesTheServiceAsANewVersionOfThatItem() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/projects/my-app"))

        subs.submit(
            UiSubmissionDraft(
                itemSlug = "my-app-ab12",
                title = "My App",
                version = "1.2.0",
                changelog = "  Faster search  ",
            ),
            packed,
        )

        assertEquals("my-app-ab12", fake.submitted?.itemSlug)
        assertEquals("1.2.0", fake.submitted?.version)
        assertEquals("Faster search", fake.submitted?.changelog, "the changelog is trimmed, not dropped")
    }

    /**
     * The suggested version has to clear everything already SENT, not just what is live.
     *
     * `unique (item_id, version_code)` makes a repeat a database error, and a submission still in review
     * holds its code. Offering 1.0.1 here, one past the published 1.0.0, would walk the publisher straight
     * into that error while their 1.1.0 sits in the queue.
     */
    @Test
    fun theSuggestedVersionStepsPastAPendingSubmissionNotJustThePublishedOne() {
        val fake = FakeSubmissions(
            StoreResult.Ok(archive()),
            itemsResult = StoreResult.Ok(
                listOf(
                    StorePublishedItem(
                        slug = "my-app-ab12",
                        title = "My App",
                        status = "approved",
                        publishedVersion = "1.0.0",
                        highestVersion = "1.1.0",
                    ),
                ),
            ),
        )

        val items = StoreSubmissions(fake).myItems()

        assertEquals(1, items.size)
        assertEquals("my-app-ab12", items[0].slug)
        assertEquals("1.0.0", items[0].publishedVersion, "the live version is what the screen shows")
        assertEquals("1.1.1", items[0].suggestedVersion)
    }

    /** A listing whose first version is still in review is still updatable, and says so with a null. */
    @Test
    fun aListingWithNothingApprovedYetHasNoPublishedVersion() {
        val fake = FakeSubmissions(
            StoreResult.Ok(archive()),
            itemsResult = StoreResult.Ok(
                listOf(StorePublishedItem("my-app-ab12", "My App", "pending", null, "1.0.0")),
            ),
        )

        val items = StoreSubmissions(fake).myItems()

        assertNull(items[0].publishedVersion)
        assertEquals("1.0.1", items[0].suggestedVersion)
    }

    /** Signed out, or nothing published: the screen has nothing to offer and must not invent a listing. */
    @Test
    fun noListingsMeansNoUpdateTargets() {
        assertTrue(StoreSubmissions(FakeSubmissions(StoreResult.Ok(archive()))).myItems().isEmpty())
    }

    /* ---- the listing's icon ---- */

    private fun png(vararg tail: Int) =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
            tail.map { it.toByte() }.toByteArray()

    /**
     * The screen renders the icon because most projects' launcher icons are XML, so what it produces has to
     * be what travels rather than something the engine recomputes from disk.
     */
    @Test
    fun theIconTheScreenRenderedIsWhatTravels() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake, launcherIcon = { png(1, 2, 3) })
        val packed = assertNotNull(subs.pack("/p"))

        subs.submit(draft().copy(iconBytes = png(9, 9, 9)), packed)

        val path = assertNotNull(fake.submitted?.iconPath, "an icon was rendered, so one should be uploaded")
        assertContentEquals(png(9, 9, 9), java.io.File(path).readBytes(), "the on-disk raster won instead")
    }

    /** A project that ships a real raster icon and a screen that rendered nothing still publishes one. */
    @Test
    fun theProjectsOwnRasterIsTheFallback() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake, launcherIcon = { png(4, 5, 6) })
        val packed = assertNotNull(subs.pack("/p"))

        subs.submit(draft(), packed)

        val path = assertNotNull(fake.submitted?.iconPath)
        assertContentEquals(png(4, 5, 6), java.io.File(path).readBytes())
    }

    /** The upload names the published object after this file and reads its content type from the extension. */
    @Test
    fun theUploadedFileIsNamedForWhatTheBytesActuallyAre() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))
        val webp = "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".encodeToByteArray()

        subs.submit(draft().copy(iconBytes = webp), packed)

        assertTrue(assertNotNull(fake.submitted?.iconPath).endsWith(".webp"), "a WebP published as a PNG")
    }

    @Test
    fun aProjectWithNoIconPublishesWithout() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))

        subs.submit(draft().copy(iconBytes = ByteArray(0)), packed)

        assertNull(fake.submitted?.iconPath, "an empty rendering is not an icon")
    }

    private fun draft() =
        UiSubmissionDraft(title = "My App", summary = "s", description = "d", category = "java")

    @Test
    fun theVersionSuggestionRollsOverRatherThanSortingBelowWhatItFollows() {
        // Each component is capped at 999 by the store's version code, so a full field carries.
        assertEquals("1.0.1", StoreSubmissions.nextVersionAfter("1.0.0"))
        assertEquals("2.3.5", StoreSubmissions.nextVersionAfter("2.3.4"))
        assertEquals("1.3.0", StoreSubmissions.nextVersionAfter("1.2.999"))
        assertEquals("2.0.0", StoreSubmissions.nextVersionAfter("1.999.999"))
        // Nothing sent yet, or nothing that parses: start at the beginning rather than at a guess.
        assertEquals("1.0.0", StoreSubmissions.nextVersionAfter(null))
        assertEquals("1.0.0", StoreSubmissions.nextVersionAfter(""))
        assertEquals("1.0.0", StoreSubmissions.nextVersionAfter("latest"))
    }

    /* ---- editing the listing an update belongs to ---- */

    /**
     * An update carries the listing's text back as a proposed EDIT.
     *
     * The fields are the same ones a first submission fills in, so nothing but this flag separates
     * "publishing a new listing" from "changing the one I have". Without it the service has no way to tell
     * a form that showed the listing from a form that never loaded it, and a blank field would read as an
     * instruction to empty the listing.
     */
    @Test
    fun anUpdateThatShowedTheListingSendsItsFieldsAsAnEdit() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))

        subs.submit(
            UiSubmissionDraft(
                itemSlug = "my-app-ab12",
                title = "My App",
                summary = "A better summary",
                description = "A better description",
                category = "utilities",
                version = "1.1.0",
                listingEdits = true,
            ),
            packed,
        )

        assertTrue(assertNotNull(fake.submitted).editsListing)
        assertEquals("A better summary", fake.submitted?.summary)
    }

    /** A form that never had the listing in front of it proposes nothing, whatever is in its fields. */
    @Test
    fun anUpdateThatNeverLoadedTheListingProposesNoEdit() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))

        subs.submit(UiSubmissionDraft(itemSlug = "my-app-ab12", title = "My App", version = "1.1.0"), packed)

        assertFalse(assertNotNull(fake.submitted).editsListing)
    }

    /** A first submission writes the item row itself, so it is never an edit to one. */
    @Test
    fun aNewListingIsNeverAnEdit() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        val subs = StoreSubmissions(fake)
        val packed = assertNotNull(subs.pack("/p"))

        subs.submit(draft().copy(listingEdits = true), packed)

        assertFalse(assertNotNull(fake.submitted).editsListing, "there is no listing to edit yet")
    }

    /**
     * The listing's own text reaches the form.
     *
     * This is what stops an update from being a blank form: the summary, description, category, tags and
     * screenshots the store has now are what the publisher edits, rather than what they retype.
     */
    @Test
    fun aListingCarriesItsTextSoTheUpdateFormCanShowIt() {
        val fake = FakeSubmissions(
            StoreResult.Ok(archive()),
            itemsResult = StoreResult.Ok(
                listOf(
                    StorePublishedItem(
                        slug = "my-app-ab12",
                        title = "My App",
                        status = "approved",
                        publishedVersion = "1.0.0",
                        highestVersion = "1.0.0",
                        summary = "Does one thing",
                        description = "The long version",
                        category = "utilities",
                        tags = listOf("kotlin", "tools"),
                        screenshots = listOf("my-app-ab12/1.0.0/shot-0.png"),
                    ),
                ),
            ),
        )

        val item = StoreSubmissions(fake).myItems().single()

        assertEquals("Does one thing", item.summary)
        assertEquals("The long version", item.description)
        assertEquals("utilities", item.category)
        assertEquals(listOf("kotlin", "tools"), item.tags)
        assertContentEquals(listOf("my-app-ab12/1.0.0/shot-0.png"), item.screenshots)
    }

    /* ---- deleting a refused submission ---- */

    @Test
    fun deletingPassesTheItemAndVersionThroughAndReportsNothingOnSuccess() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        assertNull(StoreSubmissions(fake).delete("my-app-ab12", "1.0.0"))
        assertEquals("my-app-ab12" to "1.0.0", fake.deleted)
    }

    /** The refusals name the next step ("withdraw it first"), so they are shown as they were written. */
    @Test
    fun aRefusedDeleteKeepsTheBackendsOwnSentence() {
        val fake = FakeSubmissions(StoreResult.Ok(archive()))
        fake.deleteResult = StoreResult.Failed("That submission is still in review. Withdraw it first.")
        assertEquals(
            "That submission is still in review. Withdraw it first.",
            StoreSubmissions(fake).delete("my-app-ab12", "1.0.0"),
        )
    }

    /* ---- which decisions are still news ---- */

    /**
     * A decision the publisher already answered by sending a higher version is not announced.
     *
     * Reading the submission list is what happens immediately after publishing an update, and the approval
     * of the version that update replaces is often being noticed for the first time right then. Announcing
     * it there reads as "your app is live" one second after sending a new version for review.
     */
    @Test
    fun onlyTheNewestSubmissionOfAListingIsStillNews() {
        val subs = listOf(
            submission("my-app", "1.0.0", UiSubmissionStatus.PUBLISHED),
            submission("my-app", "1.0.1", UiSubmissionStatus.SUBMITTED),
            submission("other", "2.0.0", UiSubmissionStatus.REJECTED),
        )

        val newest = StoreSubmissions.newestPerItem(subs)

        assertEquals("1.0.1", newest["my-app"])
        assertEquals("2.0.0", newest["other"])
    }

    /** Newest means the highest version, not the order the rows arrived in. */
    @Test
    fun newestIsByVersionCodeNotByPosition() {
        val subs = listOf(
            submission("my-app", "1.10.0", UiSubmissionStatus.SUBMITTED),
            submission("my-app", "1.9.0", UiSubmissionStatus.PUBLISHED),
        )
        assertEquals("1.10.0", StoreSubmissions.newestPerItem(subs)["my-app"])
        assertTrue(StoreSubmissions.versionCodeOf("1.10.0") > StoreSubmissions.versionCodeOf("1.9.0"))
    }

    private fun submission(item: String, version: String, status: UiSubmissionStatus) =
        dev.ide.ui.backend.UiStoreSubmission(
            itemId = item,
            projectName = item,
            version = version,
            status = status,
        )
}

package dev.ide.store.impl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `store_review_queue`'s document, parsed.
 *
 * The fixture was **captured from the running backend** rather than written here, for the same reason the
 * Explore fixtures are: the shape being parsed is one a plpgsql function builds with `jsonb_build_object`,
 * and a hand-written sample only ever proves that the parser agrees with whoever wrote the sample. It
 * carries both halves — a pending submission with screenshots, a manifest and a proposed listing edit, and
 * a rejected one with a note — because they take different branches.
 *
 * What is worth pinning here is that the field NAMES survive. Every one of them is a key a definer
 * function emits, and this repository has twice lost a whole field set by re-declaring such a function
 * with a shorter list, silently, because each dropped key had a plausible default on the client.
 */
class ModerationQueueParserTest {

    private val document: Any? by lazy {
        // Read by path, like the Explore fixtures: they live in this module's test resources and are shared
        // with :ide-core, which reads them the same way.
        val file = File("src/test/resources/review-queue.json")
        assertTrue(file.isFile, "missing fixture: ${file.absolutePath}")
        dev.ide.platform.JsonReader.parse(file.readText())
    }

    private fun pending() = dev.ide.platform.JsonReader
        .arr(dev.ide.platform.JsonReader.obj(document)?.get("pending"))
        .mapNotNull(SupabaseModerationService::parseSubmission)

    private fun recent() = dev.ide.platform.JsonReader
        .arr(dev.ide.platform.JsonReader.obj(document)?.get("recent"))
        .mapNotNull(SupabaseModerationService::parseSubmission)

    @Test
    fun aPendingSubmissionCarriesEverythingADecisionNeeds() {
        val submission = pending().single { it.listing.slug == "valtest-app" }

        assertEquals("1.0.0", submission.version)
        assertEquals("pending", submission.status)
        assertEquals(1_000_000, submission.versionCode)
        // The storage path is the PRIVATE one while the version is pending, and approving is what replaces
        // it with a clean public key. A parser that dropped it would leave nothing to copy from.
        assertTrue(submission.storagePath.endsWith("/valtest-app/1.0.0.zip"))
        assertEquals(1234L, submission.sizeBytes)
        assertEquals("a".repeat(64), submission.sha256)
        assertEquals(2, submission.fileCount)
        assertEquals(listOf("a.kt", "README.md"), submission.files.map { it.path })
        assertEquals(listOf(10L, 40L), submission.files.map { it.sizeBytes })
        assertEquals("First release", submission.changelog)
        assertEquals(1, submission.screenshotPaths.size)
        // Both live in the PRIVATE bucket until approval copies them across, which is why the moderation
        // download exists at all: the anonymous media cache cannot read either.
        assertTrue(submission.screenshotPaths.single().endsWith("/1.0.0-shots/shot-0.png"))
        assertTrue(assertNotNull(submission.iconPath).endsWith("/1.0.0-icon/icon.png"))
        assertNotNull(submission.createdAt)
    }

    @Test
    fun theListingAndItsSubmitterComeWithIt() {
        val submission = pending().single { it.listing.slug == "valtest-app" }

        assertEquals("Val Test", submission.listing.title)
        assertEquals("A summary", submission.listing.summary)
        assertEquals("A description", submission.listing.description)
        assertEquals("pending", submission.listing.status)
        // The listing's CURRENT icon, which is what makes "this version ships none, clear it or keep it?"
        // a question the reviewer can be asked at all.
        assertEquals("old/icon.png", submission.listing.iconPath)

        val submitter = assertNotNull(submission.submitter)
        assertEquals("fixture-a", submitter.handle)
        assertEquals("Fixture A", submitter.displayName)
        assertTrue(submitter.verified)
        assertFalse(submitter.banned)
    }

    @Test
    fun aProposedListingEditIsFlattenedToStrings() {
        val submission = pending().single { it.listing.slug == "valtest-app" }

        assertEquals(
            mapOf("summary" to "A better summary", "tags" to "cli, tools, kotlin"),
            submission.listingPatch,
        )
    }

    @Test
    fun aKeyThatIsNeitherAStringNorAnArrayIsDroppedRatherThanStringified() {
        // Every key the column's CHECK allows is a string or an array of them, so anything else is a
        // client that has drifted. Rendering `[object]` at a reviewer would be worse than omitting it.
        val patch = SupabaseModerationService.parsePatch(
            dev.ide.platform.JsonReader.parse("""{"title":"New","installs":42,"tags":["a","b"]}"""),
        )

        assertEquals(mapOf("title" to "New", "tags" to "a, b"), patch)
    }

    @Test
    fun aDecidedSubmissionKeepsTheNoteItsPublisherWasSent() {
        val decided = recent().single()

        assertEquals("rejected", decided.status)
        assertEquals("Please add a README.", decided.reviewNote)
        assertNotNull(decided.reviewedAt)
    }

    @Test
    fun aRowWithNoListingIsDroppedRatherThanRenderedHalfBlank() {
        // A card with no slug cannot be acted on — every decision addresses the version, and every copy
        // addresses the listing's slug — so a gap is the honest outcome, not a blank card.
        assertNull(
            SupabaseModerationService.parseSubmission(
                dev.ide.platform.JsonReader.parse("""{"id":"abc","version":"1.0.0","status":"pending"}"""),
            ),
        )
        assertNull(
            SupabaseModerationService.parseSubmission(
                dev.ide.platform.JsonReader.parse("""{"version":"1.0.0","item":{"slug":"x"}}"""),
            ),
        )
    }

    @Test
    fun theReportQueueParsesItsSnakeCaseKeys() {
        // store_report_queue predates the camelCase convention the newer RPCs use: it returns row_to_json
        // of a query, so its keys are the column aliases. Reading it as camelCase would silently produce a
        // queue of blank cards, which is exactly the failure this pins.
        val report = assertNotNull(
            SupabaseModerationService.parseReport(
                dev.ide.platform.JsonReader.parse(
                    """{"id":"r1","reason":"spam","detail":"bot","item_slug":"a-app","item_title":"A App",
                       "is_item_report":false,"review_author":"u1","review_stars":1,
                       "review_text":"buy followers","review_status":"visible"}""",
                ),
            ),
        )

        assertEquals("r1", report.reportId)
        assertEquals("spam", report.reason)
        assertEquals("a-app", report.itemSlug)
        assertEquals("A App", report.itemTitle)
        assertFalse(report.isItemReport)
        assertEquals("u1", report.reviewAuthorId)
        assertEquals(1, report.reviewStars)
        assertEquals("visible", report.reviewStatus)
    }
}

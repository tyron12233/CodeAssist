package dev.ide.store.bridge

import dev.ide.store.PendingSubmission
import dev.ide.store.ReportedContent
import dev.ide.store.ReviewQueue
import dev.ide.store.StoreModerationService
import dev.ide.store.StoreResult
import dev.ide.store.SubmissionListing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moderation, engine-side.
 *
 * Two things are worth pinning. A decision has to reach the port carrying the WHOLE submission — the
 * screen hands back an id, and the port needs the storage path and the slug to copy an archive — so the
 * lookup between the two is the part that can silently break. And the proposed listing edits shown to a
 * reviewer must be the ones that actually change something: the publish form sends every field it
 * displayed, so most of a patch is the listing repeated back at itself.
 */
class StoreModerationTest {

    private fun listing(
        slug: String = "my-app-ab12",
        title: String = "My App",
        summary: String = "Does one thing",
        description: String = "The long version",
        category: String = "utilities",
        tags: List<String> = listOf("kotlin", "cli"),
    ) = SubmissionListing(
        slug = slug,
        title = title,
        summary = summary,
        description = description,
        category = category,
        tags = tags,
        status = "approved",
    )

    private fun submission(
        id: String = "v1",
        version: String = "1.0.1",
        status: String = "pending",
        patch: Map<String, String> = emptyMap(),
        listing: SubmissionListing = listing(),
    ) = PendingSubmission(
        versionId = id,
        version = version,
        status = status,
        storagePath = "uid/${listing.slug}/$version.zip",
        sizeBytes = 4096,
        sha256 = "a".repeat(64),
        fileCount = 12,
        listingPatch = patch,
        listing = listing,
    )

    private class FakeModeration(
        private val queue: StoreResult<ReviewQueue> = StoreResult.Ok(ReviewQueue()),
        private val decisionResult: StoreResult<String> = StoreResult.Ok("Approved My App 1.0.1"),
        private val available: Boolean = true,
        private val moderator: Boolean = true,
        private val reportRows: StoreResult<List<ReportedContent>> = StoreResult.Ok(emptyList()),
    ) : StoreModerationService {
        var approved: PendingSubmission? = null
        var approvedNote: String? = null
        var approvedClearIcon: Boolean? = null
        var rejected: Pair<PendingSubmission, String>? = null
        var resolved: Pair<String, Boolean>? = null

        override fun moderationAvailable() = available
        override fun amIModerator() = moderator
        override fun queue(limit: Int) = queue
        override fun approve(
            submission: PendingSubmission,
            note: String?,
            clearIconIfMissing: Boolean,
        ): StoreResult<String> {
            approved = submission
            approvedNote = note
            approvedClearIcon = clearIconIfMissing
            return decisionResult
        }
        override fun reject(submission: PendingSubmission, note: String): StoreResult<String> {
            rejected = submission to note
            return decisionResult
        }
        override fun reports(limit: Int) = reportRows
        override fun resolveReport(reportId: String, actioned: Boolean): StoreResult<Unit> {
            resolved = reportId to actioned
            return StoreResult.Ok(Unit)
        }
    }

    // ---- a decision reaches the port with the row behind it ----

    @Test
    fun approvingSendsTheWholeSubmissionTheQueueRead() {
        val row = submission()
        val port = FakeModeration(queue = StoreResult.Ok(ReviewQueue(pending = listOf(row))))
        val moderation = StoreModeration(port)
        moderation.queue()

        assertNull(moderation.approve("v1", note = null, clearIconIfMissing = false))

        // The id alone would not be enough: the port copies `storagePath` into `slug/version.zip`, and
        // neither of those travels with an id.
        assertEquals(row, port.approved)
        assertEquals(false, port.approvedClearIcon)
    }

    @Test
    fun theIconQuestionIsPassedThroughRatherThanDecidedHere() {
        val row = submission()
        val port = FakeModeration(queue = StoreResult.Ok(ReviewQueue(pending = listOf(row))))
        val moderation = StoreModeration(port)
        moderation.queue()

        moderation.approve("v1", note = null, clearIconIfMissing = true)

        assertEquals(true, port.approvedClearIcon)
    }

    @Test
    fun rejectingCarriesTheNote() {
        val row = submission()
        val port = FakeModeration(queue = StoreResult.Ok(ReviewQueue(pending = listOf(row))))
        val moderation = StoreModeration(port)
        moderation.queue()

        moderation.reject("v1", "Add a README first.")

        assertEquals(row to "Add a README first.", port.rejected)
    }

    @Test
    fun aDecisionOnARowThisQueueNeverSawSaysSoInsteadOfDoingNothing() {
        val port = FakeModeration()
        val moderation = StoreModeration(port)
        moderation.queue()

        val message = assertNotNull(moderation.approve("gone", null, false))

        assertTrue("Reload" in message, message)
        // Nothing reached the port: an id it cannot resolve must not become a decision about some other row.
        assertNull(port.approved)
    }

    @Test
    fun aRowFromTheRecentListIsStillResolvable() {
        // Deciding again on something already decided is how two moderators working the same queue collide,
        // and the useful answer is the backend's "was already approved" — which needs the row to get there.
        val done = submission(id = "v9", status = "approved")
        val port = FakeModeration(
            queue = StoreResult.Ok(ReviewQueue(recent = listOf(done))),
            decisionResult = StoreResult.Failed("My App 1.0.1 was already approved"),
        )
        val moderation = StoreModeration(port)
        moderation.queue()

        assertEquals("My App 1.0.1 was already approved", moderation.reject("v9", "too late"))
    }

    @Test
    fun aRefusalKeepsTheWordsTheBackendChose() {
        val row = submission()
        val port = FakeModeration(
            queue = StoreResult.Ok(ReviewQueue(pending = listOf(row))),
            decisionResult = StoreResult.Failed("The copied payload is not being served (HTTP 404)"),
        )
        val moderation = StoreModeration(port)
        moderation.queue()

        assertEquals(
            "The copied payload is not being served (HTTP 404)",
            moderation.approve("v1", null, false),
        )
    }

    @Test
    fun anUnreadableQueueBecomesAMessageRatherThanAnEmptyPage() {
        val moderation = StoreModeration(FakeModeration(queue = StoreResult.Unavailable("Network unavailable")))

        val queue = moderation.queue()

        assertEquals("Network unavailable", queue.error)
        assertTrue(queue.pending.isEmpty())
    }

    @Test
    fun aBuildWithNoModerationTransportNeverAsksTheNetwork() {
        val moderation = StoreModeration(StoreModerationService.Unsupported)

        assertFalse(moderation.available())
        assertFalse(moderation.isModerator())
        assertNotNull(moderation.queue().error)
    }

    // ---- what counts as a proposed change ----

    @Test
    fun onlyFieldsThatDifferFromTheLiveListingAreShown() {
        val edits = StoreModeration.editsAgainst(
            patch = mapOf(
                "title" to "My App",                 // unchanged
                "summary" to "Does one thing well",  // changed
                "description" to "The long version", // unchanged
            ),
            listing = listing(),
        )

        assertEquals(listOf("summary"), edits.map { it.field })
        assertEquals("Does one thing", edits.single().current)
        assertEquals("Does one thing well", edits.single().proposed)
    }

    @Test
    fun aBlankProposalIsNotADeletion() {
        // The backend reads an empty field as "leave it alone", never as "remove the summary", so drawing
        // it as a change would show a reviewer a deletion that approving would not perform.
        val edits = StoreModeration.editsAgainst(
            patch = mapOf("summary" to "   ", "description" to ""),
            listing = listing(),
        )

        assertTrue(edits.isEmpty())
    }

    @Test
    fun emptyTagsAreAChangeBecauseThatIsHowTagsAreRemoved() {
        // Tags are the exception: an empty array is applied as written, and a publisher taking their last
        // tag off has no other way to say it.
        val edits = StoreModeration.editsAgainst(patch = mapOf("tags" to ""), listing = listing())

        assertEquals(1, edits.size)
        assertEquals("tags", edits.single().field)
        assertEquals("kotlin, cli", edits.single().current)
        assertEquals("", edits.single().proposed)
    }

    @Test
    fun editsReadInFormOrderWhateverOrderTheyArrivedIn() {
        val edits = StoreModeration.editsAgainst(
            patch = linkedMapOf(
                "tags" to "kotlin",
                "description" to "Rewritten",
                "title" to "Renamed",
            ),
            listing = listing(),
        )

        assertEquals(listOf("title", "description", "tags"), edits.map { it.field })
    }

    @Test
    fun noPatchIsNoEdits() {
        assertTrue(StoreModeration.editsAgainst(emptyMap(), listing()).isEmpty())
    }

    // ---- timestamps ----

    @Test
    fun postgresTimestampsParse() {
        // Six fractional digits and a `+00:00` offset is what Postgres writes; `Instant.parse` takes it.
        assertTrue(StoreModeration.parseInstantMs("2026-09-14T00:10:12.629697+00:00") > 0)
    }

    @Test
    fun anUnparseableTimestampIsZeroRatherThanAThrow() {
        // A date is decoration on a queue card. A queue that will not load is not.
        assertEquals(0L, StoreModeration.parseInstantMs("not a date"))
        assertEquals(0L, StoreModeration.parseInstantMs(null))
    }

    // ---- reports ----

    @Test
    fun aHiddenReviewIsReportedAsHiddenSoTheActionReadsRestore() {
        val port = FakeModeration(
            reportRows = StoreResult.Ok(
                listOf(
                    ReportedContent(
                        reportId = "r1",
                        reason = "spam",
                        itemSlug = "my-app-ab12",
                        reviewAuthorId = "u1",
                        reviewStatus = "hidden",
                    ),
                ),
            ),
        )

        assertTrue(StoreModeration(port).reports().single().reviewHidden)
    }

    @Test
    fun resolvingAReportSaysWhetherAnythingWasDone() {
        val port = FakeModeration()
        StoreModeration(port).resolveReport("r1", actioned = true)

        assertEquals("r1" to true, port.resolved)
    }
}

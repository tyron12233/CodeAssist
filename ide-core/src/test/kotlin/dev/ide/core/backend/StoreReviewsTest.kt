package dev.ide.core.backend

import dev.ide.store.RemoteReview
import dev.ide.store.RemoteReviewPage
import dev.ide.store.ReviewSort
import dev.ide.store.StoreResult
import dev.ide.store.StoreReviewService
import dev.ide.ui.backend.UiReviewSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review mapping, engine-side.
 *
 * The parts worth pinning are the ones the UI cannot recover from: a timestamp it cannot parse (it formats
 * "3 d ago" and has no parser), and a failure that must arrive as text on the page rather than as an empty
 * panel, because "no reviews" and "could not load reviews" are opposite claims.
 */
class StoreReviewsTest {

    private class FakeReviews(
        private val page: StoreResult<RemoteReviewPage> = StoreResult.Ok(RemoteReviewPage()),
        private val write: StoreResult<Unit> = StoreResult.Ok(Unit),
        private val available: Boolean = true,
    ) : StoreReviewService {
        var lastSort: ReviewSort? = null
        var lastRate: List<Any?>? = null
        var lastVote: Triple<String, String, Boolean>? = null

        override fun reviewsAvailable() = available
        override fun reviews(itemSlug: String, sort: ReviewSort, limit: Int, offset: Int): StoreResult<RemoteReviewPage> {
            lastSort = sort
            return page
        }
        override fun rate(itemSlug: String, stars: Int, review: String?, appVersion: String?, itemVersion: String?): StoreResult<Unit> {
            lastRate = listOf(itemSlug, stars, review, appVersion, itemVersion)
            return write
        }
        override fun deleteMyReview(itemSlug: String) = write
        override fun vote(itemSlug: String, authorId: String, helpful: Boolean): StoreResult<Unit> {
            lastVote = Triple(itemSlug, authorId, helpful)
            return write
        }
    }

    private fun review(
        author: String = "u1",
        stars: Int = 5,
        created: String? = "2026-09-01T10:15:30.123456+00:00",
        updated: String? = null,
    ) = RemoteReview(
        authorId = author, stars = stars, review = "Solid", helpful = 3,
        createdAt = created, updatedAt = updated,
    )

    @Test
    fun mapsThePageThrough() {
        val page = RemoteReviewPage(
            average = 4.5f,
            count = 2,
            distribution = mapOf(5 to 1, 4 to 1),
            mine = review(author = "me").copy(mine = true),
            reviews = listOf(review(author = "other")),
        )
        val ui = StoreReviews(FakeReviews(StoreResult.Ok(page))).page("slug", UiReviewSort.HELPFUL, 20)

        assertEquals(4.5f, ui.average)
        assertEquals(2, ui.count)
        assertEquals(mapOf(5 to 1, 4 to 1), ui.distribution)
        assertEquals("me", ui.mine?.authorId)
        assertTrue(ui.mine!!.mine)
        assertEquals(listOf("other"), ui.reviews.map { it.authorId })
        assertTrue(ui.hasAny)
    }

    /** The UI formats relative times and cannot parse a timestamp, so this conversion is load-bearing. */
    @Test
    fun parsesPostgresTimestamps() {
        fun postedAt(created: String?, updated: String? = null): Long =
            StoreReviews(FakeReviews(StoreResult.Ok(RemoteReviewPage(reviews = listOf(review(created = created, updated = updated))))))
                .page("s", UiReviewSort.HELPFUL, 20).reviews.single().postedAtMs

        // PostgREST's usual shape, with an offset and fractional seconds.
        assertTrue(postedAt("2026-09-01T10:15:30.123456+00:00") > 0L)
        // A plain instant.
        assertTrue(postedAt("2026-09-01T10:15:30Z") > 0L)
        // A space instead of the T, which older rows can carry.
        assertTrue(postedAt("2026-09-01 10:15:30") > 0L)
        // Unparseable is 0, not a crash and not "now" — the UI then shows no age at all.
        assertEquals(0L, postedAt("not a date"))
        assertEquals(0L, postedAt(null))
    }

    /** An edit is what a reader cares about, so the newer stamp wins. */
    @Test
    fun anEditedReviewShowsItsEditTime() {
        val ui = StoreReviews(
            FakeReviews(
                StoreResult.Ok(
                    RemoteReviewPage(
                        reviews = listOf(
                            review(created = "2026-09-01T10:00:00Z", updated = "2026-09-02T12:00:00Z"),
                        ),
                    ),
                ),
            ),
        ).page("s", UiReviewSort.HELPFUL, 20)
        val expected = java.time.Instant.parse("2026-09-02T12:00:00Z").toEpochMilli()
        assertEquals(expected, ui.reviews.single().postedAtMs)
    }

    /** "Could not load" must not render as "no reviews". */
    @Test
    fun aFailureIsCarriedOnThePageNotSwallowed() {
        val offline = StoreReviews(FakeReviews(StoreResult.Unavailable("No connection")))
            .page("s", UiReviewSort.HELPFUL, 20)
        assertEquals("No connection", offline.error)
        assertFalse(offline.hasAny)

        val refused = StoreReviews(FakeReviews(StoreResult.Failed("Store rejected the request")))
            .page("s", UiReviewSort.HELPFUL, 20)
        assertEquals("Store rejected the request", refused.error)
    }

    @Test
    fun sortIsPassedThrough() {
        val fake = FakeReviews()
        StoreReviews(fake).page("s", UiReviewSort.RECENT, 20)
        assertEquals(ReviewSort.RECENT, fake.lastSort)
        StoreReviews(fake).page("s", UiReviewSort.HELPFUL, 20)
        assertEquals(ReviewSort.HELPFUL, fake.lastSort)
    }

    @Test
    fun writesReportNullOnSuccessAndTheBackendsWordsOnFailure() {
        assertNull(StoreReviews(FakeReviews()).rate("s", 5, "good", "84", "1.0.0"))
        assertEquals(
            "Sign in to do that",
            StoreReviews(FakeReviews(write = StoreResult.Failed("Sign in to do that"))).rate("s", 5, null, null, null),
        )
        assertEquals(
            "No connection",
            StoreReviews(FakeReviews(write = StoreResult.Unavailable("No connection"))).vote("s", "u1", true),
        )
    }

    /** The version context a reviewer never types but a publisher wants. */
    @Test
    fun rateForwardsTheAppAndItemVersions() {
        val fake = FakeReviews()
        StoreReviews(fake).rate("my-app", 4, "text", appVersion = "84", itemVersion = "2.1.0")
        assertEquals(listOf("my-app", 4, "text", "84", "2.1.0"), fake.lastRate)
    }

    @Test
    fun voteForwardsTheReviewIdentity() {
        val fake = FakeReviews()
        StoreReviews(fake).vote("my-app", "author-7", helpful = true)
        assertEquals(Triple("my-app", "author-7", true), fake.lastVote)
    }

    @Test
    fun anUnsupportedServiceIsUnavailableRatherThanEmpty() {
        val subject = StoreReviews(StoreReviewService.Unsupported)
        assertFalse(subject.available())
        val page = subject.page("s", UiReviewSort.HELPFUL, 20)
        assertNotNull(page.error, "an unsupported service must explain itself, not look like an empty list")
        assertFalse(subject.deleteMine("s"))
    }
}

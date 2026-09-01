package dev.ide.core.backend

import dev.ide.store.RemoteReview
import dev.ide.store.ReviewSort
import dev.ide.store.StoreResult
import dev.ide.store.StoreReviewService
import dev.ide.ui.backend.UiReviewPage
import dev.ide.ui.backend.UiReviewSort
import dev.ide.ui.backend.UiStoreReview

/**
 * Ratings and reviews, engine-side.
 *
 * Its own class for the same reason as [StoreAccounts] and [StoreSubmissions]: it needs the review port
 * and nothing else from the IDE, so the mapping and the failure handling are testable without a project.
 *
 * The interesting work here is turning the backend's timestamps into something the UI can format and
 * keeping refusals readable. A failure is carried on the page rather than thrown, because the panel has to
 * render either way: an item with reviews it cannot fetch should say so, not disappear.
 */
internal class StoreReviews(private val reviews: StoreReviewService) {

    fun available(): Boolean = reviews.reviewsAvailable()

    fun page(itemId: String, sort: UiReviewSort, limit: Int): UiReviewPage =
        when (val result = reviews.reviews(itemId, sort.toWire(), limit)) {
            is StoreResult.Ok -> UiReviewPage(
                average = result.value.average,
                count = result.value.count,
                distribution = result.value.distribution,
                mine = result.value.mine?.toUi(),
                reviews = result.value.reviews.map { it.toUi() },
            )
            // Offline is not an error worth shouting about, but the panel still has to say why it is empty.
            is StoreResult.Unavailable -> UiReviewPage(error = result.reason)
            is StoreResult.Failed -> UiReviewPage(error = result.message)
        }

    /** Null on success, otherwise the message to show. */
    fun rate(itemId: String, stars: Int, review: String?, appVersion: String?, itemVersion: String?): String? =
        when (val result = reviews.rate(itemId, stars, review, appVersion, itemVersion)) {
            is StoreResult.Ok -> null
            is StoreResult.Unavailable -> result.reason
            is StoreResult.Failed -> result.message
        }

    fun deleteMine(itemId: String): Boolean = reviews.deleteMyReview(itemId) is StoreResult.Ok

    fun vote(itemId: String, authorId: String, helpful: Boolean): String? =
        when (val result = reviews.vote(itemId, authorId, helpful)) {
            is StoreResult.Ok -> null
            is StoreResult.Unavailable -> result.reason
            is StoreResult.Failed -> result.message
        }

    private fun UiReviewSort.toWire(): ReviewSort = when (this) {
        UiReviewSort.HELPFUL -> ReviewSort.HELPFUL
        UiReviewSort.RECENT -> ReviewSort.RECENT
    }

    private fun RemoteReview.toUi() = UiStoreReview(
        authorId = authorId,
        authorName = authorName,
        authorHandle = authorHandle,
        verified = verified,
        stars = stars,
        review = review,
        helpful = helpful,
        votedByMe = votedByMe,
        appVersion = appVersion,
        itemVersion = itemVersion,
        // The UI formats "3 days ago" and cannot parse a timestamp, so the epoch conversion happens here.
        // An edit is what the reader cares about, so updatedAt wins when it is present.
        postedAtMs = epochOf(updatedAt ?: createdAt),
        reply = reply,
        mine = mine,
    )

    private companion object {
        /**
         * Parse a Postgres timestamptz into epoch millis, 0 when it cannot be read.
         *
         * PostgREST renders `timestamptz` as ISO-8601 with an offset, but the fractional-second digits vary
         * and older rows can arrive without an offset at all, so this tries the strict parse first and
         * falls back rather than throwing on a row it could otherwise show.
         */
        fun epochOf(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            runCatching { return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            runCatching { return java.time.Instant.parse(text).toEpochMilli() }
            runCatching {
                return java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }
            return 0L
        }
    }
}

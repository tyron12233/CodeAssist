package dev.ide.store

/**
 * Ratings and reviews.
 *
 * Reading is anonymous, like the rest of the catalog; writing needs a session. That asymmetry is why this
 * is one port rather than two: the reviews panel is drawn from a single read whether or not anyone is
 * signed in, and the sign-in prompt appears when someone tries to write.
 */

/** How a review list is ordered. */
enum class ReviewSort(val wire: String) {
    /** Most-voted first. The default, because it puts the review people found useful at the top. */
    HELPFUL("helpful"),

    /** Newest first, which is what someone checking a recent version wants. */
    RECENT("recent"),
}

/**
 * One review.
 *
 * [authorName] is null for a reviewer who has never published anything: the display name lives on the
 * publisher row, and inventing one from an email or a uuid would be worse than saying nothing. The UI
 * shows a neutral label in that case.
 *
 * [mine] marks the caller's own review, which the backend returns separately so the UI can pin it above
 * the list instead of hunting for it.
 */
data class RemoteReview(
    val authorId: String,
    val authorName: String? = null,
    val authorHandle: String? = null,
    val verified: Boolean = false,
    val stars: Int,
    val review: String? = null,
    val helpful: Int = 0,
    val votedByMe: Boolean = false,
    val appVersion: String? = null,
    val itemVersion: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** The publisher's reply, if they answered this review. */
    val reply: String? = null,
    val mine: Boolean = false,
)

/**
 * Everything the reviews panel draws, from one call.
 *
 * [average] and [count] are recomputed from the rows here rather than read off the item, so the headline
 * figure always agrees with the list under it. The item's own denormalized columns exist for ranking,
 * where being a moment out of date is harmless.
 */
data class RemoteReviewPage(
    val average: Float = -1f,
    val count: Int = 0,
    /** Stars (1..5) to how many reviews gave them. Absent keys mean zero. */
    val distribution: Map<Int, Int> = emptyMap(),
    val mine: RemoteReview? = null,
    val reviews: List<RemoteReview> = emptyList(),
)

interface StoreReviewService {
    fun reviewsAvailable(): Boolean = false

    fun reviews(
        itemSlug: String,
        sort: ReviewSort = ReviewSort.HELPFUL,
        limit: Int = 20,
        offset: Int = 0,
    ): StoreResult<RemoteReviewPage> = StoreResult.Unavailable("No store endpoint")

    /**
     * Leave or replace the caller's review.
     *
     * One review per account per project, so this is an upsert: editing a review is the same act as
     * writing one, and a second review from the same person would be a way to shout.
     */
    fun rate(
        itemSlug: String,
        stars: Int,
        review: String? = null,
        appVersion: String? = null,
        itemVersion: String? = null,
    ): StoreResult<Unit> = StoreResult.Unavailable("No store endpoint")

    fun deleteMyReview(itemSlug: String): StoreResult<Unit> = StoreResult.Unavailable("No store endpoint")

    /** Mark a review useful, or take it back. [authorId] identifies the review; ids come from [reviews]. */
    fun vote(itemSlug: String, authorId: String, helpful: Boolean): StoreResult<Unit> =
        StoreResult.Unavailable("No store endpoint")

    companion object {
        val Unsupported: StoreReviewService = object : StoreReviewService {}
    }
}

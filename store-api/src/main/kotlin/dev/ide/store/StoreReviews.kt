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
    /**
     * Whether the caller is this project's publisher, and so may answer its reviews.
     *
     * Decided by the backend, not guessed here: a reply button that appears for everyone and fails for
     * almost everyone is worse than no button.
     */
    val canReply: Boolean = false,
    /** Whether the caller is a moderator, and so may hide a review. */
    val canModerate: Boolean = false,
)

/** Why something was reported. The set the backend accepts; anything else is refused. */
enum class ReportReason(val wire: String) {
    MALWARE("malware"),
    SPAM("spam"),
    COPYRIGHT("copyright"),
    INAPPROPRIATE("inappropriate"),
    BROKEN("broken"),
    OTHER("other"),
}

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

    /**
     * Answer a review as the project's publisher. One reply per review, so this edits an existing one.
     *
     * Refused for anyone else, with a message saying so rather than a permission error, because the
     * affordance can outlive the state that justified it.
     */
    fun reply(itemSlug: String, authorId: String, body: String): StoreResult<Unit> =
        StoreResult.Unavailable("No store endpoint")

    fun deleteReply(itemSlug: String, authorId: String): StoreResult<Unit> =
        StoreResult.Unavailable("No store endpoint")

    /**
     * Flag a review for a moderator.
     *
     * Reporting twice is the same report and reports the same thing, so a repeat succeeds quietly. The
     * reporter can never read the queue back, which is why nothing here returns its state.
     */
    fun report(
        itemSlug: String,
        authorId: String,
        reason: ReportReason,
        detail: String? = null,
    ): StoreResult<Unit> = StoreResult.Unavailable("No store endpoint")

    /** Flag a whole project rather than one of its reviews. */
    fun reportItem(
        itemSlug: String,
        reason: ReportReason,
        detail: String? = null,
    ): StoreResult<Unit> = StoreResult.Unavailable("No store endpoint")

    /** Hide or restore a review. Moderators only; the backend refuses anyone else. */
    fun setReviewHidden(itemSlug: String, authorId: String, hidden: Boolean): StoreResult<Unit> =
        StoreResult.Unavailable("No store endpoint")

    companion object {
        val Unsupported: StoreReviewService = object : StoreReviewService {}
    }
}

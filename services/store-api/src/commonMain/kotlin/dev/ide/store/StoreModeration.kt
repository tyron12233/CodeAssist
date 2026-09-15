package dev.ide.store

/**
 * Moderation: the review queue, a decision on a submission, and the report queue.
 *
 * Its own port rather than more methods on [StoreSubmissionService], because the two are opposite sides of
 * the same table and only one of them is for everybody. Publishing is something any signed-in account
 * does; moderating is something an account in `store_admins` does, and a host that wires the store still
 * ships to a thousand devices where [available] is false.
 *
 * **The authority is the database, never this.** Every function behind this port re-checks
 * `store_is_admin()` in its own body and raises for anyone else, so [amIModerator] is not a permission
 * check — it is what stops a non-moderator being shown a screen whose every button would fail.
 */

/** Who sent a submission, as the queue shows them. */
data class SubmissionSubmitter(
    val userId: String,
    val handle: String? = null,
    val displayName: String? = null,
    val verified: Boolean = false,
    /**
     * Whether this publisher is banned.
     *
     * Their pending uploads still reach the queue: a ban takes their published work down, and what is
     * waiting for review is exactly what a moderator has to be able to refuse.
     */
    val banned: Boolean = false,
)

/** One file inside a submitted archive, from the manifest the submitter's IDE recorded. */
data class SubmissionFile(val path: String, val sizeBytes: Long)

/**
 * The listing a submission belongs to, as the store has it NOW.
 *
 * The proposed changes are in [PendingSubmission.listingPatch], and the reviewer is shown both: approving
 * applies the patch, so this is the only moment anyone sees the words side by side before they are public.
 */
data class SubmissionListing(
    val slug: String,
    val title: String,
    val summary: String = "",
    val description: String = "",
    val category: String = "",
    val language: String? = null,
    val tags: List<String> = emptyList(),
    /** `pending`, `approved`, `rejected` or `unpublished`. */
    val status: String = "",
    /** The listing's published icon in the public media bucket, or null while it has none. */
    val iconPath: String? = null,
    /** The listing's published screenshots, in the public media bucket. */
    val screenshots: List<String> = emptyList(),
    val installs: Int = 0,
)

/**
 * One version waiting for a decision, or one recently decided.
 *
 * [storagePath] addresses the PRIVATE uploads bucket while the version is pending, and the public
 * payloads bucket once it is approved. Which bucket a reader wants is therefore a function of [status],
 * not a second field: see [StoreModerationService.payloadUrl].
 */
data class PendingSubmission(
    val versionId: String,
    val version: String,
    val versionCode: Int = 0,
    /** `pending`, `approved`, `rejected` or `withdrawn`. */
    val status: String,
    val storagePath: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val fileCount: Int = 0,
    val files: List<SubmissionFile> = emptyList(),
    val changelog: String? = null,
    val createdAt: String? = null,
    val reviewedAt: String? = null,
    /** The note a moderator left, which the submitter is shown. */
    val reviewNote: String? = null,
    /** Screenshots submitted with this version, in the PRIVATE bucket until it is approved. */
    val screenshotPaths: List<String> = emptyList(),
    /** The app icon submitted with this version, in the private bucket. Null when it shipped none. */
    val iconPath: String? = null,
    /**
     * The listing text this version proposes, by field name (`title`, `summary`, `description`,
     * `category`, `tags`). Empty when it proposes no change, which is every version from a client that
     * predates listing edits.
     */
    val listingPatch: Map<String, String> = emptyMap(),
    val submitter: SubmissionSubmitter? = null,
    val listing: SubmissionListing,
)

/** The review queue as one read: what is waiting, and the last few decisions. */
data class ReviewQueue(
    val pending: List<PendingSubmission> = emptyList(),
    val recent: List<PendingSubmission> = emptyList(),
    /** The whole queue's size, which is not `pending.size` when the read was limited. */
    val pendingCount: Int = 0,
)

/** Why something was reported, and what it was. */
data class ReportedContent(
    val reportId: String,
    val reason: String,
    /** What the reporter typed, when they typed anything. */
    val detail: String? = null,
    val createdAt: String? = null,
    val itemSlug: String? = null,
    val itemTitle: String? = null,
    /** True when the whole project was flagged; false when one review was. */
    val isItemReport: Boolean = false,
    /** The flagged review's author, for a review report. Identifies the review together with the slug. */
    val reviewAuthorId: String? = null,
    val reviewStars: Int = 0,
    val reviewText: String? = null,
    /** `visible` or `hidden`, so the action can say Hide or Restore. */
    val reviewStatus: String? = null,
)

/**
 * The moderation port.
 *
 * Reads and writes both need the moderator's session, so an implementation holds the account service the
 * same way [StoreSubmissionService] does.
 */
interface StoreModerationService {
    /** Whether this build has a moderation transport at all. False hides every moderation surface. */
    fun moderationAvailable(): Boolean = false

    /**
     * Whether the signed-in account is a moderator.
     *
     * False signed out, and false for a signed-in account that is not in `store_admins`. A network failure
     * is also false: not knowing is not a reason to show the screen, and the buttons would fail anyway.
     */
    fun amIModerator(): Boolean = false

    /** What is waiting, oldest first, and the last few decisions. */
    fun queue(limit: Int = 50): StoreResult<ReviewQueue> =
        StoreResult.Unavailable("Moderation is not available in this build")

    /**
     * Approve [submission] and publish it.
     *
     * Two operations in one call, in this order and no other. The archive is copied from the private
     * uploads bucket to a clean `{slug}/{version}.zip` in the public one and checked to be served, and only
     * then is the decision recorded — which repoints the row at the public path and flips its status in a
     * single statement. Flipping first would publish an item whose payload 404s on every install, and
     * would leave the submitter's uuid in a public URL.
     *
     * The screenshots and the app icon travel the same private-to-public route, and a version that ships
     * none leaves the listing's alone unless [clearIconIfMissing] says the publisher removed theirs.
     *
     * [note] is optional here: an approval that needs saying something is rare, and a rejection is where a
     * note is mandatory.
     */
    fun approve(
        submission: PendingSubmission,
        note: String? = null,
        clearIconIfMissing: Boolean = false,
    ): StoreResult<String> = StoreResult.Unavailable("Moderation is not available in this build")

    /**
     * Refuse [submission], with the reason the submitter is sent.
     *
     * The note is required, by this port and by the database: a rejection with no reason is a dead end for
     * whoever receives it, and the upload stays where it is so the record survives the decision.
     */
    fun reject(submission: PendingSubmission, note: String): StoreResult<String> =
        StoreResult.Unavailable("Moderation is not available in this build")

    /** Open reports, oldest first. */
    fun reports(limit: Int = 50): StoreResult<List<ReportedContent>> =
        StoreResult.Unavailable("Moderation is not available in this build")

    /** Hide a flagged review, or put it back. The rating average follows automatically. */
    fun setReviewHidden(itemSlug: String, authorId: String, hidden: Boolean): StoreResult<Unit> =
        StoreResult.Unavailable("Moderation is not available in this build")

    /**
     * Close a report. [actioned] when something was done about it, false when nothing needed doing.
     *
     * Separate from hiding a review on purpose: a moderator may hide one and still want the flag in the
     * queue while they decide about the account behind it.
     */
    fun resolveReport(reportId: String, actioned: Boolean): StoreResult<Unit> =
        StoreResult.Unavailable("Moderation is not available in this build")

    /**
     * Where [submission]'s archive can be fetched from, or null when this build cannot say.
     *
     * A pending archive is in a private bucket and needs the moderator's session, which is why this is a
     * URL and not a promise that anyone can open it.
     */
    fun payloadUrl(submission: PendingSubmission): String? = null

    /**
     * Download one object the moderator may read, to [intoPath].
     *
     * Serves both buckets: a pending submission's screenshots are private, and its archive is too. The
     * catalog source's own download cannot be reused because that one is anonymous and public-bucket only.
     */
    fun downloadObject(bucket: String, storagePath: String, intoPath: String): StoreResult<Unit> =
        StoreResult.Unavailable("Moderation is not available in this build")

    companion object {
        val Unsupported: StoreModerationService = object : StoreModerationService {}
    }
}

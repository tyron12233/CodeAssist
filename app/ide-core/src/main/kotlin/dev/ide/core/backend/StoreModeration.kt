package dev.ide.core.backend

import dev.ide.store.PendingSubmission
import dev.ide.store.StoreModerationService
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiListingEdit
import dev.ide.ui.backend.UiModerationQueue
import dev.ide.ui.backend.UiPendingSubmission
import dev.ide.ui.backend.UiReportedContent
import dev.ide.ui.backend.UiSubmissionFile
import dev.ide.ui.backend.UiSubmissionListing
import dev.ide.ui.backend.UiSubmissionSubmitter

/**
 * Moderation, engine-side.
 *
 * Its own class for the same reason as [StoreAccounts] and [StoreSubmissions]: it depends on the
 * moderation port and nothing else in the IDE, so what is worth testing — which proposed edits are real
 * changes, how a refusal is reported, what a version id resolves to — is testable with no project, engine
 * or host.
 *
 * It holds the queue it last read, because a decision is taken against a version the screen is looking at
 * and the port needs the whole submission (its storage path, its screenshots, its slug) to act on it. The
 * UI hands back an id; resolving that id to the row it came from is this class's job, and a stale id is a
 * "reload the queue" message rather than a null nobody can interpret.
 */
internal class StoreModeration(private val moderation: StoreModerationService) {

    fun available(): Boolean = moderation.moderationAvailable()

    /**
     * Whether the signed-in account moderates.
     *
     * Read during composition, so it must not go to the network on the calling thread: the port caches the
     * answer per account and only the first call after a sign-in costs anything. That first call is made
     * from the profile read, which is already off the main thread.
     */
    fun isModerator(): Boolean = available() && moderation.amIModerator()

    /** The last queue read, so a decision can find the row behind an id the UI handed back. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, PendingSubmission>()

    fun queue(): UiModerationQueue {
        if (!available()) return UiModerationQueue(error = "Moderation is not available in this build")
        return when (val result = moderation.queue()) {
            is StoreResult.Ok -> {
                // Both lists, because a decision can be taken on a row from either: `recent` is where a
                // moderator looks when they want to see what they just did.
                (result.value.pending + result.value.recent).forEach { known[it.versionId] = it }
                UiModerationQueue(
                    pending = result.value.pending.map { it.toUi() },
                    recent = result.value.recent.map { it.toUi() },
                    pendingCount = result.value.pendingCount,
                )
            }
            is StoreResult.Unavailable -> UiModerationQueue(error = result.reason)
            is StoreResult.Failed -> UiModerationQueue(error = result.message)
        }
    }

    /** Null when it went; otherwise the sentence to show, written by the backend for a person to read. */
    fun approve(versionId: String, note: String?, clearIconIfMissing: Boolean): String? {
        val submission = known[versionId] ?: return STALE
        return message(moderation.approve(submission, note, clearIconIfMissing))
    }

    fun reject(versionId: String, note: String): String? {
        val submission = known[versionId] ?: return STALE
        return message(moderation.reject(submission, note))
    }

    fun reports(): List<UiReportedContent> =
        when (val result = moderation.reports()) {
            is StoreResult.Ok -> result.value.map {
                UiReportedContent(
                    reportId = it.reportId,
                    reason = it.reason,
                    detail = it.detail,
                    itemSlug = it.itemSlug,
                    itemTitle = it.itemTitle,
                    isItemReport = it.isItemReport,
                    reviewAuthorId = it.reviewAuthorId,
                    reviewStars = it.reviewStars,
                    reviewText = it.reviewText,
                    reviewHidden = it.reviewStatus == "hidden",
                )
            }
            else -> emptyList()
        }

    fun resolveReport(reportId: String, actioned: Boolean): String? =
        message(moderation.resolveReport(reportId, actioned))

    fun setReviewHidden(itemSlug: String, authorId: String, hidden: Boolean): String? =
        message(moderation.setReviewHidden(itemSlug, authorId, hidden))

    /** Fetch a private submission image into [into]. The caller owns the cache path and its naming. */
    fun downloadSubmissionImage(storagePath: String, into: java.io.File): Boolean =
        moderation.downloadObject("store-uploads", storagePath, into) is StoreResult.Ok

    private fun <T> message(result: StoreResult<T>): String? = when (result) {
        is StoreResult.Ok -> null
        is StoreResult.Unavailable -> result.reason
        is StoreResult.Failed -> result.message
    }

    private fun PendingSubmission.toUi() = UiPendingSubmission(
        versionId = versionId,
        version = version,
        status = status,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        fileCount = fileCount.takeIf { it > 0 } ?: files.size,
        files = files.map { UiSubmissionFile(it.path, it.sizeBytes) },
        changelog = changelog,
        submittedAtMs = parseInstantMs(createdAt),
        reviewNote = reviewNote,
        screenshotPaths = screenshotPaths,
        iconPath = iconPath,
        edits = editsAgainst(listingPatch, listing),
        submitter = submitter?.let {
            UiSubmissionSubmitter(
                userId = it.userId,
                handle = it.handle,
                displayName = it.displayName,
                verified = it.verified,
                banned = it.banned,
            )
        },
        listing = UiSubmissionListing(
            slug = listing.slug,
            title = listing.title,
            summary = listing.summary,
            description = listing.description,
            category = listing.category,
            language = listing.language,
            tags = listing.tags,
            status = listing.status,
            iconPath = listing.iconPath,
            screenshots = listing.screenshots,
            installs = listing.installs,
        ),
    )

    internal companion object {
        private const val STALE =
            "That submission is no longer in the queue you are looking at. Reload and try again."

        /**
         * The proposed edits that are actually changes.
         *
         * A patch may repeat what the listing already says — the publish form sends every field it showed,
         * and most of them come back unchanged — and a reviewer reading the listing back to itself learns
         * nothing. A blank proposal is not a change either: the backend reads an empty field as "leave it
         * alone", never as "remove the summary", so showing it as a deletion would misrepresent it.
         *
         * Ordered as the form presents them rather than by whatever order the JSON arrived in, so two
         * submissions of the same project read the same way.
         */
        internal fun editsAgainst(
            patch: Map<String, String>,
            listing: dev.ide.store.SubmissionListing,
        ): List<UiListingEdit> {
            if (patch.isEmpty()) return emptyList()
            val current = mapOf(
                "title" to listing.title,
                "summary" to listing.summary,
                "description" to listing.description,
                "category" to listing.category,
                "tags" to listing.tags.joinToString(", "),
            )
            return listOf("title", "summary", "description", "category", "tags").mapNotNull { field ->
                val proposed = patch[field]?.trim() ?: return@mapNotNull null
                val now = current[field]?.trim().orEmpty()
                // Tags are the one field whose empty proposal IS a change: a publisher removing their last
                // tag has nothing else to say it with, and the backend applies an empty array as written.
                if (proposed.isEmpty() && field != "tags") return@mapNotNull null
                if (proposed == now) return@mapNotNull null
                UiListingEdit(field, now, proposed)
            }
        }

        /**
         * An ISO-8601 timestamp as epoch millis, or 0.
         *
         * Postgres writes `2026-09-14T04:11:07.481123+00:00`, whose fractional second has six digits;
         * `Instant.parse` takes that. A value it cannot read is 0 rather than an exception, because a
         * timestamp is decoration on a queue card and a queue that will not load is not.
         */
        internal fun parseInstantMs(iso: String?): Long =
            iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }
}

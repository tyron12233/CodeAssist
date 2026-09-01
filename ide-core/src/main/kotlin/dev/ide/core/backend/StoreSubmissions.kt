package dev.ide.core.backend

import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import dev.ide.store.StoreSubmissionRequest
import dev.ide.store.StoreSubmissionService
import dev.ide.ui.backend.UiPackagedProject
import dev.ide.ui.backend.UiStoreSubmission
import dev.ide.ui.backend.UiSubmissionDraft
import dev.ide.ui.backend.UiSubmissionStatus
import dev.ide.ui.backend.UiSubmitResult

/**
 * Packaging and publishing, engine-side.
 *
 * Its own class for the same reason as [StoreAccounts] and [StoreInstaller]: it depends on the submission
 * port and nothing else in the IDE, so the interesting parts (what a draft turns into, how a refusal is
 * reported, what the status strings mean) are testable without a project or a host.
 *
 * Packaging and uploading stay separate calls. The submit screen shows what packaging produced, including
 * what it excluded, and only then uploads: the archive becomes public, so the user needs to see what is in
 * it before committing rather than being told afterwards.
 */
internal class StoreSubmissions(private val submissions: StoreSubmissionService) {

    fun available(): Boolean = submissions.submissionsAvailable()

    /**
     * The real packaged archives, by project root.
     *
     * The UI gets a summary DTO, not this: the manifest is a list of every file with its size, which the
     * screen has no use for and which cannot survive the round trip (rebuilding a [PackagedProject] from
     * the summary would upload `file_count: 0` and an empty manifest, and the database's CHECK on
     * `file_count` would reject it). So the engine keeps the real one and the UI hands back a root path.
     */
    private val packed = java.util.concurrent.ConcurrentHashMap<String, PackagedProject>()

    /** The last packaging failure per project, for the screen to show. */
    private val packErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun pack(rootPath: String): UiPackagedProject? =
        when (val result = submissions.pack(rootPath)) {
            is StoreResult.Ok -> {
                packed[rootPath] = result.value
                packErrors.remove(rootPath)
                result.value.toUi(rootPath)
            }
            // Local and specific ("every file was excluded", "too large"), so the reason is kept rather
            // than discarded: the screen has nothing else to explain the empty result with.
            is StoreResult.Unavailable -> { packErrors[rootPath] = result.reason; null }
            is StoreResult.Failed -> { packErrors[rootPath] = result.message; null }
        }

    /** Why the last [pack] of [rootPath] failed, or null if it succeeded or was never tried. */
    fun packError(rootPath: String): String? = packErrors[rootPath]

    fun submit(draft: UiSubmissionDraft, packaged: UiPackagedProject): UiSubmitResult {
        val request = StoreSubmissionRequest(
            itemSlug = draft.itemSlug,
            title = draft.title.trim(),
            summary = draft.summary.trim(),
            description = draft.description.trim(),
            category = draft.category.trim(),
            language = draft.language?.trim()?.takeIf { it.isNotEmpty() },
            tags = draft.tags.map { it.trim() }.filter { it.isNotEmpty() },
            version = draft.version.trim().ifEmpty { "1.0.0" },
            changelog = draft.changelog?.trim()?.takeIf { it.isNotEmpty() },
        )
        // The archive the screen showed, not a reconstruction of it: same bytes, same manifest, same hash.
        // Re-packing if it is missing keeps a submit working after the engine was rebuilt underneath the
        // screen, at the cost of a second zip.
        val archive = packed[packaged.rootPath]
            ?: when (val again = submissions.pack(packaged.rootPath)) {
                is StoreResult.Ok -> again.value.also { packed[packaged.rootPath] = it }
                is StoreResult.Unavailable -> return UiSubmitResult(false, again.reason)
                is StoreResult.Failed -> return UiSubmitResult(false, again.message)
            }
        return when (val result = submissions.submit(request, archive)) {
            is StoreResult.Ok -> UiSubmitResult(
                success = true,
                message = "Submitted for review",
                submission = UiStoreSubmission(
                    itemId = result.value.itemSlug,
                    projectName = request.title,
                    version = result.value.version,
                    status = statusOf(result.value.status),
                    note = result.value.reviewNote,
                ),
            )
            // Both carry sentences meant for the user: quota refusals and duplicate titles come from the
            // database as readable text, and paraphrasing them would lose the only actionable part.
            is StoreResult.Unavailable -> UiSubmitResult(false, result.reason)
            is StoreResult.Failed -> UiSubmitResult(false, result.message)
        }
    }

    fun mine(): List<UiStoreSubmission> =
        when (val result = submissions.mine()) {
            is StoreResult.Ok -> result.value.map {
                UiStoreSubmission(
                    itemId = it.itemSlug,
                    // The listing has no title of its own; the slug is what the submitter recognises.
                    projectName = it.itemSlug,
                    version = it.version,
                    status = statusOf(it.status),
                    note = it.reviewNote,
                )
            }
            else -> emptyList()
        }

    fun withdraw(itemId: String, version: String): Boolean =
        submissions.withdraw(itemId, version) is StoreResult.Ok

    private fun PackagedProject.toUi(rootPath: String) = UiPackagedProject(
        rootPath = rootPath,
        fileCount = fileCount,
        totalBytes = totalBytes,
        sha256 = sha256,
        excluded = excluded,
        archivePath = archivePath,
    )

    /**
     * Map the backend's status text onto the UI's states.
     *
     * `pending` is the only one the submit path produces today; the rest exist because a moderator sets
     * them later and this list has to render whatever comes back. An unknown value maps to SUBMITTED
     * rather than failing: a new server-side status must not blank the card.
     */
    private fun statusOf(status: String): UiSubmissionStatus = when (status.lowercase()) {
        "approved", "published" -> UiSubmissionStatus.PUBLISHED
        "rejected" -> UiSubmissionStatus.REJECTED
        "changes_requested" -> UiSubmissionStatus.CHANGES_REQUESTED
        "building" -> UiSubmissionStatus.BUILDING
        else -> UiSubmissionStatus.SUBMITTED
    }
}

package dev.ide.core.backend

import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import dev.ide.store.StoreSubmissionRequest
import dev.ide.store.StoreSubmissionService
import dev.ide.ui.backend.UiPackagedProject
import dev.ide.ui.backend.UiPublishedItem
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
internal class StoreSubmissions(
    private val submissions: StoreSubmissionService,
    /**
     * The project's launcher icon as raster bytes, when one is stored as an image file.
     *
     * The fallback for [UiSubmissionDraft.iconBytes]: it only answers for a project that ships a raster
     * icon, which most do not, and it cannot render the XML ones.
     *
     * A parameter rather than a direct call so this class keeps depending on the submission port alone,
     * and so a test can publish an icon without an Android project on disk.
     */
    private val launcherIcon: (rootPath: String) -> ByteArray? = { null },
) {

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
            screenshotPaths = draft.screenshotPaths,
            // Read from the project, not asked for. The app already has an icon, and a form that made the
            // publisher supply it again would mostly produce listings with no icon at all. The screen's
            // rendering wins when it has one: most projects' launcher icons are XML, which only something
            // with a canvas can turn into an image.
            iconPath = iconFileFor(draft.iconBytes ?: runCatching { launcherIcon(packaged.rootPath) }.getOrNull()),
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

    /**
     * [bytes] written to a temp file for upload, or null.
     *
     * A file because that is what the upload takes, and a temp one because the icon inside the project is
     * the publisher's, not ours to hand out a path to. Failure is silent on purpose: a submission must not
     * stop because an icon could not be read, and the listing falls back to its glyph tile.
     */
    private fun iconFileFor(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val file = java.io.File.createTempFile("ca-store-icon-", ".${imageExtension(bytes)}")
            file.deleteOnExit()
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    /**
     * The extension for what [bytes] actually are.
     *
     * The upload names the published object after this file and takes its content type from the extension,
     * so a WebP launcher icon written to a `.png` would be served under a type it is not.
     */
    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp"
        else -> "png"
    }

    fun mine(): List<UiStoreSubmission> =
        when (val result = submissions.mine()) {
            is StoreResult.Ok -> result.value.map {
                UiStoreSubmission(
                    itemId = it.itemSlug,
                    // The title the submitter gave it, falling back to the slug, which is what the store
                    // knows the listing by when its item row could not be read.
                    projectName = it.itemTitle?.takeIf { t -> t.isNotBlank() } ?: it.itemSlug,
                    version = it.version,
                    status = statusOf(it.status),
                    note = it.reviewNote,
                )
            }
            else -> emptyList()
        }

    /**
     * The account's own listings, with the version a new submission should carry.
     *
     * The store refuses a version code it has already stored for an item (`unique (item_id, version_code)`),
     * and "already stored" includes a submission still waiting for review, so the suggestion steps past
     * everything sent rather than past what is live. Getting this right is what keeps the common case —
     * publish, get reviewed, publish again — from ending in a constraint error the publisher cannot read.
     */
    fun myItems(): List<UiPublishedItem> =
        when (val result = submissions.myItems()) {
            is StoreResult.Ok -> result.value.map {
                UiPublishedItem(
                    slug = it.slug,
                    title = it.title,
                    status = it.status,
                    publishedVersion = it.publishedVersion,
                    suggestedVersion = nextVersionAfter(it.highestVersion),
                    iconPath = it.iconPath,
                )
            }
            else -> emptyList()
        }

    fun withdraw(itemId: String, version: String): Boolean =
        submissions.withdraw(itemId, version) is StoreResult.Ok

    // ---- the account's own profile ----

    fun profile(): dev.ide.store.StorePublisherProfile? =
        (submissions.myProfile() as? StoreResult.Ok)?.value

    /** Null when saved; otherwise the message to show, which the backend wrote for a person to read. */
    fun saveProfile(
        handle: String,
        displayName: String,
        bio: String?,
        location: String?,
        linkUrl: String?,
    ): String? = when (val result = submissions.saveProfile(handle, displayName, bio, location, linkUrl)) {
        is StoreResult.Ok -> null
        is StoreResult.Unavailable -> result.reason
        is StoreResult.Failed -> result.message
    }

    /** Null when the answer is not known, which the form treats as "not yet" rather than as "taken". */
    fun handleAvailable(handle: String): Boolean? =
        (submissions.handleAvailable(handle) as? StoreResult.Ok)?.value

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

    internal companion object {
        /**
         * One step past [version], as the next submission for that item.
         *
         * A patch bump, since that is what a re-publish of the same project usually is, and the publisher
         * can still type something else. Each component is capped at 999 by the store's version code
         * (`major * 1_000_000 + minor * 1_000 + patch`), so a full field rolls into the next one rather
         * than producing a version that sorts BELOW the one it follows. Nothing parseable, or nothing sent
         * yet, starts at 1.0.0.
         */
        internal fun nextVersionAfter(version: String?): String {
            val parts = version.orEmpty().split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return "1.0.0"
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return when {
                patch < 999 -> "$major.$minor.${patch + 1}"
                minor < 999 -> "$major.${minor + 1}.0"
                else -> "${major + 1}.0.0"
            }
        }
    }
}

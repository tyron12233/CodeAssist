package dev.ide.store.bridge

import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreResult
import dev.ide.store.impl.PayloadExtractor
import dev.ide.store.impl.ProjectBaseline
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath
import dev.ide.store.impl.platform.parentPath
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState

/**
 * Brings an installed store project up to a newer version, in the folder it already lives in.
 *
 * Two ways, chosen by [StoreUpdates] from what the user did since installing:
 *
 *  - [applyInPlace], for a copy the user has not changed: the store's files are swapped for the new
 *    version's. Everything else in the folder stays (build output, the IDE's state, the user's own files).
 *  - [merge], for a copy the user has worked in: every file is resolved three ways, against the version
 *    that was installed (kept as its archive for exactly this), so the user's edits and the publisher's
 *    are both kept. Where both changed the same lines, both are kept between conflict markers.
 *
 * Either way a failure part-way leaves the project as it was: every file about to be written or removed is
 * copied aside first and put back if any step fails. The new version comes through the same two gates as an
 * install (the catalog's sha256 while downloading, then [PayloadExtractor]'s checks), and on success its
 * archive is kept at [keepArchiveAt]-style paths as the base the next update merges against.
 */
class StoreUpdater(
    private val source: StoreCatalogSource,
    private val extractor: PayloadExtractor = PayloadExtractor(),
) {

    /** What a merge did. Paths are project-relative. */
    data class MergeOutcome(
        /** The new version's file hashes: the project's new baseline. */
        val baseline: Map<String, String>,
        /** Files that now carry conflict markers, or that kept the user's copy over a change they could not merge. */
        val conflicts: List<String>,
        /** Files the merge wrote, cleanly or not. */
        val changed: Int,
    )

    /**
     * Replace the store's files in [root] with [payload]'s. Returns the new version's file hashes, which
     * become the project's baseline, or why it could not be done (with the project untouched).
     */
    fun applyInPlace(
        payload: StoreInstaller.Payload,
        root: String,
        previous: Map<String, String>,
        /** The user's own files. The update is refused if the new version would write over one of them. */
        userFiles: Set<String> = emptySet(),
        /** Where to keep the new version's archive, the base of the next merge. Null keeps nothing. */
        keepArchiveAt: String? = null,
        onProgress: (UiInstallProgress) -> Unit,
    ): StoreResult<Map<String, String>> = withNewVersion(payload, root, keepArchiveAt, onProgress) { unpacked, incoming ->
        val clash = incoming.keys.firstOrNull { it in userFiles }
        if (clash != null) {
            return@withNewVersion StoreResult.Failed("The new version adds $clash, which you already have a file of your own at")
        }
        val writes = incoming.keys.associateWith { rel -> { StoreFs.readBytes(joinPath(unpacked, rel)) } }
        val deletes = previous.keys - incoming.keys
        when (val r = writeSafely(root, writes, deletes)) {
            null -> StoreResult.Ok(incoming)
            else -> StoreResult.Failed(r)
        }
    }

    /**
     * Merge [payload] into [root], against the version that was installed, kept as [baseArchive].
     *
     * Per file: changed only by the publisher, it takes the new version; changed only by the user, it keeps
     * theirs; changed by both, text is merged by lines ([TextMerge]) and a binary keeps the user's copy, with
     * the new one written beside it as `<name>.new`. `.platform/` is the IDE's own model and state, so the
     * new version's copy is taken there rather than merged.
     */
    fun merge(
        payload: StoreInstaller.Payload,
        root: String,
        baseArchive: String,
        keepArchiveAt: String? = null,
        onProgress: (UiInstallProgress) -> Unit,
    ): StoreResult<MergeOutcome> = withNewVersion(payload, root, keepArchiveAt, onProgress) { unpacked, incoming ->
        val baseScratch = StoreFs.tempPath("ca-store-base-", "")
        try {
            StoreFs.mkdirs(baseScratch)
            val base = when (val r = extractor.extract(baseArchive, baseScratch, payload.title)) {
                is StoreResult.Ok -> r.value
                is StoreResult.Unavailable -> return@withNewVersion StoreResult.Unavailable(r.reason)
                is StoreResult.Failed -> return@withNewVersion StoreResult.Failed("The installed version could not be read back: ${r.message}")
            }
            val baseFiles = ProjectBaseline.capture(base)
            val label = "New version${payload.version?.let { " ($it)" } ?: ""}"

            val writes = LinkedHashMap<String, () -> ByteArray?>()
            val deletes = LinkedHashSet<String>()
            val conflicts = ArrayList<String>()
            for (rel in (baseFiles.keys + incoming.keys).sorted()) {
                if (baseFiles[rel] == incoming[rel]) continue // the publisher did not touch it: keep the user's
                val theirs = if (rel in incoming) StoreFs.readBytes(joinPath(unpacked, rel)) else null
                val baseBytes = if (rel in baseFiles) StoreFs.readBytes(joinPath(base, rel)) else null
                val ours = StoreFs.readBytes(joinPath(root, rel))
                when {
                    ours.contentEqualsNullable(baseBytes) || ProjectBaseline.isIdeState(rel) ->
                        if (theirs == null) deletes += rel else writes[rel] = { theirs }
                    ours.contentEqualsNullable(theirs) -> Unit
                    // The publisher removed a file the user changed: the user's version stays.
                    theirs == null -> conflicts += rel
                    // The user removed a file the publisher changed: it comes back, so the change is not lost
                    // silently, and it is listed so the user can remove it again.
                    ours == null -> { writes[rel] = { theirs }; conflicts += rel }
                    isText(baseBytes) && isText(ours) && isText(theirs) -> {
                        val merged = TextMerge.merge(
                            base = baseBytes?.decodeToString().orEmpty(),
                            ours = ours.decodeToString(),
                            theirs = theirs.decodeToString(),
                            theirsLabel = label,
                        )
                        writes[rel] = { merged.text.encodeToByteArray() }
                        if (merged.conflicts > 0) conflicts += rel
                    }
                    else -> {
                        writes["$rel.new"] = { theirs }
                        conflicts += rel
                    }
                }
            }
            when (val r = writeSafely(root, writes, deletes)) {
                null -> StoreResult.Ok(MergeOutcome(incoming, conflicts, writes.size + deletes.size))
                else -> StoreResult.Failed(r)
            }
        } finally {
            StoreFs.deleteRecursively(baseScratch)
        }
    }

    /**
     * Download [payload], verify and unpack it into scratch space, and hand it to [use]. The archive is kept
     * at [keepArchiveAt] only when [use] succeeded; scratch space is removed either way.
     */
    private fun <T> withNewVersion(
        payload: StoreInstaller.Payload,
        root: String,
        keepArchiveAt: String?,
        onProgress: (UiInstallProgress) -> Unit,
        use: (unpacked: String, incoming: Map<String, String>) -> StoreResult<T>,
    ): StoreResult<T> {
        fun report(state: UiInstallState, fraction: Float) =
            onProgress(UiInstallProgress(payload.itemId, state, fraction, rootPath = root))

        val archive = StoreFs.tempPath("ca-store-update-", ".zip")
        val scratch = StoreFs.tempPath("ca-store-update-", "")
        try {
            report(UiInstallState.DOWNLOADING, 0f)
            when (
                val downloaded = source.downloadPayload(
                    storagePath = payload.storagePath,
                    expectedSha256 = payload.sha256,
                    expectedBytes = payload.sizeBytes,
                    intoPath = archive,
                ) { fraction -> report(UiInstallState.DOWNLOADING, fraction) }
            ) {
                is StoreResult.Ok -> Unit
                is StoreResult.Unavailable -> return StoreResult.Unavailable(downloaded.reason)
                is StoreResult.Failed -> return StoreResult.Failed(downloaded.message)
            }
            report(UiInstallState.IMPORTING, 1f)
            StoreFs.mkdirs(scratch)
            val unpacked = when (val r = extractor.extract(archive, scratch, payload.title)) {
                is StoreResult.Ok -> r.value
                is StoreResult.Unavailable -> return StoreResult.Unavailable(r.reason)
                is StoreResult.Failed -> return StoreResult.Failed(r.message)
            }
            val incoming = ProjectBaseline.capture(unpacked)
            if (incoming.isEmpty()) return StoreResult.Failed("The new version is empty")
            val result = use(unpacked, incoming)
            if (result is StoreResult.Ok) {
                keepArchiveAt?.let { keep ->
                    StoreFs.mkdirsForFile(keep)
                    StoreFs.readBytes(archive)?.let { StoreFs.writeBytes(keep, it) }
                }
                report(UiInstallState.INSTALLED, 1f)
            }
            return result
        } finally {
            StoreFs.delete(archive)
            StoreFs.deleteRecursively(scratch)
        }
    }

    /**
     * Write [writes] and remove [deletes] under [root], all or nothing: every existing file involved is
     * copied aside first, and put back (with anything newly created removed) if a step fails. Null on
     * success, otherwise the message to show.
     */
    private fun writeSafely(root: String, writes: Map<String, () -> ByteArray?>, deletes: Set<String>): String? {
        val backup = StoreFs.tempPath("ca-store-backup-", "")
        try {
            val existing = (writes.keys + deletes).filter { StoreFs.isFile(joinPath(root, it)) }
            for (rel in existing) {
                val target = joinPath(backup, rel)
                StoreFs.mkdirsForFile(target)
                val bytes = StoreFs.readBytes(joinPath(root, rel))
                if (bytes == null || !StoreFs.writeBytes(target, bytes)) {
                    return "Could not make a safe copy of $rel before updating"
                }
            }
            val ok = runCatching {
                for (rel in deletes) StoreFs.delete(joinPath(root, rel))
                for ((rel, bytes) in writes) {
                    val target = joinPath(root, rel)
                    StoreFs.mkdirsForFile(target)
                    val data = bytes() ?: error("missing $rel")
                    check(StoreFs.writeBytes(target, data)) { "could not write $rel" }
                }
                pruneEmptyParents(root, deletes)
            }.isSuccess
            if (!ok) {
                for (rel in writes.keys) if (rel !in existing) StoreFs.delete(joinPath(root, rel))
                for (rel in existing) {
                    val bytes = StoreFs.readBytes(joinPath(backup, rel)) ?: continue
                    val target = joinPath(root, rel)
                    StoreFs.mkdirsForFile(target)
                    StoreFs.writeBytes(target, bytes)
                }
                return "The update could not be written, so your project was left as it was"
            }
            return null
        } finally {
            StoreFs.deleteRecursively(backup)
        }
    }

    /** Directories the old version had that are now empty, removed so the tree does not keep their shells. */
    private fun pruneEmptyParents(root: String, removed: Set<String>) {
        val dirs = removed.flatMap { rel ->
            generateSequence(parentPath(joinPath(root, rel))) { parentPath(it) }
                .takeWhile { it.length > root.length }
                .toList()
        }.toSet().sortedByDescending { it.length }
        for (dir in dirs) if (StoreFs.isDirectory(dir) && StoreFs.list(dir).isEmpty()) StoreFs.delete(dir)
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null || other == null) this == null && other == null else contentEquals(other)

    /** Text a line merge can work on: valid UTF-8 with no NUL bytes. A missing file counts (an empty base). */
    private fun isText(bytes: ByteArray?): Boolean {
        if (bytes == null) return true
        if (bytes.any { it == 0.toByte() }) return false
        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.isSuccess
    }
}

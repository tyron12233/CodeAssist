package dev.ide.store.bridge

import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.store.impl.ProjectBaseline
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiLocalChanges
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreMergeResult
import dev.ide.store.impl.platform.StoreFs
import dev.ide.ui.backend.UiStoreUpdate

/**
 * Updates to store projects already on this device: noticing them, and deciding how one may be applied.
 *
 * An install records a baseline (the version, and every file's hash). An update is the store's row for the
 * same slug carrying a later version code. Applying it is decided by what the user did since installing:
 *
 *  - **Nothing**, or only new files of their own: the store's files are replaced in place ([StoreUpdater]).
 *  - **Changed or deleted a store file**: in place would throw that work away, so it is refused; the caller
 *    installs the new version as a separate project instead, and the user's copy is left as it is.
 *  - **No baseline** (an install from before baselines existed): what is the version and what is an edit
 *    cannot be told apart, so it is treated as edited.
 *
 * Merging the user's edits with the new version is the next step and is not done here.
 */
class StoreUpdates(
    private val source: StoreCatalogSource,
    private val baselines: StoreBaselines,
    private val updater: StoreUpdater,
    private val appBuild: () -> Int,
    /** Maps a catalog row to the UI's item, overlaying the bundled catalogue the way the feed does. */
    private val toUi: (RemoteStoreItem) -> UiStoreItem,
) {

    /** Record what an install just put into [rootPath], so a later version can be recognised and applied. */
    /** Where an install should keep its archive, so a later update can merge against it. */
    fun archivePath(itemId: String): String? = baselines.archivePath(itemId)

    fun recordInstall(payload: StoreInstaller.Payload, rootPath: String) {
        baselines.write(
            payload.itemId,
            StoreBaselines.Baseline(payload.version, payload.versionCode, ProjectBaseline.capture(rootPath)),
        )
    }

    /** The store's current row for [itemId], looked up by its exact slug. Null when it cannot be reached. */
    fun current(itemId: String): RemoteStoreItem? {
        val result = source.search(StoreQuery(text = itemId, limit = 5, offset = 0), appBuild())
        return (result as? StoreResult.Ok)?.value?.firstOrNull { it.id == itemId }
    }

    /**
     * The update for [itemId] installed at [rootPath], or null when there is none (or it cannot be known).
     *
     * Newer means a higher version code. Rows from before version codes existed carry 0 on both sides, and
     * fall back to "the version string differs", which is the most a string can say.
     */
    fun check(itemId: String, rootPath: String, remote: RemoteStoreItem? = current(itemId)): UiStoreUpdate? {
        val row = remote ?: return null
        if (row.storagePath == null) return null
        val baseline = baselines.read(itemId)
        val newer = when {
            baseline == null -> false
            row.versionCode > 0 && baseline.versionCode > 0 -> row.versionCode > baseline.versionCode
            else -> row.version != null && row.version != baseline.version
        }
        if (!newer) return null
        return UiStoreUpdate(toUi(row), baseline?.version, row.version, rootPath)
    }

    fun localChanges(itemId: String, rootPath: String): UiLocalChanges {
        val baseline = baselines.read(itemId) ?: return UiLocalChanges(known = false)
        val changes = ProjectBaseline.localChanges(rootPath, baseline.files)
        return UiLocalChanges(changes.modified, changes.deleted, changes.added, canMerge = baselines.hasArchive(itemId))
    }

    /**
     * Update the project at [rootPath] in place, if nothing the user did would be lost. Re-checks the
     * project itself rather than trusting what the screen last showed: an edit can land between the two.
     */
    fun applyInPlace(
        itemId: String,
        rootPath: String,
        onProgress: (UiInstallProgress) -> Unit,
    ): UiStoreInstallResult {
        val baseline = baselines.read(itemId)
            ?: return UiStoreInstallResult(false, "This project was installed before updates were tracked, so it can only be updated as a separate copy")
        val changes = localChanges(itemId, rootPath)
        if (changes.blocksInPlace) {
            return UiStoreInstallResult(false, "You've changed files in this project, so updating it in place would overwrite them")
        }
        val row = current(itemId) ?: return UiStoreInstallResult(false, "The store can't be reached right now")
        val payload = StoreInstaller.Payload.of(row)
            ?: return UiStoreInstallResult(false, "That version has nothing to download")
        // A file the user added at a path the new version also ships would be overwritten too.
        return when (val result = updater.applyInPlace(payload, rootPath, baseline.files, changes.added.toSet(), baselines.archivePath(itemId), onProgress)) {
            is StoreResult.Ok -> {
                baselines.write(itemId, StoreBaselines.Baseline(row.version, row.versionCode, result.value))
                // The version itself is already on the line this message is shown in.
                UiStoreInstallResult(true, "Updated", rootPath)
            }
            is StoreResult.Unavailable -> UiStoreInstallResult(false, result.reason)
            is StoreResult.Failed -> UiStoreInstallResult(false, result.message)
        }
    }

    /**
     * Update the project at [rootPath] and keep the user's changes: the version that was installed, the
     * user's copy and the new version are merged file by file. Overlapping edits come back as conflicts,
     * marked in the files themselves.
     */
    fun merge(itemId: String, rootPath: String, onProgress: (UiInstallProgress) -> Unit): UiStoreMergeResult {
        if (baselines.read(itemId) == null || !baselines.hasArchive(itemId)) {
            return UiStoreMergeResult(false, "This project was installed before CodeAssist kept the original files, so your changes can't be merged. Install the new version as a separate copy instead.")
        }
        val row = current(itemId) ?: return UiStoreMergeResult(false, "The store can't be reached right now")
        val payload = StoreInstaller.Payload.of(row)
            ?: return UiStoreMergeResult(false, "That version has nothing to download")
        val base = baselines.archivePath(itemId)!!
        // Read into memory first: the merge keeps the NEW archive at the same path once it succeeds.
        val baseCopy = StoreFs.tempPath("ca-store-base-", ".zip")
        try {
            val bytes = StoreFs.readBytes(base)
            if (bytes == null || !StoreFs.writeBytes(baseCopy, bytes)) {
                return UiStoreMergeResult(false, "The installed version's files could not be read")
            }
            return when (val result = updater.merge(payload, rootPath, baseCopy, base, onProgress)) {
                is StoreResult.Ok -> {
                    baselines.write(itemId, StoreBaselines.Baseline(row.version, row.versionCode, result.value.baseline))
                    val n = result.value.conflicts.size
                    UiStoreMergeResult(
                        success = true,
                        message = if (n == 0) "Updated, and your changes were kept" else "Updated. $n file(s) need you to choose between your change and the new one",
                        conflicts = result.value.conflicts,
                        rootPath = rootPath,
                    )
                }
                is StoreResult.Unavailable -> UiStoreMergeResult(false, result.reason)
                is StoreResult.Failed -> UiStoreMergeResult(false, result.message)
            }
        } finally {
            StoreFs.delete(baseCopy)
        }
    }
}

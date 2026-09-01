package dev.ide.core.backend

import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreResult
import dev.ide.store.impl.PayloadExtractor
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState
import dev.ide.ui.backend.UiStoreInstallResult
import java.io.File

/**
 * Downloads a community project and unpacks it into the workspace.
 *
 * Split out of [StoreBackend] because this is the one part of the store worth testing against real bytes:
 * it takes a source, a directory and a payload, and needs no project, engine or host. The backend keeps
 * what genuinely depends on the running IDE — where the workspace is, and when to count an install.
 *
 * The payload is untrusted (it came out of a public bucket), so there are two gates and both must hold:
 * the sha256 has to match what the catalog row promised, and the archive has to survive
 * [PayloadExtractor]'s checks. Neither is skippable — the result is unpacked into the user's workspace.
 */
internal class StoreInstaller(
    private val source: StoreCatalogSource,
    private val extractor: PayloadExtractor = PayloadExtractor(),
) {

    /** What an install needs to know, taken from the catalog row rather than trusted from the archive. */
    class Payload(
        val itemId: String,
        val storagePath: String,
        val sha256: String?,
        val sizeBytes: Long,
        val title: String,
        /** The catalog's version string, so a review can say which release it is about. */
        val version: String? = null,
    )

    /**
     * Run the install, reporting each phase through [onProgress].
     *
     * The temp archive is deleted either way: on success it has been unpacked, and on failure keeping a
     * partial download would only make the next attempt ambiguous.
     */
    fun install(
        payload: Payload,
        projectsRoot: File,
        /**
         * Make the unpacked directory a real project (build its model if the archive did not carry one).
         * Returns null on success, or the reason it is not a project CodeAssist can open. A rejected
         * directory is deleted here, so a failed install leaves the workspace exactly as it was.
         */
        adopt: (File) -> String?,
        onProgress: (UiInstallProgress) -> Unit,
    ): UiStoreInstallResult {
        val id = payload.itemId
        fun report(state: UiInstallState, fraction: Float, message: String? = null) =
            onProgress(UiInstallProgress(id, state, fraction, message))

        fun failed(message: String): UiStoreInstallResult {
            report(UiInstallState.FAILED, 0f, message)
            return UiStoreInstallResult(false, message)
        }

        val archive = File.createTempFile("ca-store-", ".zip")
        try {
            report(UiInstallState.DOWNLOADING, 0f)
            val downloaded = source.downloadPayload(
                storagePath = payload.storagePath,
                expectedSha256 = payload.sha256,
                expectedBytes = payload.sizeBytes,
                into = archive,
            ) { fraction -> report(UiInstallState.DOWNLOADING, fraction) }

            when (downloaded) {
                is StoreResult.Ok -> Unit
                is StoreResult.Unavailable -> return failed(downloaded.reason)
                is StoreResult.Failed -> return failed(downloaded.message)
            }

            // The checksum was verified as the bytes streamed, so reaching here means the archive is the
            // one the catalog described. Unpacking is the second gate.
            report(UiInstallState.IMPORTING, 1f)
            return when (val extracted = extractor.extract(archive, projectsRoot, payload.title)) {
                is StoreResult.Ok -> {
                    val dir = extracted.value
                    val rejected = adopt(dir)
                    if (rejected != null) {
                        // Unpacked, but nothing can open it. Leaving the folder behind would put an entry
                        // in the workspace that the picker cannot show and the user cannot remove.
                        dir.deleteRecursively()
                        return failed(rejected)
                    }
                    report(UiInstallState.INSTALLED, 1f)
                    UiStoreInstallResult(true, "Added to your projects", dir.absolutePath)
                }
                is StoreResult.Unavailable -> failed(extracted.reason)
                is StoreResult.Failed -> failed(extracted.message)
            }
        } finally {
            archive.delete()
        }
    }
}

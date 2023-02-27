package org.jetbrains.kotlin.com.intellij.util.indexing

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.com.intellij.psi.stubs.SerializationManagerEx
import org.jetbrains.kotlin.com.intellij.util.SystemProperties
import java.nio.file.Files
import kotlin.io.path.*

@ApiStatus.Internal
object CorruptionMarker {
    private const val CORRUPTION_MARKER_NAME = "corruption.marker"
    private const val MARKED_AS_DIRTY_REASON =
        "Indexes marked as dirty (IDE is expected to be work)"
    private const val FORCE_REBUILD_REASON = "Indexes were forcibly marked as corrupted"
    private const val EXPLICIT_INVALIDATION_REASON = "Explicit index invalidation"

    private val corruptionMarker
        get() = PathManager.getIndexRoot().toPath().resolve(CORRUPTION_MARKER_NAME)

    @JvmStatic
    fun markIndexesAsDirty() {
        createCorruptionMarker(MARKED_AS_DIRTY_REASON)
    }

    @JvmStatic
    fun markIndexesAsClosed() {
        val corruptionMarkerExists = corruptionMarker.exists()
        if (corruptionMarkerExists) {
            try {
                if (corruptionMarker.readText() == MARKED_AS_DIRTY_REASON) {
                    corruptionMarker.deleteExisting()
                }
            } catch (ignored: Exception) {
            }
        }
    }

    @JvmStatic
    fun requestInvalidation() {
        FileBasedIndexImpl.LOG.info("Explicit index invalidation has been requested")
        createCorruptionMarker(EXPLICIT_INVALIDATION_REASON)
    }

    @JvmStatic
    fun requireInvalidation(): Boolean {
        val corruptionMarkerExists = corruptionMarker.exists()
        if (corruptionMarkerExists) {
            val message = "Indexes are corrupted and will be rebuilt"
            try {
                val corruptionReason = corruptionMarker.readText()
                FileBasedIndexImpl.LOG.info("$message (reason = $corruptionReason)")
            } catch (e: Exception) {
                FileBasedIndexImpl.LOG.info(message)
            }
        }
        return IndexInfrastructure.hasIndices() && corruptionMarkerExists
    }

    @JvmStatic
    fun dropIndexes() {
        FileBasedIndexImpl.LOG.info("Indexes are dropped")
        val indexRoot = PathManager.getIndexRoot().toPath()

        if (Files.exists(indexRoot)) {
            val filesToBeIgnored =
                FileBasedIndexInfrastructureExtension.EP_NAME.extensions.mapNotNull { it.persistentStateRoot }
                    .toSet()

            Files.walk(indexRoot).filter { it.isDirectory() }.forEach {
                if (!filesToBeIgnored.contains(it.fileName.toString())) {
                    FileUtil.deleteWithRenaming(it.toFile())
                }
            }
        } else {
            Files.createDirectories(indexRoot)
        }

        if (SystemProperties.getBooleanProperty(
                "idea.index.clear.diagnostic.on.invalidation",
                true
            )
        ) {
//            IndexDiagnosticDumper.clearDiagnostic()
        }

        // serialization manager is initialized before and use removed index root so we need to reinitialize it
        SerializationManagerEx.getInstanceEx().reinitializeNameStorage()
        ID.reinitializeDiskStorage()
        PersistentIndicesConfiguration.saveConfiguration()
        FileUtil.delete(corruptionMarker)
        FileBasedIndexInfrastructureExtension.EP_NAME.extensions.forEach { it.resetPersistentState() }
//        FileBasedIndexLayoutSettings.saveCurrentLayout()
    }

    private fun createCorruptionMarker(reason: String) {
        try {
            corruptionMarker.writeText(reason)
        } catch (e: Exception) {
            FileBasedIndexImpl.LOG.warn(e)
        }
    }
}
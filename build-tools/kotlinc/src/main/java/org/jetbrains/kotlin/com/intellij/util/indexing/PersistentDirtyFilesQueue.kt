package org.jetbrains.kotlin.com.intellij.util.indexing

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntCollection
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.*


internal object PersistentDirtyFilesQueue {
  private const val dirtyQueueFileName = "dirty-file-ids"

  private val isUnittestMode: Boolean
    get() = ApplicationManager.getApplication().isUnitTestMode

  private val dirtyFilesQueueFile: Path
    get() = PathManager.getIndexRoot().toPath() / dirtyQueueFileName

  fun removeCurrentFile() {
    if (isUnittestMode) {
      thisLogger().info("removing $dirtyQueueFileName")
    }
    try {
      dirtyFilesQueueFile.deleteIfExists()
    }
    catch (ignored: IOException) {
    }
  }

  fun thisLogger() = Logger.getInstance(PersistentDirtyFilesQueue::class.java)

  fun readIndexingQueue(): IntSet {
    val result = IntOpenHashSet()
    try {
      DataInputStream(dirtyFilesQueueFile.inputStream().buffered()).use {
        while (it.available() > -1) {
          result.add(it.readInt())
        }
      }
    }
    catch (ignored: NoSuchFileException) {
    }
    catch (ignored: EOFException) {
    }
    catch (e: IOException) {
      thisLogger().error(e)
    }
    if (isUnittestMode) {
      thisLogger().info("read dirty file ids: ${result.toIntArray().contentToString()}")
    }
    return result
  }

  fun storeIndexingQueue(fileIds: IntCollection) {
    try {
      if (fileIds.isEmpty()) {
        dirtyFilesQueueFile.deleteIfExists()
      }
      dirtyFilesQueueFile.parent.createDirectories()
      DataOutputStream(dirtyFilesQueueFile.outputStream().buffered()).use {
        fileIds.forEach { fileId ->
          it.writeInt(fileId)
        }
      }
    }
    catch (e: IOException) {
      thisLogger().error(e)
    }
    if (isUnittestMode) {
      val idsToPaths = mapOf(*fileIds.map { it to StaleIndexesChecker.getStaleRecordOrExceptionMessage(it) }.toTypedArray())
      thisLogger().info("dirty file ids stored. Ids & filenames: ${idsToPaths.toString().take(300)}")
    }
  }
}
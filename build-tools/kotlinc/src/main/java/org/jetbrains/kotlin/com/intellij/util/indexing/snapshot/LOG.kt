package org.jetbrains.kotlin.com.intellij.util.indexing.snapshot

import org.jetbrains.kotlin.com.intellij.openapi.Forceable
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing
import org.jetbrains.kotlin.com.intellij.util.indexing.ID
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexInfrastructure
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.com.intellij.util.io.PersistentEnumerator
import java.io.Closeable
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

private val LOG = Logger.getInstance(CompositeHashIdEnumerator::class.java)

internal class CompositeHashIdEnumerator(private val indexId: ID<*, *>): Closeable, Forceable {
  @Volatile
  private var enumerator = init()

  @Throws(IOException::class)
  override fun close() = enumerator.close()

  override fun isDirty(): Boolean = enumerator.isDirty

  override fun force() = enumerator.force()

  @Throws(IOException::class)
  fun clear() {
    try {
      close()
    }
    catch (e: IOException) {
      LOG.error(e)
    }
    finally {
      IOUtil.deleteAllFilesStartingWith(getBasePath())
      init()
    }
  }

  fun enumerate(hashId: Int, subIndexerTypeId: Int) = enumerator.enumerate(CompositeHashId(hashId, subIndexerTypeId))

  private fun getBasePath() = IndexInfrastructure.getIndexRootDir(indexId).resolve("compositeHashId")

  private fun init(): PersistentEnumerator<CompositeHashId> {
    enumerator = PersistentEnumerator(getBasePath(), CompositeHashIdDescriptor(), 64 * 1024)
    return enumerator
  }
}

private data class CompositeHashId(val baseHashId: Int, val subIndexerTypeId: Int)

private class CompositeHashIdDescriptor : KeyDescriptor<CompositeHashId> {
  override fun getHashCode(value: CompositeHashId): Int {
    return value.hashCode()
  }

  override fun isEqual(val1: CompositeHashId, val2: CompositeHashId): Boolean {
    return Comparing.equal(val1, val2)
  }

  @Throws(IOException::class)
  override fun save(out: DataOutput, value: CompositeHashId) {
    DataInputOutputUtil.writeINT(out, value.baseHashId)
    DataInputOutputUtil.writeINT(out, value.subIndexerTypeId)
  }

  @Throws(IOException::class)
  override fun read(`in`: DataInput): CompositeHashId {
    return CompositeHashId(DataInputOutputUtil.readINT(`in`), DataInputOutputUtil.readINT(`in`))
  }
}
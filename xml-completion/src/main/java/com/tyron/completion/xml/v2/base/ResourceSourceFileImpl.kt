package com.tyron.completion.xml.v2.base

import com.android.utils.Base128InputStream
import com.android.utils.Base128OutputStream
import it.unimi.dsi.fastutil.objects.Object2IntMap
import java.io.IOException

/**
 * A simple implementation of the [ResourceSourceFile] interface.
 *
 * [relativePath] path of the file relative to the resource directory, or null if the source file of the resource is not available
 * [configuration] configuration the resource file is associated with
 */
data class ResourceSourceFileImpl(
  override val relativePath: String?,
  override val configuration: RepositoryConfiguration
): ResourceSourceFile {
  @Throws(IOException::class)
  override fun serialize(stream: Base128OutputStream, configIndexes: Object2IntMap<String>) {
    stream.writeString(relativePath)
    stream.writeInt(configIndexes.getInt(configuration.folderConfiguration.qualifierString))
  }

  companion object {
    /**
     * Creates a ResourceSourceFileImpl by reading its contents from the given stream.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun deserialize(stream: Base128InputStream, configurations: List<RepositoryConfiguration>): ResourceSourceFileImpl {
      val relativePath = stream.readString()
      val configIndex = stream.readInt()
      return ResourceSourceFileImpl(relativePath, configurations[configIndex])
    }
  }
}

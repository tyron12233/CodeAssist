package com.tyron.completion.xml.v2.base

import com.android.utils.Base128OutputStream
import it.unimi.dsi.fastutil.objects.Object2IntMap
import java.io.IOException

/**
 * Represents an XML file from which an Android resource was created.
 */
interface ResourceSourceFile {
  /**
   * The path of the file relative to the resource directory, or null if the source file
   * of the resource is not available.
   */
  val relativePath: String?

  /**
   * The configuration the resource file is associated with.
   */
  val configuration: RepositoryConfiguration

  @JvmDefault
  val repository : LoadableResourceRepository
    get() = configuration.repository

  /**
   * Serializes the ResourceSourceFile to the given stream.
   */
  @Throws(IOException::class)
  fun serialize(stream: Base128OutputStream, configIndexes: Object2IntMap<String>)
}
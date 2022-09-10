package com.tyron.completion.xml.v2.aar

import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * Externally provided data used by [AarSourceResourceRepository] and [FrameworkResourceRepository]
 * to validate and create a persistent cache file.
 *
 * @param cacheFile The location of the cache file.
 * @param contentVersion The version of the content of the resource directory or file.
 * @param codeVersion The version of the Android plugin, used to make sure that the cache file is updated
 *     when the code changes. This version is an additional safety measure on top of
 *     [AarSourceResourceRepository.CACHE_FILE_FORMAT_VERSION].
 * @param cacheCreationExecutor The executor used for creating a cache file, or null if the cache file
 *     should not be created if it doesn't exist or is out of date.
 */
class CachingData(val cacheFile: Path,
                  val contentVersion: String,
                  val codeVersion: String,
                  val cacheCreationExecutor: Executor? = null)

/**
 * Directory for the cache files relative to the system path.
 */
const val RESOURCE_CACHE_DIRECTORY = "caches/resources"
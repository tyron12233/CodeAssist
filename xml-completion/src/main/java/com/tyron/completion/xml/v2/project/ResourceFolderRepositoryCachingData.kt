package com.tyron.completion.xml.v2.project

import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * Caching parameters used by [ResourceFolderRepository]
 *
 * @param cacheFile The location of the cache file.
 * @param cacheIsInvalidated The cache is invalidated and should not be used for loading.
 * @param codeVersion The version of the Android plugin, used to make sure that the cache file is updated
 *     when the code changes. This version is an additional safety measure on top of
 *     [ResourceFolderRepository.CACHE_FILE_FORMAT_VERSION].
 * @param cacheCreationExecutor The executor used for creating a cache file, or null if the cache file
 *     should not be created if it doesn't exist or is out of date.
 */
class ResourceFolderRepositoryCachingData(val cacheFile: Path,
                                          val cacheIsInvalidated: Boolean,
                                          val codeVersion: String,
                                          val cacheCreationExecutor: Executor? = null)
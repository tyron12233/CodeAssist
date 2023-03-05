@file:JvmName("ApkCreatorFactories")
package com.tyron.builder.gradle.internal.packaging

import com.android.tools.build.apkzlib.zfile.ApkCreatorFactory
import com.android.tools.build.apkzlib.zfile.ApkZFileCreatorFactory
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.android.tools.build.apkzlib.zip.compress.DeflateExecutionCompressor
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater

/**
 * Time after which background compression threads should be discarded.
 */
private const val BACKGROUND_THREAD_DISCARD_TIME_MS: Long = 100

/**
 * Maximum number of compression threads.
 */
private const val MAXIMUM_COMPRESSION_THREADS = 2

/**
 * Creates an [ApkCreatorFactory] based on the definitions in the project. This is only to
 * be used with the incremental packager.
 *
 * @param debuggableBuild whether the [ApkCreatorFactory] will be used to create a
 * debuggable archive
 * @return the factory
 */
fun fromProjectProperties(
    debuggableBuild: Boolean
): ApkCreatorFactory {
    val options = ZFileOptions()
    options.noTimestamps = true
    options.coverEmptySpaceUsingExtraField = true

    val compressionExecutor = ThreadPoolExecutor(
        0, /* Number of always alive threads */
        MAXIMUM_COMPRESSION_THREADS,
        BACKGROUND_THREAD_DISCARD_TIME_MS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingDeque()
    )

    if (debuggableBuild) {
        options.compressor = DeflateExecutionCompressor(compressionExecutor, Deflater.BEST_SPEED)
    } else {
        options.compressor =
            DeflateExecutionCompressor(compressionExecutor, Deflater.DEFAULT_COMPRESSION)
        options.autoSortFiles = true
    }

    return ApkZFileCreatorFactory(options)
}

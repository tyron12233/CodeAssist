package com.tyron.builder.gradle.tasks

import com.android.manifmerger.MergingReport
import com.google.common.base.Charsets
import com.google.common.io.Files
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.util.internal.GFileUtils
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException

/** A task that processes the manifest  */
@DisableCachingByDefault
abstract class ManifestProcessorTask : NonIncrementalTask() {

    @get:Optional
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Optional
    @get:OutputFile
    abstract val mergeBlameFile: RegularFileProperty

    companion object {
        @Throws(IOException::class)
        @JvmStatic
        protected fun outputMergeBlameContents(
            mergingReport: MergingReport, mergeBlameFile: File?
        ) {
            if (mergeBlameFile == null) {
                return
            }
            val output =
                mergingReport.getMergedDocument(MergingReport.MergedManifestKind.BLAME)
                    ?: return
            GFileUtils.mkdirs(mergeBlameFile.parentFile)
            Files.newWriter(
                mergeBlameFile,
                Charsets.UTF_8
            ).use { writer -> writer.write(output) }
        }
    }
}
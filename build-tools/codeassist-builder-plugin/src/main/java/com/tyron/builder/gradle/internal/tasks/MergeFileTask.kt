package com.tyron.builder.gradle.internal.tasks

import com.android.utils.FileUtils
import com.google.common.base.Charsets
import com.google.common.io.Files
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException

/**
 * Task to merge files. This appends all the files together into an output file.
 *
 * Caching disabled by default for this task because the task does very little work.
 * Concatenates the registered Inputs into a single Output file, requiring no computation.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.MISC, secondaryTaskCategories = [TaskCategory.MERGING])
abstract class MergeFileTask : NonIncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @Throws(IOException::class)
    override fun doTaskAction() {
        mergeFiles(inputFiles.files, outputFile.get().asFile)
    }

    companion object {

        fun mergeFiles(inputFiles: Collection<File>, output: File) {
            // filter out any non-existent files
            val existingFiles = inputFiles.filter { it.isFile() }

            if (existingFiles.size == 1) {
                FileUtils.copyFile(existingFiles[0], output)
                return
            }

            // first delete the current file
            FileUtils.deleteIfExists(output)

            // no input? done.
            if (existingFiles.isEmpty()) {
                return
            }

            // otherwise put all the files together
            for (file in existingFiles) {
                val content = Files.toString(file, Charsets.UTF_8)
                Files.append("$content\n", output, Charsets.UTF_8)
            }
        }
    }
}

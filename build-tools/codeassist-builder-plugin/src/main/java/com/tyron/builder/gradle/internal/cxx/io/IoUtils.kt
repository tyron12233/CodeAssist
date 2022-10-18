package com.tyron.builder.gradle.internal.cxx.io

import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.gradle.internal.cxx.io.SynchronizeFile.Comparison.SAME_PATH_BY_FILE_OBJECT_IDENTITY
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files.isSameFile

/**
 * Returns true if the two files are the same file (including through hard links)
 * or if they have the same content.
 */
fun isSameFileOrContent(
    source: File,
    destination: File
) = compareFileContents(source, destination).areSameFileOrContent

/**
 * Returns true if the two files are the same file (including through hard links)
 * or if they have the same content.
 */
@VisibleForTesting
fun compareFileContents(
    source: File,
    destination: File,
    compareBufferSize: Int = 8192
    ) : SynchronizeFile.Comparison {
    when {
        source as Any === destination as Any -> return SAME_PATH_BY_FILE_OBJECT_IDENTITY
        source.path == destination.path -> return SynchronizeFile.Comparison.SAME_PATH_ACCORDING_TO_LEXICAL_PATH
        source.canonicalPath == destination.canonicalPath ->
            // Canonical path can throw an IO exception when the paths are not valid for the
            // underlying OS file provider. Here, we let the exception propagate rather than
            // claiming the files are the same or different.
            return SynchronizeFile.Comparison.SAME_PATH_ACCORDING_TO_CANONICAL_PATH
        !source.isFile && !destination.isFile -> return SynchronizeFile.Comparison.SAME_SOURCE_AND_DESTINATION_DID_NOT_EXIST
        !source.isFile -> return SynchronizeFile.Comparison.NOT_SAME_SOURCE_DID_NOT_EXIST
        !destination.isFile -> return SynchronizeFile.Comparison.NOT_SAME_DESTINATION_DID_NOT_EXIST
        isSameFile(source.toPath(), destination.toPath()) ->
            // This method should follow hard links and return true if those files lead to the
            // same content.
            return SynchronizeFile.Comparison.SAME_PATH_ACCORDING_TO_FILE_SYSTEM_PROVIDER
        source.length() != destination.length() -> return SynchronizeFile.Comparison.NOT_SAME_LENGTH
    }

    // Both files are the same size. Now check the actual content to see whether there are
    // byte-wise differences.
    val buffer1 = ByteArray(compareBufferSize)
    val buffer2 = ByteArray(compareBufferSize)
    FileInputStream(source).use { input1 ->
        FileInputStream(destination).use { input2 ->
            do {
                val size1 = input1.read(buffer1)
                if (size1 == -1) {
                    return@compareFileContents SynchronizeFile.Comparison.SAME_CONTENT
                }
                val size2 = input2.read(buffer2)
                assert(size1 == size2)
                if (!(buffer1 contentEquals buffer2)) {
                    return@compareFileContents SynchronizeFile.Comparison.NOT_SAME_CONTENT
                }
            } while(true)
        }
    }
}

/**
 * Remove [files] that are the same as, or have the same content as, a file earlier in the list.
 */
fun removeDuplicateFiles(files : List<File>): List<File> {
    if (files.size < 2) return files
    val seen = mutableListOf<File>()
    for(file in files) {
        // This is O(N^2) but normally there's at most one expensive call to isSameFileContent(...)
        // because:
        // - When two files are different their file size is likely to be different and
        //   isSameFileOrContent(...) will be fast.
        // - When two files are the same then isSameFileOrContent(...) is expensive but then the
        //   'any { }' call will return early before evaluating the rest of 'seen'
        if (seen.any { saw -> isSameFileOrContent(saw, file) } ) continue
        seen.add(file)
    }
    return seen
}

private val SynchronizeFile.Comparison.areSameFileOrContent : Boolean
    get() = when(this) {
        SynchronizeFile.Comparison.NOT_SAME_SOURCE_DID_NOT_EXIST,
        SynchronizeFile.Comparison.NOT_SAME_DESTINATION_DID_NOT_EXIST,
        SynchronizeFile.Comparison.NOT_SAME_LENGTH,
        SynchronizeFile.Comparison.NOT_SAME_CONTENT -> false
        SynchronizeFile.Comparison.SAME_SOURCE_AND_DESTINATION_DID_NOT_EXIST,
        SAME_PATH_BY_FILE_OBJECT_IDENTITY,
        SynchronizeFile.Comparison.SAME_PATH_ACCORDING_TO_LEXICAL_PATH,
        SynchronizeFile.Comparison.SAME_PATH_ACCORDING_TO_FILE_SYSTEM_PROVIDER,
        SynchronizeFile.Comparison.SAME_PATH_ACCORDING_TO_CANONICAL_PATH,
        SynchronizeFile.Comparison.SAME_CONTENT -> true
        else -> error("Unrecognized comparison code: ${this}")
    }


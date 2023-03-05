@file:JvmName("IncrementalChanges")
package com.tyron.builder.files

import com.android.ide.common.resources.FileStatus
import com.android.tools.build.apkzlib.zip.compress.Zip64NotSupportedException
import com.tyron.builder.files.IncrementalRelativeFileSets.fromZip
import java.io.File
import java.io.Serializable
import java.util.*

data class SerializableChange(
    val file: File,
    val fileStatus: FileStatus,
    val normalizedPath: String
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class SerializableInputChanges(
    val roots: List<File>,
    val changes: Collection<SerializableChange>
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

class SerializableFileChanges(
    val fileChanges: List<SerializableChange>
) : Serializable {

    private val fileStatusMap by lazy {
        fileChanges.groupBy { it.fileStatus }
    }

    val removedFiles by lazy {
        fileStatusMap[FileStatus.REMOVED] ?: emptyList()
    }

    val modifiedFiles by lazy {
        fileStatusMap[FileStatus.CHANGED] ?: emptyList()
    }

    val addedFiles by lazy {
        fileStatusMap[FileStatus.NEW] ?: emptyList()
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Convert a set of serializable changes to incremental changes.
 *
 * Changes that mix jars and directories must use `@Classpath` or `@CompileClasspath` sensitivity,
 * which ensures that the normalized path for jars at the root is the name of the jar.
 *
 * Do *not* use this with relative normalization, as an addition and removal of two files with the
 * same normalized path but different absolute paths may be reported as a change, which breaks the
 * jar cache.
 */
fun classpathToRelativeFileSet(
    changes: SerializableInputChanges,
    cache: KeyedFileCache,
    cacheUpdates: MutableSet<Runnable>
): Map<RelativeFile, FileStatus> {
    return Collections.unmodifiableMap(HashMap<RelativeFile, FileStatus>().apply {
        for (change in changes.changes) {
            if (change.normalizedPath.isEmpty()) {
                check(change.file.path.endsWith(".zip") || change.file.path.endsWith(".jar")) {
                    "Incremental input root file ${change.file.path} must end with '.zip' or '.jar'."
                }
                addZipChanges(change.file, cache, cacheUpdates)
            } else {
                addFileChange(change)
            }
        }
    })
}

@Throws(Zip64NotSupportedException::class)
fun MutableMap<RelativeFile, FileStatus>.addZipChanges(
    change: File,
    cache: KeyedFileCache,
    cacheUpdates: MutableSet<Runnable>
) {
    putAll(fromZip(ZipCentralDirectory(change), cache, cacheUpdates))
}

fun MutableMap<RelativeFile, FileStatus>.addFileChange(
    change: SerializableChange
) {
    val key = RelativeFile.fileInDirectory(change.normalizedPath, change.file)
    val previous = get(key)

    if (previous != null) {
        /**
         * Note that with classpath normalization, the same file may be reported as both
         * added and removed if its order within the classpath changes.
         *
         * In that case, mark the status as changed.
         */
        when (previous) {
            FileStatus.NEW -> check(change.fileStatus == FileStatus.REMOVED) { "Multiple changes for one file? $change and $previous" }
            FileStatus.REMOVED -> check(change.fileStatus == FileStatus.NEW) { "Multiple changes for one file? $change and $previous" }
            FileStatus.CHANGED -> throw IllegalStateException("Multiple changes for one file? $change and $previous")
        }
        put(key, FileStatus.CHANGED)
    } else {
        put(key, change.fileStatus)
    }
}
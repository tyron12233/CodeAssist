@file:JvmName("RelativeResourceUtils")

package com.tyron.builder.common.resources


import java.io.File
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import kotlin.IllegalStateException

private const val separator: String = ":/"

/**
 * Determines a resource file path relative to the source set containing the resource.
 *
 * The absolute path to the module source set is identified by the source set ordering of a module.
 * Format of the returned String is `<package name - source set module order>:<path to source set>`.
 */
fun getRelativeSourceSetPath(resourceFile: File, moduleSourceSets: Map<String, String>)
        : String {
    val absoluteResFilePath = resourceFile.absolutePath
    for ((identifier, absoluteSourceSetPath) in moduleSourceSets.entries) {
        if (absoluteResFilePath.startsWith(absoluteSourceSetPath)) {
            val invariantFilePath = resourceFile.absoluteFile.invariantSeparatorsPath
            val resIndex = File(absoluteSourceSetPath).absoluteFile.invariantSeparatorsPath.length
            val relativePathToSourceSet = invariantFilePath.substring(resIndex + 1)
            return "$identifier$separator$relativePathToSourceSet"
        }
    }

    throw IllegalArgumentException(
        "Unable to locate resourceFile ($absoluteResFilePath) in source-sets.")
}

/**
 * Converts a source set identified relative resource path to an absolute path.
 *
 * The source set identifier before the separator is replaced with the absolute source set
 * path and then concatenated with the path after the separator.
 */
fun relativeResourcePathToAbsolutePath(
    relativePath: String,
    sourceSetPathMap: Map<String, String>,
    fileSystem: FileSystem = FileSystems.getDefault()): String {
    return relativeResourcePathToAbsolutePath(sourceSetPathMap, fileSystem)(relativePath)
}

fun relativeResourcePathToAbsolutePath(
    sourceSetPathMap: Map<String, String>,
    fileSystem: FileSystem = FileSystems.getDefault()
): (String) -> String {
    return { relativePath: String ->
        if (sourceSetPathMap.none()) {
            throw IllegalStateException(
                """Unable to get absolute path from $relativePath
                   because no relative root paths are present."""
            )
        }
        val separatorIndex = relativePath.indexOf(separator)
        if (separatorIndex == -1) {
            throw IllegalArgumentException(
                """Source set identifier and relative path must be separated by a "$separator".
                   Relative path: $relativePath"""
            )
        }
        val sourceSetPrefix = relativePath.substring(0, separatorIndex)
        val resourcePathFromSourceSet =
            relativePath.substring(separatorIndex + separator.lastIndex, relativePath.length)
        val systemRelativePath = if ("/" != fileSystem.separator) {
            resourcePathFromSourceSet.replace("/", fileSystem.separator)
        } else {
            resourcePathFromSourceSet
        }
        val absolutePath = sourceSetPathMap[sourceSetPrefix]
            ?: throw NoSuchElementException(
                """Unable to get absolute path from $relativePath
                       because $sourceSetPrefix is not key in sourceSetPathMap."""
            )
        "$absolutePath$systemRelativePath"
    }
}

/**
 * Verifies if a string is relative resource sourceset filepath. This is for cases where it is
 * not possible to determine if relative resource filepaths are enabled by default.
 */
fun isRelativeSourceSetResource(filepath: String) : Boolean {
    return filepath.contains(separator)
}
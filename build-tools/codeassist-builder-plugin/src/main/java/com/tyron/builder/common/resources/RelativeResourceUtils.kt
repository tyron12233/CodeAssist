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

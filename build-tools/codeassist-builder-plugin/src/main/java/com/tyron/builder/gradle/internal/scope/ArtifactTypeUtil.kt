package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.api.artifact.Artifact
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.util.internal.GFileUtils
import java.io.File
import java.util.Locale

/**
 * Returns a suitable output directory for this receiving artifact type.
 * This folder will be independent of variant or tasks names and should be further qualified
 * if necessary.
 *
 * @param parentFile the parent directory.
 */
fun Artifact<*>.getOutputDir(parentDir: File)=
    GFileUtils.join(parentDir, category.name.lowercase(Locale.US), getFolderName())

fun Artifact<*>.getIntermediateOutputDir(parentDir: File): File =
    GFileUtils.join(parentDir, Artifact.Category.INTERMEDIATES.name.lowercase(Locale.US), getFolderName())

/**
 * Returns a [File] representing the artifact type location (could be a directory or regular file).
 *
 * @param buildDirectory the parent build folder
 * @param identifier the unique scoping identifier
 * @param taskName the task name to append to the path, or null if not necessary
 * @param forceFilename to overwrite default fileName
 * @return a [File] that can be safely use as task output.
 */
fun Artifact<*>.getOutputPath(
    buildDirectory: DirectoryProperty,
    variantIdentifier: String,
    vararg paths: String,
    forceFilename:String = "") = GFileUtils.join(
        getOutputDir(buildDirectory.get().asFile),
        variantIdentifier,
        *paths,
        if(forceFilename.isNullOrEmpty()) getFileSystemLocationName() else forceFilename
    )

fun Artifact<*>.getIntermediateOutputPath(
    buildDirectory: DirectoryProperty,
    variantIdentifier: String,
    vararg paths: String,
    forceFilename:String = "") = GFileUtils.join(
    getIntermediateOutputDir(buildDirectory.get().asFile),
    variantIdentifier,
    *paths,
    if(forceFilename.isNullOrEmpty()) getFileSystemLocationName() else forceFilename
)

/**
 * Converts a [FileCollection] to a [Provider] of a [List] of [RegularFile], filtering other types
 * like [Directory]
 */
fun FileCollection.getRegularFiles(projectDirectory: Directory): Provider<List<RegularFile>> =
    elements.map {
        it.filter { file -> file.asFile.isFile }
            .map {
                fileSystemLocation -> projectDirectory.file(fileSystemLocation.asFile.absolutePath)
        }
    }

/**
 * Converts a [FileCollection] to a [Provider] of a [List] of [Directory], ignoring other types
 * like [RegularFile]
 */
fun FileCollection.getDirectories(projectDirectory: Directory): Provider<List<Directory>> =
    elements.map {
        it.filter { file -> file.asFile.isDirectory }
            .map {
                fileSystemLocation -> projectDirectory.dir(fileSystemLocation.asFile.absolutePath)
        }
    }

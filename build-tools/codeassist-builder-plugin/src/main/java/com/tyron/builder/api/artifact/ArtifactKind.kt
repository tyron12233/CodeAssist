package com.tyron.builder.api.artifact

import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import java.io.Serializable
import kotlin.reflect.KClass

/**
 * Exhaustive list of artifact file representations supported by the Android Gradle plugin.
 *
 * As of now, only [RegularFile] represented by [FILE] and [Directory] represented by [DIRECTORY]
 * are supported.
 */
sealed class ArtifactKind<T: FileSystemLocation>(): Serializable {
    object FILE : ArtifactKind<RegularFile>() {
        override fun dataType(): KClass<RegularFile> {
            return RegularFile::class
        }
    }

    object DIRECTORY : ArtifactKind<Directory>() {
        override fun dataType(): KClass<Directory> {
            return Directory::class
        }
    }

    /**
     * @return The data type used by Gradle to represent the file abstraction for this
     * artifact kind.
     */
    abstract fun dataType(): KClass<T>
}
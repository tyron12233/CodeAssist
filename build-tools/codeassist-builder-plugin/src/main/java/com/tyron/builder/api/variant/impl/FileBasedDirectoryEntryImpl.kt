package com.tyron.builder.api.variant.impl

import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.tasks.util.PatternSet
import java.io.File

/**
 * Implementation of [DirectoryEntry] for an existing directory. The directory is provided as a
 * [Provider] of [Directory] for convenience, the directory must exist with sources present during
 * configuration time.
 *
 * @param directory the directory that exists and contains source files.
 * @param filter optional filters to apply to the folder.
 * @param isUserAdded true if the user added this source folder or false if created by AGP.
 */
class FileBasedDirectoryEntryImpl(
    override val name: String,
    private val directory: File,
    override val filter: PatternFilterable? = null,
    override val isUserAdded: Boolean = false,
    override val shouldBeAddedToIdeModel: Boolean = false,
): DirectoryEntry {

    override fun asFiles(directoryPropertyCreator: () -> DirectoryProperty): Provider<Directory> {
        return directoryPropertyCreator().also {
            it.set(directory)
        }
    }

    override val isGenerated: Boolean = false

    override fun asFileTree(
        fileTreeCreator: () -> ConfigurableFileTree,
        directoryPropertyCreator: () -> DirectoryProperty
    ): ConfigurableFileTree =
        fileTreeCreator().setDir(directory).also {
            if (filter != null) {
                it.include((filter as PatternSet).asIncludeSpec)
                it.exclude(filter.asExcludeSpec)
            }
        }
}
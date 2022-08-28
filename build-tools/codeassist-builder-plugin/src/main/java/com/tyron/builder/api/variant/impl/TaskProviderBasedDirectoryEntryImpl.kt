package com.tyron.builder.api.variant.impl

import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.util.PatternFilterable

/**
 * Implementation of [DirectoryEntry] based on [Provider] of [Directory] with embedded task
 * dependency. The [TaskProvider] is also provided to the constructor as creating a
 * [ConfigurableFileTree] from a [Provider] is not enough, and the [ConfigurableFileTree.builtBy]
 * must be explicitly called.
 */
class TaskProviderBasedDirectoryEntryImpl(
    override val name: String,
    private val directoryProvider: Provider<Directory>,
    override val isGenerated: Boolean = true,
    override val isUserAdded: Boolean = false,
    override val shouldBeAddedToIdeModel: Boolean = false,
): DirectoryEntry {

    /**
     * Filters cannot be set on task provided source folders, tasks should just not create extra
     * sources that would require filtering.
     */
    override val filter: PatternFilterable? = null

    override fun asFiles(directoryPropertyCreator: () -> DirectoryProperty): Provider<Directory> {
        return directoryProvider
    }

    override fun asFileTree(
        fileTreeCreator: () -> ConfigurableFileTree,
        directoryPropertyCreator: () -> DirectoryProperty
    ): ConfigurableFileTree =
        fileTreeCreator().setDir(directoryProvider).builtBy(directoryProvider)
}

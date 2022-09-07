package com.tyron.builder.api.variant.impl
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.util.PatternFilterable

/**
 * Implementation of [DirectoryEntry] based on a [ConfigurableFileTree]. This is for backward
 * compatibility of the old variant API.
 *
 * Do not use without explicitly stating the reasons as this class should be removed once the old
 * variant API is removed.
 */
class ConfigurableFileTreeBasedDirectoryEntryImpl(
    override val name: String,
    private val configurableFileTree: ConfigurableFileTree,
): DirectoryEntry {

    override fun asFiles(directoryPropertyCreator: () -> DirectoryProperty): Provider<Directory> {
        return directoryPropertyCreator().also {
            it.set(configurableFileTree.dir)
        }
    }

    override val isGenerated: Boolean = true
    override val isUserAdded: Boolean = true
    override val shouldBeAddedToIdeModel: Boolean = true

    override val filter: PatternFilterable?
        get() = null

    override fun asFileTree(
        fileTreeCreator: () -> ConfigurableFileTree,
        directoryPropertyCreator: () -> DirectoryProperty
    ) = configurableFileTree
}


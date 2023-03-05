package com.tyron.builder.api.variant.impl

import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.tasks.util.PatternSet

class ProviderBasedDirectoryEntryImpl(
    override val name: String,
    val elements: Provider<Set<FileSystemLocation>>,
    override val filter: PatternFilterable?
): DirectoryEntry  {

    override val isGenerated: Boolean = true
    override val isUserAdded: Boolean = true
    override val shouldBeAddedToIdeModel: Boolean = true

    override fun asFiles(directoryPropertyCreator: () -> DirectoryProperty): Provider<Directory> {
        return elements.flatMap {
            if (it.size > 1) {
                throw RuntimeException("There are more than one element in $name\n" +
                        "${it.map { it.asFile.absolutePath }}")
            }
            directoryPropertyCreator().also { directoryProperty ->
                directoryProperty.set(it.single().asFile)
            }
        }
    }

    override fun asFileTree(
        fileTreeCreator: () -> ConfigurableFileTree,
        directoryPropertyCreator: () -> DirectoryProperty
    ): ConfigurableFileTree {
        return fileTreeCreator()
            .from(asFiles(directoryPropertyCreator))
            .builtBy(elements)
            .also {
                if (filter != null) {
                    it.include((filter as PatternSet).asIncludeSpec)
                    it.exclude(filter.asExcludeSpec)
                }
            }
    }
}
package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.SourceDirectories
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.util.PatternFilterable
import java.io.File

/**
 * A set of source directories for a specific [SourceType]
 *
 * @param _name name of the source directories, as returned by [SourceType.name]
 * @param variantServices the variant's [VariantServices]
 * @param variantDslFilters filters set on the variant specific source directory in the DSL, may be null if
 * the is no variant specific source directory.
 */
class FlatSourceDirectoriesImpl(
    _name: String,
    private val variantServices: VariantServices,
    variantDslFilters: PatternFilterable?
): SourceDirectoriesImpl(_name, variantServices, variantDslFilters),
    SourceDirectories.Flat {

    // For compatibility with the old variant API, we must allow reading the content of this list
    // before it is finalized.
    private val variantSources = variantServices.newListPropertyForInternalUse(
        type = DirectoryEntry::class.java,
    )

    // this will contain all the directories
    private val directories = variantServices.newListPropertyForInternalUse(
        type = Directory::class.java,
    )

    override val all: Provider<out Collection<Directory>> = directories

    //
    // Internal APIs.
    //

    override fun addSource(directoryEntry: DirectoryEntry) {
        variantSources.add(directoryEntry)
        directories.add(directoryEntry.asFiles(variantServices::directoryProperty))
    }


    internal fun getAsFileTrees(): Provider<List<ConfigurableFileTree>> =
            variantSources.map { entries: MutableList<DirectoryEntry> ->
                entries.map { sourceDirectory ->
                    sourceDirectory.asFileTree(
                        variantServices::fileTree,
                        variantServices::directoryProperty
                    )
                }
            }

    internal fun addSources(sourceDirectories: Iterable<DirectoryEntry>) {
        sourceDirectories.forEach(::addSource)
    }

    /*
     * Internal API that can only be used by the model.
     */
    override fun variantSourcesForModel(filter: (DirectoryEntry) -> Boolean ): List<File> {
        val files = mutableListOf<File>()
        variantSources.get()
            .filter { filter.invoke(it) }
            .forEach {
                val asDirectoryProperty = it.asFiles(variantServices::directoryProperty)
                if (asDirectoryProperty.isPresent) {
                    files.add(asDirectoryProperty.get().asFile)
                }
            }
        return files
    }
}

package com.tyron.builder.api.variant.impl

import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.util.PatternFilterable

/**
 * Abstraction of a source directory within the Variant object model.
 */
interface DirectoryEntry {

    /**
     *  source folder name, human readable but not guaranteed to be unique.
     */
    val name: String

    /**
     * true if it contains generated sources, false it is editable by the user.
     */
    val isGenerated: Boolean

    /**
     * true if the user added this source folder (generated or not), false if it is a folder
     * that was automatically created by AGP.
     */
    val isUserAdded: Boolean

    /**
     * true if the folder should be added to the IDE model, false otherwise.
     */
    val shouldBeAddedToIdeModel: Boolean

    /**
     * Return the source folder as a [Provider] of [Directory], with appropriate
     * [org.gradle.api.Task] dependency if there is one. Can be used as a task input directly.
     */
    fun asFiles(directoryPropertyCreator: () -> DirectoryProperty): Provider<Directory>


    /**
     * Return the source folder as a [ConfigurableFileTree] which can be used as
     * [org.gradle.api.Task] input.
     */
    fun asFileTree(
        fileTreeCreator: () -> ConfigurableFileTree,
        directoryPropertyCreator: () -> DirectoryProperty
    ): ConfigurableFileTree

    /**
     * Optional filter associated with this source folder.
     */
    val filter: PatternFilterable?
}
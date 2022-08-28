package com.tyron.builder.gradle.api

import org.gradle.api.Incubating
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.util.PatternFilterable
import java.io.File

/**
 * An AndroidSourceDirectorySet represents a set of directory inputs for an Android project.
 */
@Deprecated("Use  com.tyron.builder.api.dsl.AndroidSourceDirectorySet")
interface AndroidSourceDirectorySet : PatternFilterable, com.tyron.builder.api.dsl.AndroidSourceDirectorySet {

    override fun getName(): String

    override fun srcDir(srcDir: Any): AndroidSourceDirectorySet

    override fun srcDirs(vararg srcDirs: Any): AndroidSourceDirectorySet

    override fun setSrcDirs(srcDirs: Iterable<*>): AndroidSourceDirectorySet

    /**
     * Returns the list of source files as a [org.gradle.api.file.FileTree]
     *
     * @return a non null [FileTree] for all the source files in this set.
     */
    fun getSourceFiles(): FileTree

    /**
     * Returns the filter used to select the source from the source directories.
     *
     * @return a non null [org.gradle.api.tasks.util.PatternFilterable]
     */
    val filter: PatternFilterable

    /**
     * Returns the source folders as a list of [org.gradle.api.file.ConfigurableFileTree]
     *
     *
     * This is used as the input to the java compile to enable incremental compilation.
     *
     * @return a non null list of [ConfigurableFileTree]s, one per source dir in this set.
     */
    fun getSourceDirectoryTrees(): List<ConfigurableFileTree>

    /**
     * Returns the resolved directories.
     *
     * Setter can be called with a collection of [Object]s, just like
     * Gradle's `project.file(...)`.
     *
     * @return a non null set of File objects.
     */
    val srcDirs: Set<File>

    /** Returns the [FileCollection] that represents this source sets.  */
    @Incubating
    fun getBuildableArtifact(): FileCollection
}
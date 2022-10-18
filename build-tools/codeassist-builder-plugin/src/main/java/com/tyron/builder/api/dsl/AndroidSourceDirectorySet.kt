package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.Named
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.tasks.util.PatternSet
import java.io.File

/**
 * An AndroidSourceDirectorySet represents a set of directory inputs for an Android project.
 */
interface AndroidSourceDirectorySet : Named {

    /**
     * A concise name for the source directory (typically used to identify it in a collection).
     */
    @Incubating
    override fun getName(): String

    /**
     * Adds the given source directory to this set.
     *
     * @param srcDir The source directory. This is evaluated as [org.gradle.api.Project.file]
     *
     * This method has a return value for legacy reasons.
     */
    @Incubating
    fun srcDir(srcDir: Any): Any

    /**
     * Adds the given source directories to this set.
     *
     * @param srcDirs The source directories. These are evaluated as [org.gradle.api.Project.files]
     *
     * This method has a return value for legacy reasons.
     */
    @Incubating
    fun srcDirs(vararg srcDirs: Any): Any

    /**
     * Sets the source directories for this set.
     *
     * @param srcDirs The source directories. These are evaluated as for
     * [org.gradle.api.Project.files]
     *
     *  This method has a return value for legacy reasons.
     */
    @Incubating
    fun setSrcDirs(srcDirs: Iterable<*>): Any
}
package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.Named
import java.io.File

/**
 * An AndroidSourceFile represents a single file input for an Android project.
 */
interface AndroidSourceFile: Named {

    /**
     * A concise name for the source directory (typically used to identify it in a collection).
     */
    @Incubating
    override fun getName(): String

    /**
     * Sets the location of the file.
     *
     * @param srcPath The source directory. This is evaluated as [org.gradle.api.Project.file]
     *
     * This method has a return value for legacy reasons.
     */
    @Incubating
    fun srcFile(srcPath: Any): Any
}
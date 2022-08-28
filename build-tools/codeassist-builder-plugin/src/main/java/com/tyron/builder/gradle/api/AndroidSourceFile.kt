package com.tyron.builder.gradle.api

import java.io.File

/**
 * An AndroidSourceFile represents a single file input for an Android project.
 */
@Deprecated("Use  com.tyron.builder.api.dsl.AndroidSourceFile")
interface AndroidSourceFile: com.tyron.builder.api.dsl.AndroidSourceFile {
    /**
     * A concise name for the source directory (typically used to identify it in a collection).
     */
    override fun getName(): String

    /** The source file */
    val srcFile: File

    override fun srcFile(srcPath: Any): AndroidSourceFile
}
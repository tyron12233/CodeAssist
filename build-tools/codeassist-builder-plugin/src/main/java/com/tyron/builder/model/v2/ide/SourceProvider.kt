package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.CustomSourceDirectory
import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * Represent a SourceProvider for a given configuration.
 *
 * TODO: source filters?
 *
 * @since 4.2
 */
interface SourceProvider: AndroidModel {
    /**
     * Returns the name of this source set.
     *
     * @return The name. Never returns null.
     */
    val name: String

    /**
     * Returns the manifest file.
     *
     * @return the manifest file. It may not exist.
     */
    val manifestFile: File

    /**
     * Returns the java source folders.
     *
     * @return a list of folders. They may not all exist.
     */
    val javaDirectories: Collection<File>

    /**
     * Returns the kotlin source folders.
     *
     * @return a list of folders. They may not all exist.
     */
    val kotlinDirectories: Collection<File>

    /**
     * Returns the java resources folders.
     *
     * @return a list of folders. They may not all exist.
     */
    val resourcesDirectories: Collection<File>

    /**
     * Returns the aidl source folders or null if aidl is disabled
     *
     * @return a list of folders. They may not all exist.
     */
    val aidlDirectories: Collection<File>?

    /**
     * Returns the renderscript source folders or null if renderscript is disabled
     *
     * @return a list of folders. They may not all exist.
     */
    val renderscriptDirectories: Collection<File>?

    /**
     * Returns the android resources folders, or null if resource processing is disabled
     *
     * @return a list of folders. They may not all exist.
     */
    val resDirectories: Collection<File>?

    /**
     * Returns the android assets folders or null if assets processing is disabled
     *
     * @return a list of folders. They may not all exist.
     */
    val assetsDirectories: Collection<File>?

    /**
     * Returns the native libs folders.
     *
     * @return a list of folders. They may not all exist.
     */
    val jniLibsDirectories: Collection<File>

    /**
     * Returns the shader folders or null if shaders are disabled.
     *
     * @return a list of folders. They may not all exist.
     */
    val shadersDirectories: Collection<File>?

    /**
     * Returns the machine learning models folders or null if ML is disabled
     *
     * @return a list of folders. They may not all exist.
     */
    val mlModelsDirectories: Collection<File>?

    /**
     * Returns a [Collection] of all registered custom directories.
     *
     * @return a [Collection] of custom directories.
     */
    val customDirectories: Collection<CustomSourceDirectory>?
}

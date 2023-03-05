package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Information related to the actions creating a bundle (.aab) file for the variant.
 */
@Incubating
interface BundleConfig {

    /**
     * Settings associated with the code transparency feature in bundles.
     * Initialized from the corresponding DSL elements.
     */
    val codeTransparency: CodeTransparency

    /**
     * Add a metadata file to the bundle (.aab) file. The file will be added under the
     * BUNDLE-METADATA folder.
     *
     * @param metadataDirectory the directory below BUNDLE-METADATA where the file should be stored.
     * @param file the [Provider] of [RegularFile] that can be wired from a [org.gradle.api.Task]
     * output or an existing file in the project directory.
     */
    fun addMetadataFile(
        metadataDirectory: String,
        file: Provider<RegularFile>,
    )
}
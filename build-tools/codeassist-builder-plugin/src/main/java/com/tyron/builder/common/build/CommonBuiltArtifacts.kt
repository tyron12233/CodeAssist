package com.tyron.builder.common.build

/**
 * Common interface between studio specific and agp specific in memory representations of the
 * output.json file.
 *
 * agp representation is located in gradle-api package so end users can load/write those files
 * when consuming/producing artifacts.
 *
 * studio representation is located here in sdk-common and cannot import gradle-api interfaces.
 */
interface CommonBuiltArtifacts {
    /**
     * Indicates the version of the metadata file.
     *
     * @return the metadata file.
     */
    val version: Int

    /**
     * Returns the application ID for these [CommonBuiltArtifacts] instances.
     *
     * @return the application ID.
     */
    val applicationId: String

    /**
     * Identifies the variant name for these [CommonBuiltArtifact]
     */
    val variantName: String
}
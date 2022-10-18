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
interface CommonBuiltArtifact {

    /**
     * Returns a read-only version code.
     *
     * @return version code
     */
    val versionCode: Int?

    /**
     * Returns a read-only version name.
     *
     * @return version name
     */
    val versionName: String?

    /**
     * Absolute path to the built file
     *
     * @return the output file path
     */
    val outputFile: String
}
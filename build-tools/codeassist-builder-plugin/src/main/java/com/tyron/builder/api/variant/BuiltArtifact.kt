package com.tyron.builder.api.variant
/**
 * Represents a built artifact that is present in the file system.
 */
interface BuiltArtifact: VariantOutputConfiguration {

    /**
     * Returns a read-only version code.
     *
     * @return version code or null if the version code is unknown (not set in manifest nor DSL)
     */
    val versionCode: Int?

    /**
     * Returns a read-only version name.
     *
     * @return version name or null if the version name is unknown (not set in manifest nor DSL)
     */
    val versionName: String?

    /**
     * Absolute path to the built file
     *
     * @return the output file path.
     */
    val outputFile: String
}
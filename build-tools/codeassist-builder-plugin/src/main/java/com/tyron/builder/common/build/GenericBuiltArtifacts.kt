package com.tyron.builder.common.build

/**
 * Generic version of the gradle-api BuiltArtifacts with all the gradle types removed.
 */
data class GenericBuiltArtifacts(

    override val version: Int,

    /**
     * Identifies the [GenericBuiltArtifacts] for this [Collection] of [GenericBuiltArtifacts],
     * all [GenericBuiltArtifact] are the same type of artifact.
     *
     * @return the [GenericArtifactType] for all the [GenericBuiltArtifact] instances.
     */
    val artifactType: GenericArtifactType,

    /**
     * Returns the application ID for these [GenericBuiltArtifact] instances.
     *
     * @return the application ID.
     */
    override val applicationId: String,

    /**
     * Identifies the variant name for these [GenericBuiltArtifact]
     */
    override val variantName: String,

    /**
     * Returns the [Collection] of [GenericBuiltArtifact].
     */
    val elements: Collection<GenericBuiltArtifact>,

    /**
     *  Type of file stored in [elements], can be "File" or "Directory", or null if there are
     *  no elements or for outputs of older Android Gradle plugins (version 4.1.0).
     */
    val elementType: String?
): CommonBuiltArtifacts
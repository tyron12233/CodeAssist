package com.tyron.builder.common.build

/**
 * Generic version of the gradle-api BuiltArtifact with all gradle specific types removed.
 */
data class GenericBuiltArtifact(
    /**
     * Returns the output type of the referenced APK.
     *
     * @return the Output type for this APK
     */
    val outputType: String,

    /**
     * Returns a possibly empty list of [GenericFilterConfiguration] for this output. If the list
     * is empty this means there is no filter associated to this output.
     *
     * @return list of [GenericFilterConfiguration] for this output.
     */
    val filters: Collection<GenericFilterConfiguration> = listOf(),

    val attributes: Map<String, String> = mapOf(),

    override val versionCode: Int? = null,
    override val versionName: String? = null,
    override val outputFile: String
): CommonBuiltArtifact
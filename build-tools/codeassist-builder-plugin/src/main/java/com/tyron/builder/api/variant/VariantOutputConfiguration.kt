package com.tyron.builder.api.variant

interface VariantOutputConfiguration {
    /**
     * Type of package file, either the main APK or a full split APK file containing resources for a
     * particular split dimension.
     */
    enum class OutputType {
        SINGLE,
        ONE_OF_MANY,
        UNIVERSAL
    }

    /**
     * Returns the output type of the referenced APK.
     *
     * @return the [OutputType] for this APK
     */
    val outputType: OutputType

    /**
     * Returns a possibly empty list of [FilterConfiguration] for this output. If the list is empty,
     * this means there is no filter associated to this output.
     *
     * @return list of [FilterConfiguration] for this output.
     */
    val filters: Collection<FilterConfiguration>
}
package com.tyron.builder.api.variant

/**
 * Immutable filter configuration.
 */
interface FilterConfiguration {

    /**
     * Returns the [FilterType] for this filter configuration.
     */
    val filterType: FilterType

    /**
     * Returns the identifier for this filter. The filter identifier is
     * dependent on the [FilterConfiguration.filterType].
     */
    val identifier: String

    /** Split dimension type  */
    enum class FilterType {
        DENSITY,
        ABI,
        LANGUAGE
    }
}
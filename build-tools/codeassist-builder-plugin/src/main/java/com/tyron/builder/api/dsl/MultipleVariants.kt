package com.tyron.builder.api.dsl

/**
 * Multi variant publishing options.
 */
interface MultipleVariants : PublishingOptions {

    /**
     * Publish all the variants to the component.
     */
    fun allVariants()

    /**
     * Publish variants to the component based on the specified build types.
     */
    fun includeBuildTypeValues(vararg buildTypes: String)

    /**
     * Publish variants to the component based on the specified product flavor dimension and values.
     */
    fun includeFlavorDimensionAndValues(dimension: String, vararg values: String)
}

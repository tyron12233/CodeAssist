package com.tyron.builder.api.variant

import java.util.regex.Pattern

/**
 * Selector to reduce the number of variants that are of interests when calling any of the
 * variant API like [AndroidComponentsExtension.beforeVariants].
 */
interface VariantSelector {
    /**
     * Creates a [VariantSelector] of [ComponentIdentity] that includes all the variants for the
     * current module.
     *
     * @return a [VariantSelector] for all variants.
     */
    fun all(): VariantSelector

    /**
     * Returns a new selector for [ComponentIdentity] objects with a given build type.
     *
     * @param buildType Build type to filter [ComponentIdentity] on.
     * @return An instance of [VariantSelector] to further filter variants.
     */
    fun withBuildType(buildType: String): VariantSelector

    /**
     * Returns a new selector for [ComponentIdentity] objects with a given (dimension, flavorName).
     *
     * @param flavorToDimension Dimension and flavor to filter [ComponentIdentity] on.
     * @return [VariantSelector] instance to further filter instances of [ComponentIdentity]
     */
    fun withFlavor(flavorToDimension: Pair<String, String>): VariantSelector

    /**
     * Returns a new selector for [ComponentIdentity]  objects with a given name pattern.
     *
     * @param pattern [Pattern] to apply on the [org.gradle.api.Named.getName] to filter [ComponentIdentity]
     * instances on
     */
    fun withName(pattern: Pattern): VariantSelector

    /**
     * Returns a new selector for [ComponentIdentity]  objects with a given name.
     *
     * @param name [String] to test against the [org.gradle.api.Named.getName] for equality.
     */
    fun withName(name: String): VariantSelector
}



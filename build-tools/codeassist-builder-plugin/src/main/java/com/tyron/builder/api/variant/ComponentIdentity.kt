package com.tyron.builder.api.variant

/**
 * Variant Configuration represents the identify of a variant
 *
 * This is computed from the list of build types and flavors.
 */
interface ComponentIdentity {

    /**
     * Component's name.
     */
    val name: String

    /**
     * Build type name, might be replaced with access to locked DSL object once ready.
     */
    val buildType: String?

    /**
     * List of flavor names, might be replaced with access to locked DSL objects once ready.
     *
     * The order is properly sorted based on the associated dimension order.
     */
    val productFlavors: List<Pair<String, String>>

    /**
     * The multi-flavor name of the variant.
     *
     * This does not include the build type. If no flavors are present, this will return null
     *
     * The full name of the variant is queried via [name].
     */
    val flavorName: String?
}
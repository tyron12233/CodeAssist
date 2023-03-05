package com.tyron.builder.model.v2.ide

/**
 * Basic information about dependency components
 */
interface ComponentInfo {

    /**
     * The build type attribute of this component.
     *
     * Null if the component does not have Android variants
     */
    val buildType: String?

    /**
     * The product flavor attributes of this component, keyed by flavor dimension name.
     *
     * May be empty if the component does not have Android product flavors.
     */
    val productFlavors: Map<String, String>

    /**
     * The list of attributes associated with the component.
     *
     * Build types and product flavor attributes are handled explicitly in [buildType] and
     * [productFlavors], so they are not included here
     */
    val attributes: Map<String, String>

    /**
     * The list of capabilities associated with the component
     */
    val capabilities: List<String>

    /**
     * Indicates whether this component (library or module) is a test fixtures component (i.e. has
     * a test fixtures capability).
     */
    val isTestFixtures: Boolean
}

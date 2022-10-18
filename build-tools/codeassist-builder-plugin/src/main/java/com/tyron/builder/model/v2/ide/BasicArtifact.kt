package com.tyron.builder.model.v2.ide

/**
 * The base information for all generated artifacts
 */
interface BasicArtifact {

    /**
     * A SourceProvider specific to the variant. This can be null if there is no flavors as
     * the "variant" is equal to the build type.
     */
    val variantSourceProvider: SourceProvider?

    /**
     * A SourceProvider specific to the flavor combination.
     *
     * For instance if there are 2 dimensions, then this would be Flavor1Flavor2, and would be
     * common to all variant using these two flavors and any of the build type.
     *
     * This can be null if there is less than 2 flavors.
     */
    val multiFlavorSourceProvider: SourceProvider?
}

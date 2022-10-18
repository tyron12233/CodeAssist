package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Basic information about a build Variant.
 *
 * This is basically the source set information.
 */
interface BasicVariant: AndroidModel {
    /**
     * The name of the variant.
     */
    val name: String

    /**
     * The main artifact for this variant.
     */
    val mainArtifact: BasicArtifact

    /**
     * The AndroidTest artifact for this variant, if applicable.
     */
    val androidTestArtifact: BasicArtifact?

    /**
     * The Unit Test artifact for this variant, if applicable.
     */
    val unitTestArtifact: BasicArtifact?

    /**
     * The TestFixtures artifact for this variant, if applicable.
     */
    val testFixturesArtifact: BasicArtifact?

    /**
     * The build type name.
     *
     * If null, no build type is associated with the variant (this generally means that no build
     * types exist, which can only happen for libraries)
     */
    val buildType: String?

    /**
     * The flavors for this variants. This can be empty if no flavors are configured.
     */
    val productFlavors: List<String>
}


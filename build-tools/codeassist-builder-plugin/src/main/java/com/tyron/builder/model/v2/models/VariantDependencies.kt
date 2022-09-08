package com.tyron.builder.model.v2.models

import com.tyron.builder.model.v2.AndroidModel
import com.tyron.builder.model.v2.ide.ArtifactDependencies
import com.tyron.builder.model.v2.ide.Library

/**
 * The dependencies for a given variants.
 *
 * This will contain the dependencies for the variant's main artifact as well as its tests (if
 * applicable)
 */
interface VariantDependencies: AndroidModel {
    /**
     * Returns the name of the variant. It is made up of the build type and flavors (if applicable)
     *
     * @return the name of the variant.
     */
    val name: String

    val mainArtifact: ArtifactDependencies

    val androidTestArtifact: ArtifactDependencies?
    val unitTestArtifact: ArtifactDependencies?
    val testFixturesArtifact: ArtifactDependencies?

    /**
     * The list of external libraries used by all the variants in the module.
     *
     * The key for the map entries is the keys found via [com.tyron.builder.model.v2.ide.GraphItem.key]
     * and [Library.key]
     */
    val libraries: Map<String, Library>
}

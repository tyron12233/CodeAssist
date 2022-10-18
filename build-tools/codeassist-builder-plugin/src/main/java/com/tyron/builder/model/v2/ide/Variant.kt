package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * A build Variant.
 */
interface Variant: AndroidModel {
    /**
     * The name of the variant.
     */
    val name: String

    /**
     * The display name for the variant.
     */
    val displayName: String

    /**
     * The main artifact for this variant.
     */
    val mainArtifact: AndroidArtifact

    /**
     * The AndroidTest artifact for this variant, if applicable.
     */
    val androidTestArtifact: AndroidArtifact?

    /**
     * The Unit Test artifact for this variant, if applicable.
     */
    val unitTestArtifact: JavaArtifact?

    /**
     * The TestFixtures artifact for this variant, if applicable.
     */
    val testFixturesArtifact: AndroidArtifact?

    /**
     * For standalone test plugins: information about the tested project.
     *
     * For other plugin types, this is null
     */
    val testedTargetVariant: TestedTargetVariant?

    /**
     * Whether the variant is instant app compatible.
     *
     * Only application modules and dynamic feature modules will set this property.
     */
    val isInstantAppCompatible: Boolean

    /**
     * Desugared methods supported by D8 and core library desugaring.
     */
    val desugaredMethods: List<File>
}

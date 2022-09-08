package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Class representing the tested variants.
 *
 * This is currently used by the test modules, and contains the same pieces of information
 * as the ones used to define the tested application (and it's variant).
 *
 * @since 4.2
 */
interface TestedTargetVariant: AndroidModel {
    /**
     * Returns the Gradle path of the project that is being tested.
     */
    val targetProjectPath: String

    /**
     * Returns the variant of the tested project.
     */
    val targetVariant: String
}

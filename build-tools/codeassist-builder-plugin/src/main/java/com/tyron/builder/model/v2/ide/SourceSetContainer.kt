package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * A container of source sets for a given dimension value (ie a build type or a flavor)
 */
interface SourceSetContainer: AndroidModel {

    /**
     * The production source set
     */
    val sourceProvider: SourceProvider

    /**
     * The optional source set for the AndroidTest component
     */
    val androidTestSourceProvider: SourceProvider?

    /**
     * The optional source set for the UnitTest component
     */
    val unitTestSourceProvider: SourceProvider?

    /**
     * The optional source set for the TestFixtures component
     */
    val testFixturesSourceProvider: SourceProvider?
}

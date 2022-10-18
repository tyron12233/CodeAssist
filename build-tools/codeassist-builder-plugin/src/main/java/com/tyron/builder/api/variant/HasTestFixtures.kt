package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Variants that optionally have test fixtures.
 */
@Incubating
interface HasTestFixtures {
    /**
     * Variant's [TestFixtures], or null if the test fixtures for this variant are disabled.
     */
    val testFixtures: TestFixtures?
}
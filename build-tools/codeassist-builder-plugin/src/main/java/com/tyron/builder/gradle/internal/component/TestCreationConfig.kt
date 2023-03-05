package com.tyron.builder.gradle.internal.component

import org.gradle.api.provider.Provider

/**
 * Interface for properties common to all test components.
 */
interface TestCreationConfig: ComponentCreationConfig {

    /**
     * In unit tests, there is no dexing. However aapt2 requires the instrumentation tag to be
     * present in the merged manifest to process android resources.
     */
    val instrumentationRunner: Provider<String>

    /**
     * The application of the app under tests
     */
    val testedApplicationId: Provider<String>
}
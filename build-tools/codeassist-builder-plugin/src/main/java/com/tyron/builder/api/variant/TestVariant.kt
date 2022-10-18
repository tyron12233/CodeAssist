package com.tyron.builder.api.variant

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * [Variant] for test-only modules.
 */
interface TestVariant: GeneratesTestApk, Variant {
    /**
     * Variant's application ID as present in the final manifest file of the APK.
     */
    override val applicationId: Property<String>

    /**
     * The application of the app under tests.
     */
    val testedApplicationId: Provider<String>
}
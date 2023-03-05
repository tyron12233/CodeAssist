package com.tyron.builder.api.variant

/**
 * Variants that optionally have instrumented tests.
 */
interface HasAndroidTest {

    /**
     * Variant's [AndroidTest] configuration, or null if android tests are disabled for this
     * variant.
     */
    val androidTest: AndroidTest?
}
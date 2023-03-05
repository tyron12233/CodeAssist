package com.tyron.builder.api.variant

import org.gradle.api.provider.Property

interface GeneratesTestApk: GeneratesApk {

    /**
     * The instrumentationRunner to use to run the tests.
     */
    val instrumentationRunner: Property<String>

    /**
     * The handleProfiling value to use to run the tests.
     */
    val handleProfiling: Property<Boolean>

    /**
     * The functionalTest value to use to run the tests.
     */
    val functionalTest: Property<Boolean>

    /**
     * The test label.
     */
    val testLabel: Property<String?>
}

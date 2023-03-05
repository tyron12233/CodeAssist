package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.dexing.DexingType
import org.gradle.api.provider.Provider

/**
 * Contains the final dsl info computed from the DSL object model (extension, default config,
 * build type, flavors) that are needed by components that runs instrumented tests.
 */
interface InstrumentedTestComponentDslInfo {

    /**
     * Returns the instrumentationRunner to use to test this variant, or if the variant is a test,
     * the one to use to test the tested variant.
     *
     * @param dexingType the selected dexing type for this variant.
     * @return the instrumentation test runner name
     */
    fun getInstrumentationRunner(dexingType: DexingType): Provider<String>

    /**
     * Returns the instrumentationRunner arguments to use to test this variant, or if the variant is
     * a test, the ones to use to test the tested variant
     */
    val instrumentationRunnerArguments: Map<String, String>

    /**
     * Returns handleProfiling value to use to test this variant, or if the variant is a test, the
     * one to use to test the tested variant.
     *
     * @return the handleProfiling value
     */
    val handleProfiling: Provider<Boolean>

    /**
     * Returns functionalTest value to use to test this variant, or if the variant is a test, the
     * one to use to test the tested variant.
     *
     * @return the functionalTest value
     */
    val functionalTest: Provider<Boolean>

    /** Gets the test label for this variant  */
    val testLabel: Provider<String?>

    /**
     * Whether android test coverage is enabled for the component's buildType.
     */
    val isAndroidTestCoverageEnabled: Boolean
}

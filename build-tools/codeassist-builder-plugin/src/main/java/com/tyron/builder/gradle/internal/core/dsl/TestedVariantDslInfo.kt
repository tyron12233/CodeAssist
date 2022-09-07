package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.api.dsl.TestFixtures

/**
 * Contains the final dsl info computed from the DSL object model (extension, default config,
 * build type, flavors) that are needed by tested components.
 */
interface TestedVariantDslInfo: VariantDslInfo {
    val testFixtures: TestFixtures

    val testInstrumentationRunnerArguments: Map<String, String>
}
package com.tyron.builder.gradle.internal.core.dsl

/**
 * Represents the dsl info for a unit test component, initialized from the DSL object model
 * (extension, default config, build type, flavors)
 *
 * This class allows querying for the values set via the DSL model.
 *
 * Use [DslInfoBuilder] to instantiate.
 *
 * @see [com.android.build.gradle.internal.component.UnitTestCreationConfig]
 */
interface UnitTestComponentDslInfo: TestComponentDslInfo {
    val isUnitTestCoverageEnabled: Boolean
}
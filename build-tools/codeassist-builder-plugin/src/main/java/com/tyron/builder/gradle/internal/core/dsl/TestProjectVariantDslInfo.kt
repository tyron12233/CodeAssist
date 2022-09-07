package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo

/**
 * Represents the dsl info for a test project variant, initialized from the DSL object model
 * (extension, default config, build type, flavors)
 *
 * This class allows querying for the values set via the DSL model.
 *
 * Use [DslInfoBuilder] to instantiate.
 *
 * @see [com.tyron.builder.gradle.internal.component.TestVariantCreationConfig]
 */
interface TestProjectVariantDslInfo:
    VariantDslInfo,
    MultiVariantComponentDslInfo,
    ApkProducingComponentDslInfo,
    InstrumentedTestComponentDslInfo {
    override val androidResourcesDsl: AndroidResourcesDslInfo
}

package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo

/**
 * Represents the dsl info for a dynamic feature variant, initialized from the DSL object model
 * (extension, default config, build type, flavors)
 *
 * This class allows querying for the values set via the DSL model.
 *
 * Use [DslInfoBuilder] to instantiate.
 *
 * @see [com.tyron.builder.gradle.internal.component.DynamicFeatureCreationConfig]
 */
interface DynamicFeatureVariantDslInfo:
    VariantDslInfo,
    ApkProducingComponentDslInfo,
    TestedVariantDslInfo,
    MultiVariantComponentDslInfo {
    val isMultiDexSetFromDsl: Boolean

    override val androidResourcesDsl: AndroidResourcesDslInfo
}

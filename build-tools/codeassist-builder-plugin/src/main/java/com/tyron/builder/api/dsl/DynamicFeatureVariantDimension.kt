package com.tyron.builder.api.dsl

/**
 * Shared properties between DSL objects that contribute to an dynamic feature variant.
 *
 * That is, [DynamicFeatureBuildType] and [DynamicFeatureProductFlavor] and
 * [DynamicFeatureDefaultConfig].
 */
interface DynamicFeatureVariantDimension :
    VariantDimension
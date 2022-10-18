package com.tyron.builder.api.dsl

/**
 *
 * Shared properties between DSL objects [ProductFlavor] and [DefaultConfig] for dynamic features.
 *
 * See [DynamicFeatureDefaultConfig] and [DynamicFeatureProductFlavor].
 */
interface DynamicFeatureBaseFlavor :
    BaseFlavor,
    DynamicFeatureVariantDimension
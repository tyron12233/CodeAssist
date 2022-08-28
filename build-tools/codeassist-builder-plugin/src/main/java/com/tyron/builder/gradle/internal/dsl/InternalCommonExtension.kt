package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.CommonExtension

/**
 * Internal extension of the DSL interface that overrides the properties to use the implementation
 * types, in order to enable the use of kotlin delegation from the original DSL classes
 * to the new implementations.
 */
interface InternalCommonExtension<
        BuildFeaturesT : com.tyron.builder.api.dsl.BuildFeatures,
        BuildTypeT : com.tyron.builder.api.dsl.BuildType,
        DefaultConfigT : com.tyron.builder.api.dsl.DefaultConfig,
        ProductFlavorT : com.tyron.builder.api.dsl.ProductFlavor> :
    CommonExtension<
            BuildFeaturesT,
            BuildTypeT,
            DefaultConfigT,
            ProductFlavorT>, Lockable {
}
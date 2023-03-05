package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.TestFixtures
import com.tyron.builder.api.dsl.TestedExtension
import org.gradle.api.Action

/** See [InternalCommonExtension] */
interface InternalTestedExtension<BuildFeaturesT : com.tyron.builder.api.dsl.BuildFeatures,
        BuildTypeT : com.tyron.builder.api.dsl.BuildType,
        DefaultConfigT : com.tyron.builder.api.dsl.DefaultConfig,
        ProductFlavorT : com.tyron.builder.api.dsl.ProductFlavor>
    : TestedExtension,
    InternalCommonExtension<BuildFeaturesT, BuildTypeT, DefaultConfigT, ProductFlavorT> {
    fun testFixtures(action: Action<TestFixtures>)
}
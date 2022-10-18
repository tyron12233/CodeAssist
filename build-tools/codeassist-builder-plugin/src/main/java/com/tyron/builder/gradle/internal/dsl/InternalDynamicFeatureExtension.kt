package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.DynamicFeatureBuildFeatures
import com.tyron.builder.api.dsl.DynamicFeatureBuildType
import com.tyron.builder.api.dsl.DynamicFeatureDefaultConfig
import com.tyron.builder.api.dsl.DynamicFeatureExtension
import com.tyron.builder.api.dsl.DynamicFeatureProductFlavor

/** See [InternalCommonExtension] */
interface InternalDynamicFeatureExtension :
    DynamicFeatureExtension,
    InternalTestedExtension<
            DynamicFeatureBuildFeatures,
            DynamicFeatureBuildType,
            DynamicFeatureDefaultConfig,
            DynamicFeatureProductFlavor>
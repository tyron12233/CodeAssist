package com.tyron.builder.api.dsl

/**
 * Extension for the Android Dynamic Feature Gradle Plugin.
 *
 * This is the `android` block when the `com.android.dynamic-feature` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
 */
interface DynamicFeatureExtension :
    CommonExtension<
            DynamicFeatureBuildFeatures,
            DynamicFeatureBuildType,
            DynamicFeatureDefaultConfig,
            DynamicFeatureProductFlavor>,
    ApkExtension,
    TestedExtension {
}
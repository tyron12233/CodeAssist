package com.tyron.builder.gradle.internal.scope

/**
 * Allows access to the final values of
 * [com.android.build.api.dsl.ApplicationBuildFeatures]
 * [com.android.build.api.dsl.DynamicFeatureBuildFeatures]
 * [com.android.build.api.dsl.LibraryBuildFeatures]
 * [com.android.build.api.dsl.TestBuildFeatures]
 *
 * This is a union of all above interfaces to simplify things internally.
 *
 * The values returned take into account default values coming via
 * [com.android.build.gradle.options.BooleanOption]
 */
interface BuildFeatureValues {
    // ------------------
    // Common flags

    val aidl: Boolean
    val compose: Boolean
    val buildConfig: Boolean
    val dataBinding: Boolean
    val mlModelBinding: Boolean
    val prefab: Boolean
    val renderScript: Boolean
    val resValues: Boolean
    val shaders: Boolean
    val viewBinding: Boolean

    // ------------------
    // Application flags

    // ------------------
    // Dynamic-Feature flags

    // ------------------
    // Library flags

    val buildType: Boolean
    val androidResources: Boolean
    val prefabPublishing: Boolean

    // ------------------
    // Test flags
}
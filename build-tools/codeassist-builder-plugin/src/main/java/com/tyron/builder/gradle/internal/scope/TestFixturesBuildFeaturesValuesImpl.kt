package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.gradle.options.ProjectOptions

class TestFixturesBuildFeaturesValuesImpl(
    buildFeatures: BuildFeatures,
    projectOptions: ProjectOptions,
    androidResourcesEnabled: Boolean,
    dataBindingOverride: Boolean? = null,
    mlModelBindingOverride: Boolean? = null
) : BuildFeatureValuesImpl(
    buildFeatures,
    projectOptions,
    dataBindingOverride,
    mlModelBindingOverride
) {

    override val aidl: Boolean = false
    override val buildConfig: Boolean = false
    override val prefab: Boolean = false
    override val renderScript: Boolean = false
    override val shaders: Boolean = false
    override val prefabPublishing: Boolean = false
    override val androidResources: Boolean = androidResourcesEnabled
}

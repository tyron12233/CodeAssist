package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.gradle.options.ProjectOptions

class UnitTestBuildFeaturesValuesImpl(
    buildFeatures: BuildFeatures,
    projectOptions: ProjectOptions,
    dataBindingOverride: Boolean? = null,
    mlModelBindingOverride: Boolean? = null,
    includeAndroidResources: Boolean
) : BuildFeatureValuesImpl(
    buildFeatures,
    projectOptions,
    dataBindingOverride,
    mlModelBindingOverride
) {
    override val androidResources: Boolean = includeAndroidResources
}

package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.gradle.options.ProjectOptions

class AndroidTestBuildFeatureValuesImpl(
    buildFeatures: BuildFeatures,
    projectOptions: ProjectOptions,
    dataBindingOverride: Boolean? = null,
    mlModelBindingOverride: Boolean? = null
) : BuildFeatureValuesImpl(
    buildFeatures,
    projectOptions,
    dataBindingOverride,
    mlModelBindingOverride
) {
}

package com.tyron.builder.gradle.internal.dsl

import com.android.resources.Density
import com.google.common.collect.Sets
import com.tyron.builder.api.dsl.ApplicationDefaultConfig
import com.tyron.builder.api.dsl.DynamicFeatureDefaultConfig
import com.tyron.builder.api.dsl.LibraryDefaultConfig
import com.tyron.builder.api.dsl.TestDefaultConfig
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

/** DSL object for the defaultConfig object.  */
// Exposed in the DSL.
abstract class DefaultConfig @Inject constructor(name: String, dslServices: DslServices) :
    BaseFlavor(name, dslServices),
    ApplicationDefaultConfig,
    DynamicFeatureDefaultConfig,
    LibraryDefaultConfig,
    TestDefaultConfig {

    init {
        val densities = Density.getRecommendedValuesForDevice()
        val strings: MutableSet<String> =
            Sets.newHashSetWithExpectedSize(densities.size)
        for (density in densities) {
            strings.add(density.resourceValue)
        }
        vectorDrawables.setGeneratedDensities(strings)
        vectorDrawables.useSupportLibrary = false
    }
}
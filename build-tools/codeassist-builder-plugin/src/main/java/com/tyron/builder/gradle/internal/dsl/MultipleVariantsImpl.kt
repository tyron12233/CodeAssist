package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.MultipleVariants
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

abstract class MultipleVariantsImpl @Inject constructor(
    dslServices: DslServices,
    val componentName: String,
) : MultipleVariants, PublishingOptionsImpl() {

    internal abstract var allVariants: Boolean
    internal abstract var includedBuildTypes: MutableSet<String>
    internal val includedFlavorDimensionAndValues: MutableMap<String, Set<String>> = mutableMapOf()

    override fun allVariants() {
        allVariants = true
    }

    override fun includeBuildTypeValues(vararg buildTypes: String) {
        this.includedBuildTypes.addAll(buildTypes)
    }

    override fun includeFlavorDimensionAndValues(dimension: String, vararg values: String) {
        this.includedFlavorDimensionAndValues[dimension] = mutableSetOf<String>().also { it.addAll(values) }
    }
}

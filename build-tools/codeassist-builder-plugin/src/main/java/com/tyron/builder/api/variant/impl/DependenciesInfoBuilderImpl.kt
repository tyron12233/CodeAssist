package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.DependenciesInfo
import com.tyron.builder.api.variant.DependenciesInfoBuilder
import com.tyron.builder.gradle.internal.services.VariantBuilderServices
import javax.inject.Inject

open class DependenciesInfoBuilderImpl @Inject constructor(
    variantBuilderServices: VariantBuilderServices,
    dslDependencyInfo: DependenciesInfo
): DependenciesInfoBuilder {
    private val includeInApkValue = variantBuilderServices.valueOf(dslDependencyInfo.includeInApk)
    private val includeInBundleValue = variantBuilderServices.valueOf(dslDependencyInfo.includeInBundle)

    override var includedInApk: Boolean
        set(value) = includeInApkValue.set(value)
        get() = includeInApkValue.get()

    override var includedInBundle: Boolean
        set(value) = includeInBundleValue.set(value)
        get() = includeInBundleValue.get()
}

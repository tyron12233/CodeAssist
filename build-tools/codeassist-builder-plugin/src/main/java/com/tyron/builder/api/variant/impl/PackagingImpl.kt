package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.Packaging
import com.tyron.builder.gradle.internal.services.VariantServices

open class PackagingImpl(
    dslPackagingOptions: com.tyron.builder.api.dsl.PackagingOptions,
    variantServices: VariantServices
) : Packaging {

    override val jniLibs =
        JniLibsPackagingImpl(dslPackagingOptions, variantServices)

    override val resources =
        ResourcesPackagingImpl(dslPackagingOptions, variantServices)
}
package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.ApkPackaging
import com.tyron.builder.gradle.internal.services.VariantServices

class ApkPackagingImpl(
    dslPackagingOptions: com.tyron.builder.api.dsl.PackagingOptions,
    variantServices: VariantServices,
    minSdk: Int
) : PackagingImpl(dslPackagingOptions, variantServices), ApkPackaging {

    override val dex =
        DexPackagingOptionsImpl(dslPackagingOptions, variantServices, minSdk)

    override val jniLibs =
        JniLibsApkPackagingImpl(dslPackagingOptions, variantServices, minSdk)

    override val resources =
        ResourcesApkPackagingImpl(dslPackagingOptions, variantServices)
}

package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.PackagingOptions
import com.tyron.builder.gradle.internal.services.VariantServices

class ResourcesApkPackagingImpl(
    dslPackagingOptions: PackagingOptions,
    variantServices: VariantServices
) : ResourcesPackagingImpl(dslPackagingOptions, variantServices) {

    override val excludes =
        variantServices.setPropertyOf(String::class.java) {
            // exclude .kotlin_module files from APKs (b/152898926)
            getBaseExcludes().plus("/META-INF/*.kotlin_module")
        }
}

package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.PackagingOptions
import com.tyron.builder.api.variant.JniLibsPackaging
import com.tyron.builder.gradle.internal.packaging.defaultExcludes
import com.tyron.builder.gradle.internal.services.VariantServices

open class JniLibsPackagingImpl(
    dslPackagingOptions: PackagingOptions,
    variantServices: VariantServices
) : JniLibsPackaging {

    override val excludes =
        variantServices.setPropertyOf(String::class.java) {
            // subtract defaultExcludes because its patterns are specific to java resources.
            dslPackagingOptions.excludes
                .minus(defaultExcludes)
                .union(dslPackagingOptions.jniLibs.excludes)
        }

    override val pickFirsts =
        variantServices.setPropertyOf(String::class.java) {
            dslPackagingOptions.pickFirsts.union(dslPackagingOptions.jniLibs.pickFirsts)
        }

    override val keepDebugSymbols =
        variantServices.setPropertyOf(String::class.java) {
            dslPackagingOptions.doNotStrip.union(dslPackagingOptions.jniLibs.keepDebugSymbols)
        }
}
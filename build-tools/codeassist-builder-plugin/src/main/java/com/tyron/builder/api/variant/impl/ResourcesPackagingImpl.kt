package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.PackagingOptions
import com.tyron.builder.api.variant.ResourcesPackaging
import com.tyron.builder.gradle.internal.packaging.defaultExcludes
import com.tyron.builder.gradle.internal.packaging.defaultMerges
import com.tyron.builder.gradle.internal.services.VariantServices

open class ResourcesPackagingImpl(
    private val dslPackagingOptions: PackagingOptions,
    variantServices: VariantServices
) : ResourcesPackaging {

    override val excludes =
        variantServices.setPropertyOf(String::class.java) { getBaseExcludes() }

    override val pickFirsts =
        variantServices.setPropertyOf(String::class.java) {
            dslPackagingOptions.pickFirsts.union(dslPackagingOptions.resources.pickFirsts)
        }

    override val merges =
        variantServices.setPropertyOf(String::class.java) {
            // the union of dslPackagingOptions.merges and dslPackagingOptions.resources.merges,
            // minus the default patterns removed from either of them.
            dslPackagingOptions.merges
                .union(dslPackagingOptions.resources.merges)
                .minus(
                    defaultMerges.subtract(dslPackagingOptions.merges)
                        .union(defaultMerges.subtract(dslPackagingOptions.resources.merges))
                )
        }

    // the union of dslPackagingOptions.excludes and dslPackagingOptions.resources.excludes, minus
    // the default patterns removed from either of them.
    protected fun getBaseExcludes(): Set<String> =
        dslPackagingOptions.excludes
            .union(dslPackagingOptions.resources.excludes)
            .minus(
                defaultExcludes.subtract(dslPackagingOptions.excludes)
                    .union(defaultExcludes.subtract(dslPackagingOptions.resources.excludes))
            )
}
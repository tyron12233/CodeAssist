package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.PackagingOptions
import com.tyron.builder.api.variant.DexPackagingOptions
import com.tyron.builder.gradle.internal.services.VariantServices
import com.android.sdklib.AndroidVersion.VersionCodes.P

class DexPackagingOptionsImpl(
    dslPackagingOptions: PackagingOptions,
    variantServices: VariantServices,
    minSdk: Int
) : DexPackagingOptions {

    // Default to false for P+ because uncompressed dex files yield smaller installation sizes
    // because ART doesn't need to store an extra uncompressed copy on disk.
    override val useLegacyPackaging =
            variantServices.propertyOf(
                    Boolean::class.java,
                    dslPackagingOptions.dex.useLegacyPackaging ?: (minSdk < P)
            )
}

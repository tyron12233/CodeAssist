package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.PackagingOptions
import com.tyron.builder.api.variant.JniLibsApkPackaging
import com.tyron.builder.gradle.internal.services.VariantServices
import com.android.sdklib.AndroidVersion.VersionCodes.M

class JniLibsApkPackagingImpl(
    dslPackagingOptions: PackagingOptions,
    variantServices: VariantServices,
    minSdk: Int
) : JniLibsPackagingImpl(dslPackagingOptions, variantServices),
    JniLibsApkPackaging {

    override val useLegacyPackaging =
        variantServices.provider {
            dslPackagingOptions.jniLibs.useLegacyPackaging ?: (minSdk < M)
        }

    override val useLegacyPackagingFromBundle =
        variantServices.provider {
            dslPackagingOptions.jniLibs.useLegacyPackaging ?: false
        }
}

package com.tyron.builder.gradle.internal.ide

import com.tyron.builder.model.VariantBuildInformation
import java.io.Serializable

data class VariantBuildInformationImp(override val variantName: String,
    override val assembleTaskName: String,
    override val assembleTaskOutputListingFile: String?,
    override val bundleTaskName: String?,
    override val bundleTaskOutputListingFile: String?,
    override val apkFromBundleTaskName: String?,
    override val apkFromBundleTaskOutputListingFile: String?
): VariantBuildInformation, Serializable {
}
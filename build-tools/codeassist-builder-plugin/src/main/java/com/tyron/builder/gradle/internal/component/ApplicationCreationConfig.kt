package com.tyron.builder.gradle.internal.component

import com.tyron.builder.api.variant.impl.BundleConfigImpl
import com.tyron.builder.gradle.internal.dsl.NdkOptions

interface ApplicationCreationConfig: ApkCreationConfig, VariantCreationConfig, PublishableCreationConfig {
    val profileable: Boolean
    val consumesFeatureJars: Boolean
    val needAssetPackTasks: Boolean
    val nativeDebugSymbolLevel: NdkOptions.DebugSymbolLevel
    val isWearAppUnbundled: Boolean?
    override val bundleConfig: BundleConfigImpl
}
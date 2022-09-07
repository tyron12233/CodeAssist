package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.internal.core.dsl.DynamicFeatureVariantDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalDynamicFeatureExtension
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.profile.ProfilingMode
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.StringOption
import com.tyron.builder.core.ComponentType
import org.gradle.api.file.DirectoryProperty

internal class DynamicFeatureVariantDslInfoImpl(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    dataProvider: ManifestDataProvider,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    extension: InternalDynamicFeatureExtension
) : TestedVariantDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    dataProvider,
    services,
    buildDirectory,
    extension
), DynamicFeatureVariantDslInfo {

    // TODO: Dynamic feature variant doesn't have isDebuggable dsl in the build type, we should only
    //  have `debug` variants be debuggable
    override val isDebuggable: Boolean
        get() = ProfilingMode.getProfilingModeType(
            services.projectOptions[StringOption.PROFILING_MODE]
        ).isDebuggable
            ?: (buildTypeObj as? ApplicationBuildType)?.isDebuggable
            ?: false

    override val signingConfig: SigningConfig? = null
    override val isSigningReady: Boolean = false
    override val isMultiDexSetFromDsl: Boolean
        get() = (buildTypeObj as? ApplicationBuildType)?.multiDexEnabled != null
}

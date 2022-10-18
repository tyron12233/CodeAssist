package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.LibraryVariantDimension
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.internal.core.MergedAarMetadata
import com.tyron.builder.gradle.internal.core.dsl.LibraryVariantDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalLibraryExtension
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.profile.ProfilingMode
import com.tyron.builder.gradle.internal.publishing.VariantPublishingInfo
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.StringOption
import com.tyron.builder.core.ComponentType
import org.gradle.api.file.DirectoryProperty

internal class LibraryVariantDslInfoImpl internal constructor(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    dataProvider: ManifestDataProvider,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    override val publishInfo: VariantPublishingInfo,
    extension: InternalLibraryExtension
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
), LibraryVariantDslInfo {

    override val aarMetadata = MergedAarMetadata()

    init {
        mergeOptions()
    }

    private fun mergeOptions() {
        computeMergedOptions(
            aarMetadata,
            { (this as LibraryVariantDimension).aarMetadata },
            { (this as LibraryVariantDimension).aarMetadata }
        )
    }

    // TODO: Library variant doesn't have isDebuggable dsl in the build type, we should only have
    //  `debug` variants be debuggable
    override val isDebuggable: Boolean
        get() = ProfilingMode.getProfilingModeType(
            services.projectOptions[StringOption.PROFILING_MODE]
        ).isDebuggable
            ?: (buildTypeObj as? com.tyron.builder.gradle.internal.dsl.BuildType)?.isDebuggable
            ?: false
}

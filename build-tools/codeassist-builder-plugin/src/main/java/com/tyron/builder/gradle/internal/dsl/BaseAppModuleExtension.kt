package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ComposeOptions
import com.tyron.builder.core.LibraryRequest
import com.tyron.builder.gradle.AppExtension
import com.tyron.builder.gradle.api.AndroidSourceSet
import com.tyron.builder.gradle.api.BaseVariantOutput
import com.tyron.builder.gradle.internal.ExtraModelInfo
import com.tyron.builder.gradle.internal.dependency.SourceSetManager
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfig
import org.gradle.api.NamedDomainObjectContainer

/** The `android` extension for base feature module (application plugin).  */
open class BaseAppModuleExtension(
    dslServices: DslServices,
    bootClasspathConfig: BootClasspathConfig,
    buildOutputs: NamedDomainObjectContainer<BaseVariantOutput>,
    sourceSetManager: SourceSetManager,
    extraModelInfo: ExtraModelInfo,
    private val publicExtensionImpl: ApplicationExtensionImpl
) : AppExtension(
    dslServices,
    bootClasspathConfig,
    buildOutputs,
    sourceSetManager,
    extraModelInfo,
    true
), InternalApplicationExtension by publicExtensionImpl {

    // Overrides to make the parameterized types match, due to BaseExtension being part of
    // the previous public API and not wanting to paramerterize that.
    override val buildTypes: NamedDomainObjectContainer<BuildType>
        get() = publicExtensionImpl.buildTypes as NamedDomainObjectContainer<BuildType>
    override val defaultConfig: DefaultConfig
        get() = publicExtensionImpl.defaultConfig as DefaultConfig
    override val productFlavors: NamedDomainObjectContainer<ProductFlavor>
        get() = publicExtensionImpl.productFlavors as NamedDomainObjectContainer<ProductFlavor>
    override val sourceSets: NamedDomainObjectContainer<AndroidSourceSet>
        get() = publicExtensionImpl.sourceSets

    override val composeOptions: ComposeOptions = publicExtensionImpl.composeOptions

//    override val bundle: BundleOptions = publicExtensionImpl.bundle as BundleOptions

    override val flavorDimensionList: MutableList<String>
        get() = flavorDimensions

//    override val buildToolsRevision: Revision
//        get() = Revision.parseRevision(buildToolsVersion, Revision.Precision.MICRO)

    override val libraryRequests: MutableCollection<LibraryRequest>
        get() = publicExtensionImpl.libraryRequests
}

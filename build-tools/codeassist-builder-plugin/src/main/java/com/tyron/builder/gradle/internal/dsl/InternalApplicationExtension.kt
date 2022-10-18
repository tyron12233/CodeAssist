package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.*
import org.gradle.api.Action

/** See [InternalCommonExtension] */
interface InternalApplicationExtension :
    ApplicationExtension,
    InternalTestedExtension<
                ApplicationBuildFeatures,
                ApplicationBuildType,
                ApplicationDefaultConfig,
                ApplicationProductFlavor> {
    override val dynamicFeatures: MutableSet<String>
    fun setDynamicFeatures(dynamicFeatures: Set<String>)
    override val assetPacks: MutableSet<String>
    fun setAssetPacks(assetPacks: Set<String>)

    // See GroovyBlockInExtensionsTest
    fun bundle(action: Action<BundleOptions>)
    fun dependenciesInfo(action: Action<DependenciesInfo>)
    fun publishing(action: Action<ApplicationPublishing>)
}
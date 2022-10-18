package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

interface AssetPackBundleExtension {
    @get:Incubating
    @set:Incubating
    var applicationId: String

    @get:Incubating
    @set:Incubating
    var compileSdk: Int

    @get:Incubating
    @set:Incubating
    var versionTag: String

    @get:Incubating
    val versionCodes: MutableSet<Int>

    @get:Incubating
    val assetPacks: MutableSet<String>

    @get:Incubating
    val signingConfig: SigningConfig
    @Incubating
    fun signingConfig(action: SigningConfig.() -> Unit)

    @get:Incubating
    val texture: BundleTexture
    @Incubating
    fun texture(action: BundleTexture.() -> Unit)

    @get:Incubating
    val deviceTier: BundleDeviceTier
    @Incubating
    fun deviceTier(action: BundleDeviceTier.() -> Unit)
}

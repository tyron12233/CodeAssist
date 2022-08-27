package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.file.DirectoryProperty

/** Features that apply to distribution by the bundle  */
interface Bundle {

    val abi: BundleAbi

    val density: BundleDensity

    val language: BundleLanguage

    val texture: BundleTexture

    @get:Incubating
    val deviceTier: BundleDeviceTier

    @get:Incubating
    val codeTransparency: BundleCodeTransparency

    val storeArchive: BundleStoreArchive

    @get:Incubating
    val integrityConfigDir: DirectoryProperty

    fun abi(action: BundleAbi.() -> Unit)

    fun density(action: BundleDensity.() -> Unit)

    fun language(action: BundleLanguage.() -> Unit)

    fun texture(action: BundleTexture.() -> Unit)

    @Incubating
    fun deviceTier(action: BundleDeviceTier.() -> Unit)

    @Incubating
    fun codeTransparency(action: BundleCodeTransparency.() -> Unit)

    fun storeArchive(action: BundleStoreArchive.() -> Unit)
}
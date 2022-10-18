package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.Bundle
import com.tyron.builder.api.dsl.BundleCodeTransparency
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty

/** Features that apply to distribution by the bundle  */
abstract class BundleOptions : Bundle {

    abstract override val abi: BundleOptionsAbi
    abstract override val density: BundleOptionsDensity
    abstract override val language: BundleOptionsLanguage
    abstract override val texture: BundleOptionsTexture
    abstract override val deviceTier: BundleOptionsDeviceTier
    abstract override val codeTransparency: BundleCodeTransparency
    abstract override val storeArchive: BundleOptionsStoreArchive

    abstract override val integrityConfigDir: DirectoryProperty

    abstract fun abi(action: Action<BundleOptionsAbi>)
    abstract fun density(action: Action<BundleOptionsDensity>)
    abstract fun language(action: Action<BundleOptionsLanguage>)
    abstract fun texture(action: Action<BundleOptionsTexture>)
    abstract fun deviceTier(action: Action<BundleOptionsDeviceTier>)
    abstract fun storeArchive(action: Action<BundleOptionsStoreArchive>)
}

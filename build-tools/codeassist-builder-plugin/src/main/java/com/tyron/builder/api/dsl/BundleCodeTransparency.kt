package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

@Incubating
interface BundleCodeTransparency {

    /**
     * Specifies the signing configuration for the code transparency feature of `bundletool`.
     *
     * When the [SigningConfig] has all necessary values set, it will be used for signing
     * non-debuggable bundles using code transparency.
     *
     */
    val signing: SigningConfig

    /**
     * Specifies the signing configuration for the code transparency feature of `bundletool`.
     *
     * When the [SigningConfig] has all necessary values set, it will be used for signing
     * non-debuggable bundles using code transparency.
     *
     */
    fun signing(action: SigningConfig.() -> Unit)
}
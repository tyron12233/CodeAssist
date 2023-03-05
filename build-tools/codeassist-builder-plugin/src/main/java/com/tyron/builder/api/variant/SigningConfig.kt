package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.provider.Property

/**
 * Defines a variant's signing config.
 */
interface SigningConfig {

    /**
     * Sets the [com.tyron.builder.api.dsl.SigningConfig] with information on how to retrieve
     * the signing configuration.
     */
    @Incubating
    fun setConfig(signingConfig: com.tyron.builder.api.dsl.SigningConfig)

    /**
     * Enable signing using JAR Signature Scheme (aka v1 signing).
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     *
     * This property will override any value set using the corresponding DSL.
     */
    val enableV1Signing: Property<Boolean>

    /**
     * Enable signing using APK Signature Scheme v2 (aka v2 signing).
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     *
     * This property will override any value set using the corresponding DSL.
     */
    val enableV2Signing: Property<Boolean>

    /**
     * Enable signing using APK Signature Scheme v3 (aka v3 signing).
     *
     * See [APK Signature Scheme v3](https://source.android.com/security/apksigning/v3)
     *
     * This property will override any value set using the corresponding DSL.
     */
    val enableV3Signing: Property<Boolean>

    /**
     * Enable signing using APK Signature Scheme v4 (aka v4 signing).
     *
     * This property will override any value set using the corresponding DSL.
     */
    val enableV4Signing: Property<Boolean>
}

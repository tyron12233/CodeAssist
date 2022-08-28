package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import java.io.File

/**
 * DSL object for configuring options related to signing for APKs and bundles.
 *
 * [ApkSigningConfig] extends this with options relating to just APKs
 *
 */
interface SigningConfig {

    /**
     * Store file used when signing.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    @get:Incubating
    @set:Incubating
    var storeFile: File?

    /**
     * Store password used when signing.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    @get:Incubating
    @set:Incubating
    var storePassword: String?

    /**
     * Key alias used when signing.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    @get:Incubating
    @set:Incubating
    var keyAlias: String?

    /**
     * Key password used when signing.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    @get:Incubating
    @set:Incubating
    var keyPassword: String?

    /**
     * Store type used when signing.
     *
     * See [Signing Your Applications](http://developer.android.com/tools/publishing/app-signing.html)
     */
    @get:Incubating
    @set:Incubating
    var storeType: String?

    /**
     * Copies all properties from the given signing config.
     */
    @Incubating
    fun initWith(that: SigningConfig)
}
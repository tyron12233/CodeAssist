package com.tyron.builder.model

import java.io.File

/**
 * A Signing Configuration.
 *
 * This is an interface for the gradle tooling api, and should only be used from Android Studio.
 * It is not part of the DSL & API interfaces of the Android Gradle Plugin.
 */
interface SigningConfig {
    /** Returns the name of the Signing config */
    fun getName(): String

    /** The keystore file. */
    val storeFile: File?

    /** The keystore password. */
    val storePassword: String?

    /** The key alias name. */
    val keyAlias: String?

    /** The key password. */
    val keyPassword: String?

    /** The store type. */
    val storeType: String?

    /** Signing using JAR Signature Scheme (aka v1 signing) is enabled. */
    @Deprecated("This property is deprecated")
    val isV1SigningEnabled: Boolean

    /** Signing using APK Signature Scheme v2 (aka v2 signing) is enabled. */
    @Deprecated("This property is deprecated")
    val isV2SigningEnabled: Boolean

    /**
     * Whether the config is fully configured for signing.
     *
     * i.e. all the required information are present.
     */
    val isSigningReady: Boolean
}
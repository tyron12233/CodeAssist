package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.dsl.SigningConfig
import java.io.File
import java.io.Serializable

/**
 * Implementation of [SigningConfig] for serialization via the Tooling API.
 */
data class SigningConfigImpl(
    override val name: String,
    override val storeFile: File?,
    override val storePassword: String?,
    override val keyAlias: String?,
    override val keyPassword: String?,
    override val enableV1Signing: Boolean?,
    override val enableV2Signing: Boolean?,
    override val enableV3Signing: Boolean?,
    override val enableV4Signing: Boolean?
) : SigningConfig, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }

    override val isSigningReady: Boolean
        get() = storeFile != null &&
                storePassword != null &&
                keyAlias != null &&
                keyPassword != null
}

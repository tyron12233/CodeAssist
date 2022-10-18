package com.tyron.builder.gradle.internal.signing
import java.io.Serializable


/** Class containing information about which signature versions are enabled or disabled.  */
data class SigningConfigVersions(
    val enableV1Signing: Boolean,
    val enableV2Signing: Boolean,
    val enableV3Signing: Boolean,
    val enableV4Signing: Boolean,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        // The lowest API with v2 signing support
        const val MIN_V2_SDK = 24
        // The lowest API with v3 signing support
        const val MIN_V3_SDK = 28
        // The lowest API with v4 signing support
        const val MIN_V4_SDK = 30
    }
}
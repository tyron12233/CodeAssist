package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.SigningConfig
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.signing.SigningConfigVersions
import org.gradle.api.provider.Provider
import java.io.File
import java.util.concurrent.Callable

class SigningConfigImpl(
    signingConfig: com.tyron.builder.gradle.internal.dsl.SigningConfig?,
    variantServices: VariantServices,
    minSdk: Int,
    targetApi: Int?
) : SigningConfig {

    private var dslSigningConfig = signingConfig

    val name: String?
        get() = dslSigningConfig?.name

    override fun setConfig(signingConfig: com.tyron.builder.api.dsl.SigningConfig) {
        dslSigningConfig = signingConfig as com.tyron.builder.gradle.internal.dsl.SigningConfig
    }

    fun hasConfig() = dslSigningConfig!=null

    override val enableV4Signing =
        variantServices.propertyOf(
            Boolean::class.java,
            Callable {
                when {
                    // Don't sign with v4 if we're installing on a device that doesn't support it.
                    targetApi != null && targetApi < SigningConfigVersions.MIN_V4_SDK -> false
                    // Otherwise, sign with v4 if it's enabled explicitly.
                    else -> dslSigningConfig?.enableV4Signing ?: false
                }
            },
        )

    override val enableV3Signing =
        variantServices.propertyOf(
            Boolean::class.java,
            Callable {
                when {
                    // Don't sign with v3 if we're installing on a device that doesn't support it.
                    targetApi != null && targetApi < SigningConfigVersions.MIN_V3_SDK -> false
                    // Otherwise, sign with v3 if it's enabled explicitly.
                    else -> dslSigningConfig?.enableV3Signing ?: false
                }
            },
        )

    override val enableV2Signing =
        variantServices.propertyOf(
            Boolean::class.java,
            Callable {
                val enableV2Signing = dslSigningConfig?.enableV2Signing
                val effectiveMinSdk = targetApi ?: minSdk
                return@Callable when {
                    // Don't sign with v2 if we're installing on a device that doesn't support it.
                    targetApi != null && targetApi < SigningConfigVersions.MIN_V2_SDK -> false
                    // Otherwise, sign with v2 if it's enabled explicitly.
                    enableV2Signing != null -> enableV2Signing
                    // We sign with v2 if minSdk < MIN_V3_SDK, even if we're also signing with v1,
                    // because v2 signatures can be verified faster than v1 signatures.
                    effectiveMinSdk < SigningConfigVersions.MIN_V3_SDK -> true
                    // If minSdk >= MIN_V3_SDK, sign with v2 only if we're not signing with v3.
                    else -> !enableV3Signing.get()
                }
            },
        )

    override val enableV1Signing =
        variantServices.propertyOf(
            Boolean::class.java,
            Callable {
                val enableV1Signing = dslSigningConfig?.enableV1Signing
                val effectiveMinSdk = targetApi ?: minSdk
                return@Callable when {
                    // Sign with v1 if it's enabled explicitly.
                    enableV1Signing != null -> enableV1Signing
                    // We need v1 if minSdk < MIN_V2_SDK.
                    effectiveMinSdk < SigningConfigVersions.MIN_V2_SDK -> true
                    // We don't need v1 if minSdk >= MIN_V2_SDK and we're signing with v2.
                    enableV2Signing.get() -> false
                    // We need v1 if we're not signing with v2 and minSdk < MIN_V3_SDK.
                    effectiveMinSdk < SigningConfigVersions.MIN_V3_SDK -> true
                    // If minSdk >= MIN_V3_SDK, sign with v1 only if we're not signing with v3.
                    else -> !enableV3Signing.get()
                }
            },
        )

    //-----------------------------------------------------
    // Internal APIs
    //------------------------------------------------------
    val storeFile: Provider<File?> =
        variantServices.provider { dslSigningConfig?.storeFile }

    val storePassword: Provider<String?> =
        variantServices.provider { dslSigningConfig?.storePassword }

    val keyAlias: Provider<String?> =
        variantServices.provider { dslSigningConfig?.keyAlias }

    val keyPassword: Provider<String?> =
        variantServices.provider { dslSigningConfig?.keyPassword }

    val storeType: Provider<String?> =
        variantServices.provider { dslSigningConfig?.storeType }

    fun isSigningReady(): Boolean {
        return storeFile.isPresent &&
            storePassword.isPresent &&
            keyAlias.isPresent &&
            keyPassword.isPresent
    }
}
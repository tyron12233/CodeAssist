package com.tyron.builder.gradle.internal.packaging

import com.android.ide.common.signing.KeystoreHelper
import com.android.ide.common.signing.KeytoolException
import com.android.prefs.AndroidLocationsException
import com.android.prefs.AndroidLocationsProvider
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.signing.SigningConfigData
import com.tyron.builder.signing.DefaultSigningConfig
import org.gradle.api.InvalidUserDataException
import org.gradle.api.logging.Logger
import java.io.File
import java.io.IOException
import java.security.KeyStore

fun AndroidLocationsProvider.getDefaultDebugKeystoreLocation(): File = try {
    KeystoreHelper.defaultDebugKeystoreLocation(this)
} catch (e: AndroidLocationsException) {
    throw InvalidUserDataException("Failed to get default debug keystore location.", e)
}

fun AndroidLocationsProvider.getDefaultDebugKeystoreSigningConfig(): SigningConfigData =
    SigningConfigData(
        name = BuilderConstants.DEBUG,
        keyAlias = DefaultSigningConfig.DEFAULT_ALIAS,
        keyPassword = DefaultSigningConfig.DEFAULT_PASSWORD,
        storePassword = DefaultSigningConfig.DEFAULT_PASSWORD,
        storeType = KeyStore.getDefaultType(),
        storeFile = getDefaultDebugKeystoreLocation(),
    )


@Throws(IOException::class)
fun createDefaultDebugStore(defaultDebugKeystoreLocation: File, logger: Logger) {
    val signingConfig = DefaultSigningConfig.DebugSigningConfig(defaultDebugKeystoreLocation)
    logger.info(
        "Creating default debug keystore at {}",
        defaultDebugKeystoreLocation.absolutePath)
    try {
        if (!KeystoreHelper.createDebugStore(
                signingConfig.storeType,
                signingConfig.storeFile,
                signingConfig.storePassword,
                signingConfig.keyPassword,
                signingConfig.keyAlias,
                LoggerWrapper(logger))) {
            throw IOException("Unable to create missing debug keystore.")
        }
    } catch (e: KeytoolException) {
        throw IOException(e)
    }
}

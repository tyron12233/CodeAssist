package com.tyron.builder.gradle.internal.utils

import com.android.utils.EnvironmentProvider
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/** Gradle-specific APIs for accessing system properties and environment variables. */
interface GradleEnvironmentProvider {
    fun getSystemProperty(key: String): Provider<String>
    fun getEnvVariable(key: String): Provider<String>
}

/**
 * Provides environment variables and system properties by using [ProviderFactory] APIs.
 */
class GradleEnvironmentProviderImpl(private val providerFactory: ProviderFactory) :
    GradleEnvironmentProvider {

    override fun getSystemProperty(key: String): Provider<String> {
        return providerFactory.systemProperty(key)
    }

    override fun getEnvVariable(key: String): Provider<String> {
        return providerFactory.environmentVariable(key)
    }
}

/** Implementation of [EnvironmentProvider] interface w/o dependencies on Gradle APIs. */
class EnvironmentProviderImpl(private val gradleEnvironmentProvider: GradleEnvironmentProvider) :
    EnvironmentProvider {
    override fun getSystemProperty(key: String): String? {
        return gradleEnvironmentProvider.getSystemProperty(key).orNull
    }

    override fun getEnvVariable(key: String): String? {
        return gradleEnvironmentProvider.getEnvVariable(key).orNull
    }
}

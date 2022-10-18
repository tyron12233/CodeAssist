package com.tyron.builder.gradle.internal.fusedlibrary

import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage

/**
 * Scope object that contains all the configurations for the fused-library plugin.
 */
class FusedLibraryConfigurations {

    private val configurations= mutableListOf<Configuration>()

    fun addConfiguration(configuration: Configuration) {
        synchronized(configurations) {
            configurations.add(configuration)
        }
    }

    fun getConfiguration(usage: String): Configuration {
        synchronized(configurations) {
            configurations.forEach { configuration ->
                if (configuration.attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name == usage)
                    return configuration
            }
        }
        throw IllegalArgumentException("No configuration found with usage $usage")
    }
}

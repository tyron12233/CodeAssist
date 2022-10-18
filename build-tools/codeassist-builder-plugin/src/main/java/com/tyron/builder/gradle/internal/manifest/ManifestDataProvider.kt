package com.tyron.builder.gradle.internal.manifest

import org.gradle.api.provider.Provider

/**
 * A class that can provide a [Provider] of [ManifestData]
 */
interface ManifestDataProvider {
    val manifestLocation: String
    val manifestData: Provider<ManifestData>
}
package com.tyron.builder.api.variant

import org.gradle.api.provider.Property

/**
 * Defines an APK variant's packaging options for dex files.
 */
interface DexPackagingOptions {

    /**
     * Whether to use the legacy convention of compressing all dex files in the APK.
     *
     * This property does not affect dex file compression in APKs produced from app bundles.
     */
    val useLegacyPackaging: Property<Boolean>
}
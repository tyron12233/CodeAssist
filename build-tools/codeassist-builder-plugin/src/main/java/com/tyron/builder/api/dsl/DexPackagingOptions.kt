package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/** Packaging options for Dex (Android Dalvik Executable) files */
@Incubating
interface DexPackagingOptions {
    /**
     * Whether to use the legacy convention of compressing all dex files in the APK. If null, dex
     * files will be uncompressed when minSdk >= 28.
     *
     * This property does not affect dex file compression in APKs produced from app bundles.
     */
    var useLegacyPackaging: Boolean?
}
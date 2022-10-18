package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/** Packaging options for native library (.so) files */
@Incubating
interface JniLibsPackagingOptions {
    /**
     * Whether to use the legacy convention of compressing all .so files in the APK. If null, .so
     * files will be uncompressed and page-aligned when minSdk >= 23.
     */
    var useLegacyPackaging: Boolean?

    /**
     * The set of excluded patterns. Native libraries matching any of these patterns do not get
     * packaged.
     *
     * Example: `android.packagingOptions.jniLibs.excludes += "**`/`exclude.so"`
     */
    val excludes: MutableSet<String>

    /**
     * The set of patterns where the first occurrence is packaged in the APK. For each native
     * library APK entry path matching one of these patterns, only the first native library found
     * with that path gets packaged.
     *
     * Example: `android.packagingOptions.jniLibs.pickFirsts += "**`/`pickFirst.so"`
     */
    val pickFirsts: MutableSet<String>

    /**
     * The set of patterns for native libraries that should not be stripped of debug symbols.
     *
     * Example: `android.packagingOptions.jniLibs.keepDebugSymbols += "**`/`doNotStrip.so"`
     */
    val keepDebugSymbols: MutableSet<String>
}
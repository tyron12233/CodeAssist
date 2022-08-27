package com.tyron.builder.api.variant

import org.gradle.api.provider.SetProperty

/**
 * Defines a variant's packaging options for native library (.so) files.
 */
interface JniLibsPackaging {

    /**
     * The set of excluded patterns. Native libraries matching any of these patterns do not get
     * packaged.
     *
     * Example usage: `packaging.jniLibs.excludes.add("**`/`exclude.so")`
     */
    val excludes: SetProperty<String>

    /**
     * The set of patterns for which the first occurrence is packaged in the APK. For each native
     * library APK entry path matching one of these patterns, only the first native library found
     * with that path gets packaged.
     *
     * Example usage: `packaging.jniLibs.pickFirsts.add("**`/`pickFirst.so")`
     */
    val pickFirsts: SetProperty<String>

    /**
     * The set of patterns for native libraries that should not be stripped of debug symbols.
     *
     * Example: `packaging.jniLibs.keepDebugSymbols.add("**`/`doNotStrip.so")`
     */
    val keepDebugSymbols: SetProperty<String>
}
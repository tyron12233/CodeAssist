package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * DSL object for configuring per-abi splits options.
 *
 * See [FULL_APK Splits](https://developer.android.com/studio/build/configure-apk-splits.html).
 */
interface AbiSplit : Split {
    /** Whether to create an FULL_APK with all available ABIs. */
    @get:Incubating
    @set:Incubating
    var isUniversalApk: Boolean
}
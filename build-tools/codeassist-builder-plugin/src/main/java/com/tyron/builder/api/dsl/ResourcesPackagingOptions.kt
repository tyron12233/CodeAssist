package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/** Packaging options for java resource files */
@Incubating
interface ResourcesPackagingOptions {
    /**
     * The set of excluded patterns. Java resources matching any of these patterns do not get
     * packaged in the APK.
     *
     * Example: `android.packagingOptions.resources.excludes += "**`/`*.exclude"`
     */
    val excludes: MutableSet<String>

    /**
     * The set of patterns for which the first occurrence is packaged in the APK. For each java
     * resource APK entry path matching one of these patterns, only the first java resource found
     * with that path gets packaged in the APK.
     *
     * Example: `android.packagingOptions.resources.pickFirsts += "**`/`*.pickFirst"`
     */
    val pickFirsts: MutableSet<String>

    /**
     * The set of patterns for which matching java resources are merged. For each java resource
     * APK entry path matching one of these patterns, all java resources with that path are
     * concatenated and packaged as a single entry in the APK.
     *
     * Example: `android.packagingOptions.resources.merges += "**`/`*.merge"`
     */
    val merges: MutableSet<String>
}
package com.tyron.builder.api.variant

import org.gradle.api.provider.SetProperty

interface ResourcesPackaging {

    /**
     * The set of excluded patterns. Java resources matching any of these patterns do not get
     * packaged in the APK.
     *
     * Example usage: `packagingOptions.resources.excludes.add("**`/`*.exclude")`
     */
    val excludes: SetProperty<String>

    /**
     * The set of patterns for which the first occurrence is packaged in the APK. For each java
     * resource APK entry path matching one of these patterns, only the first java resource found
     * with that path gets packaged in the APK.
     *
     * Example usage: `packagingOptions.resources.pickFirsts.add("**`/`*.pickFirst")`
     */
    val pickFirsts: SetProperty<String>

    /**
     * The set of patterns for which matching java resources are merged. For each java resource
     * APK entry path matching one of these patterns, all java resources with that path are
     * concatenated and packaged as a single entry in the APK.
     *
     * Example usage: `packagingOptions.resources.merges.add("**`/`*.merge")`
     */
    val merges: SetProperty<String>
}
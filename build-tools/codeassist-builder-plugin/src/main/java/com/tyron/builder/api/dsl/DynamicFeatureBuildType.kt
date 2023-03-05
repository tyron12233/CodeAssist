package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Build types define certain properties that Gradle uses when building and packaging your app, and
 * are typically configured for different stages of your development lifecycle.

 * Dynamic features must have exactly the same build types (by name) as the app that
 * includes them, however settings can be different between the application and the dynamic feature.
 * Properties on dynamic feature build types fall in to three categories.
 *
 * * Properties global to the application that affect the build flow, and so must be explicitly set
 *   in the dynamic feature.
 *   For example, whether the build type is debuggable must match the application that includes this
 *   dynamic feature.
 * * Properties global to the application that do not affect the build flow. These are set in the
 *   `com.android.application` project, and are automatically configured on the dynamic feature,
 *   they cannot be set on the dynamic feature.
 *   For example, application ID suffix and signing cannot be configured on the dynamic feature and
 *   are not present on this interface.
 * * Properties that can vary between the app and the dynamic feature.
 *   For example, `resValues` can be used independently from the app in a dynamic feature.
 *
 * See [ApplicationProductFlavor]
 */
interface DynamicFeatureBuildType :
    BuildType,
    DynamicFeatureVariantDimension {
    /**
     * Whether to crunch PNGs.
     *
     * Setting this property to `true` reduces of PNG resources that are not already
     * optimally compressed. However, this process increases build times.
     *
     * PNG crunching is enabled by default in the release build type and disabled by default in
     * the debug build type.
     */
    @get:Incubating
    @set:Incubating
    var isCrunchPngs: Boolean?
}
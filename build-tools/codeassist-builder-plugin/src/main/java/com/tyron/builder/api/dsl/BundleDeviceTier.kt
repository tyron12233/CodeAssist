package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

@Incubating
interface BundleDeviceTier {
    var enableSplit: Boolean?

    /**
     * Specifies the default device tier value for the bundle. Used for local-testing and generating
     * universal APKs.
     *
     */
    var defaultTier: String?
}
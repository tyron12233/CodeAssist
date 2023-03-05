package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

@Incubating
interface FusedLibraryExtension {

    @get: Incubating
    @set: Incubating
    var namespace: String?

    /**
     * For basic validation that all included libraries in the fused library are at least the minSdk.
     * Eventually (b/229956178) this value should be able to be automatically determined by the plugin.
     */
    @get: Incubating
    @set: Incubating
    var minSdk: Int?
}
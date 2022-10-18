package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Build types define certain properties that Gradle uses when building and packaging your library,
 * and are typically configured for different stages of your development lifecycle.
 *
 * Test projects have a target application project that they depend on and build type matching works
 * in the same way as library dependencies. Therefore to test multiple build types of an
 * application you can declare corresponding build types here.
 *
 * See
 * [configuring build types](https://developer.android.com/studio/build#build-config)
 * for more information.
 */
interface TestBuildType :
    BuildType,
    TestVariantDimension {
    /** Whether this build type should generate a debuggable apk. */
    @get:Incubating
    @set:Incubating
    var isDebuggable: Boolean

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

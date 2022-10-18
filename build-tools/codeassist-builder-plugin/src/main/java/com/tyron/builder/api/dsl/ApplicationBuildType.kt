package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Build types define certain properties that Gradle uses when building and packaging your app, and
 * are typically configured for different stages of your development lifecycle.
 *
 * There are two build types defined by default, `debug` and `release`, and you can customize them
 * and create additional build types.
 *
 * The default debug build type enables debug options and signs the APK with the debug
 * key, while the release build type is not debuggable and can be configured to shrink, obfuscate,
 * and sign your APK with a release key for distribution.
 *
 * See
 * [configuring build types](https://developer.android.com/studio/build#build-config)
 * for more information.
 */
interface ApplicationBuildType :
    BuildType,
    ApplicationVariantDimension {
    /** Whether this build type should generate a debuggable apk. */
    @get:Incubating
    @set:Incubating
    var isDebuggable: Boolean

    /**
     * Whether a linked Android Wear app should be embedded in variant using this build type.
     *
     * Wear apps can be linked with the following code:
     *
     * ```
     * dependencies {
     *     freeWearApp project(:wear:free') // applies to variant using the free flavor
     *     wearApp project(':wear:base') // applies to all other variants
     * }
     * ```
     */
    @get:Incubating
    @set:Incubating
    var isEmbedMicroApp: Boolean

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

    /** Whether this product flavor should be selected in Studio by default  */
    var isDefault: Boolean

    /**
     * Intended to produce an APK that leads to more accurate profiling.
     *
     * Enabling this option will declare the application as profileable in the AndroidManifest.
     *
     * Profileable build types will be signed with the default debug signing config if no other
     * signing config is specified.
     *
     * This option doesn't make sense to combine with isDebuggable=true.
     * If a build type is set to be both debuggable and profileable the build system will log a
     * warning.
     */
    @get:Incubating
    @set:Incubating
    var isProfileable: Boolean
}
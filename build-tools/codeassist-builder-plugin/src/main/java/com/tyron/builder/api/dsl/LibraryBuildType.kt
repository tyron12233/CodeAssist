package com.tyron.builder.api.dsl

/**
 * Build types define certain properties that Gradle uses when building and packaging your library,
 * and are typically configured for different stages of your development lifecycle.
 *
 * There are two build types defined by default, `debug` and `release`, and you can customize them
 * and create additional build types.
 *
 * The default debug build type enables debug options, while the release build type is not
 * debuggable and can be configured to, for example shrink and obfuscate your library for
 * distribution.
 *
 * See
 * [configuring build types](https://developer.android.com/studio/build#build-config)
 * for more information.
 */
interface LibraryBuildType :
    BuildType,
    LibraryVariantDimension {
    /** Whether this build type should be selected in Studio by default  */
    var isDefault: Boolean
}

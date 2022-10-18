package com.tyron.builder.model.v2.dsl

import com.tyron.builder.model.v2.AndroidModel

/**
 * a Build Type. This is only the configuration of the build type.
 *
 * It does not include the sources or the dependencies. Those are available on the container
 * or in the artifact info.
 *
 * This is an interface for the gradle tooling api, and should only be used from Android Studio.
 * It is not part of the DSL & API interfaces of the Android Gradle Plugin.
 *
 * @see BuildTypeContainer
 *
 * @since 4.2
 */
interface BuildType : BaseConfig, AndroidModel {
    /** Whether the build type is configured to generate a debuggable apk. */
    val isDebuggable: Boolean

    /** Whether the build type is configured to work with profilers. */
    val isProfileable: Boolean

    /** Whether the build type is configured to be build with support for code coverage. */
    val isTestCoverageEnabled: Boolean

    /** Whether the build type is configured to be build with support for pseudolocales. */
    val isPseudoLocalesEnabled: Boolean

    /** Whether the build type is configured to generate an apk with debuggable native code. */
    val isJniDebuggable: Boolean

    /** Whether the build type is configured to generate an apk with debuggable renderscript code. */
    val isRenderscriptDebuggable: Boolean

    /** The optimization level of the renderscript compilation. */
    val renderscriptOptimLevel: Int

    /**
     * Specifies whether to enable code shrinking for this build type.
     *
     * By default, when you enable code shrinking by setting this property to `true`,
     * the Android plugin uses ProGuard.
     *
     * To learn more, read
     * [Shrink Your Code and Resources](https://developer.android.com/studio/build/shrink-code.html).
     */
    val isMinifyEnabled: Boolean

    /** Whether zipalign is enabled for this build type. */
    val isZipAlignEnabled: Boolean

    /**Whether the variant embeds the micro app. */
    val isEmbedMicroApp: Boolean

    /** The associated signing config or null if none are set on the build type. */
    val signingConfig: String?
}

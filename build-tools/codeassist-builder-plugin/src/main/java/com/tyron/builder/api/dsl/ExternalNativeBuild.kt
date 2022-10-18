package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * DSL object to configure external native builds using
 * [CMake](https://cmake.org/)
 * or
 * [ndk-build](https://developer.android.com/ndk/guides/build.html)
 *
 * ```
 * android {
 *     externalNativeBuild {
 *         // Encapsulates your CMake build configurations.
 *         // For ndk-build, instead use the ndkBuild block.
 *         cmake {
 *             // Specifies a path to your CMake build script that's
 *             // relative to the build.gradle file.
 *             path "CMakeLists.txt"
 *         }
 *     }
 * }
 * ```
 *
 * To learn more about including external native builds to your Android Studio projects, read
 * [Add C and C++ Code to Your Project](https://developer.android.com/studio/projects/add-native-code.html)
 */
interface ExternalNativeBuild {

    /**
     * Per module settings, such as the path to `Android.mk`, for an external ndk-build project.
     *
     * For more information about the properties you can configure in this block, see [NdkBuild].
     */
    val ndkBuild: NdkBuild

    /**
     * Per module settings, such as the path to `Android.mk`, for an external ndk-build project.
     *
     * For more information about the properties you can configure in this block, see [NdkBuild].
     */
    fun ndkBuild(action: NdkBuild.() -> Unit)

    /**
     * Per module settings, such as the path to `CMakeLists.txt`, for an external CMake project.
     *
     * For more information about the properties you can configure in this block, see [Cmake].
     */
    val cmake: Cmake

    /**
     * Per module settings, such as the path to `CMakeLists.txt`, for an external CMake project.
     *
     * For more information about the properties you can configure in this block, see [Cmake].
     */
    fun cmake(action: Cmake.() -> Unit)

    /**
     * Additional per module experimental properties for C and C++.
     */
    @get:Incubating
    val experimentalProperties: MutableMap<String, Any>
}

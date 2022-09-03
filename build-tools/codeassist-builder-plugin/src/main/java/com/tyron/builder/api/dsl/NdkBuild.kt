package com.tyron.builder.api.dsl

import java.io.File

/**
 * DSL object for per-module ndk-build configurations, such as the path to your `Android.mk`
 * build script and external native build output directory.
 *
 * To include ndk-build projects in your Gradle build, you need to use Android Studio 2.2 and
 * higher with Android plugin for Gradle 2.2.0 and higher. To learn more about Android Studio's
 * support for external native builds, read
 * [Add C and C++ Code to Your Project](https://developer.android.com/studio/projects/add-native-code.html)
 *
 * If you want to instead build your native libraries using CMake, see [Cmake]
 */
interface NdkBuild {

    /**
     * Specifies the relative path to your <code>Android.mk</code> build script.
     *
     * For example, if your ndk-build script is in the same folder as your project-level
     * `build.gradle` file, you simply pass the following:
     *
     * ```
     * android {
     *     externalNativeBuild {
     *         ndkBuild {
     *             // Tells Gradle to find the root ndk-build script in the same
     *             // directory as the project's build.gradle file. Gradle requires this
     *             // build script to add your ndk-build project as a build dependency and
     *             // pull your native sources into your Android project.
     *             path "Android.mk"
     *         }
     *     }
     * }
     * ```
     *
     * since 2.2.0
     */
    var path: File?

    /**
     * Specifies the relative path to your <code>Android.mk</code> build script.
     *
     * For example, if your ndk-build script is in the same folder as your project-level
     * `build.gradle` file, you simply pass the following:
     *
     * ```
     * android {
     *     externalNativeBuild {
     *         ndkBuild {
     *             // Tells Gradle to find the root ndk-build script in the same
     *             // directory as the project's build.gradle file. Gradle requires this
     *             // build script to add your ndk-build project as a build dependency and
     *             // pull your native sources into your Android project.
     *             path "Android.mk"
     *         }
     *     }
     * }
     * ```
     *
     * since 4.0.0
     */
    fun path(any: Any)

    /**
     * Specifies the path to your external native build output directory.
     *
     * If you do not specify a value for this property, the Android plugin uses the
     * `<project_dir>/.cxx/` directory by default.
     *
     * If you specify a path that does not exist, the Android plugin creates it for you.
     * Relative paths are relative to the `build.gradle` file, as shown below:
     *
     * ```
     * android {
     *     externalNativeBuild {
     *         ndkBuild {
     *             // Tells Gradle to put outputs from external native
     *             // builds in the path specified below.
     *             buildStagingDirectory "./outputs/ndk-build"
     *         }
     *     }
     * }
     * ```
     *
     * If you specify a path that's a subdirectory of your project's temporary `build`
     * directory, you get a build error. That's because files in this directory do not
     * persist through clean builds.
     * So, you should either keep using the default `<project_dir>/.cxx/`
     * directory or specify a path outside the temporary build directory.
     *
     * since 3.0.0
     */
    var buildStagingDirectory: File?

    /**
     * Specifies the path to your external native build output directory.
     *
     * If you do not specify a value for this property, the Android plugin uses the
     * `<project_dir>/.cxx/` directory by default.
     *
     * If you specify a path that does not exist, the Android plugin creates it for you.
     * Relative paths are relative to the `build.gradle` file, as shown below:
     *
     * ```
     * android {
     *     externalNativeBuild {
     *         ndkBuild {
     *             // Tells Gradle to put outputs from external native
     *             // builds in the path specified below.
     *             buildStagingDirectory "./outputs/ndk-build"
     *         }
     *     }
     * }
     * ```
     *
     * If you specify a path that's a subdirectory of your project's temporary `build`
     * directory, you get a build error. That's because files in this directory do not
     * persist through clean builds.
     * So, you should either keep using the default `<project_dir>/.cxx/`
     * directory or specify a path outside the temporary build directory.
     *
     * since 4.0.0
     */
    fun buildStagingDirectory(any: Any)
}

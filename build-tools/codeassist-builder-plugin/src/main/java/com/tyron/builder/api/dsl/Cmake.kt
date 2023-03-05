package com.tyron.builder.api.dsl

import java.io.File

/**
 * DSL object for per-module CMake configurations, such as the path to your `CMakeLists.txt`
 * build script and external native build output directory.
 *
 * To include CMake projects in your Gradle build, you need to use Android Studio 2.2 and higher
 * with Android plugin for Gradle 2.2.0 and higher. To learn more about Android Studio's support for
 * external native builds, read
 * [Add C and C++ Code to Your Project](https://developer.android.com/studio/projects/add-native-code.html)
 *
 * If you want to instead build your native libraries using ndk-build, see [NdkBuild]
 */
interface Cmake {
    /**
     * Specifies the relative path to your `CMakeLists.txt` build script.
     *
     * For example, if your CMake build script is in the same folder as your module-level
     * `build.gradle` file, you simply pass the following:
     *
     * ```
     * android {
     *     ...
     *     externalNativeBuild {
     *         cmake {
     *             ...
     *             // Tells Gradle to find the root CMake build script in the same
     *             // directory as the module's build.gradle file. Gradle requires this
     *             // build script to add your CMake project as a build dependency and
     *             // pull your native sources into your Android project.
     *             path "CMakeLists.txt"
     *         }
     *     }
     * }
     * ```
     *
     * since 2.2.0
     */
    var path: File?

    /**
     * Specifies the relative path to your `CMakeLists.txt` build script.
     *
     * For example, if your CMake build script is in the same folder as your module-level
     * `build.gradle` file, you simply pass the following:
     *
     * ```
     * android {
     *     ...
     *     externalNativeBuild {
     *         cmake {
     *             ...
     *             // Tells Gradle to find the root CMake build script in the same
     *             // directory as the module's build.gradle file. Gradle requires this
     *             // build script to add your CMake project as a build dependency and
     *             // pull your native sources into your Android project.
     *             path "CMakeLists.txt"
     *         }
     *     }
     * }
     * ```
     *
     * since 2.2.0
     */
    fun path(path: Any?)

    /**
     * Specifies the path to your external native build output directory.
     *
     * This directory also includes other build system files that should persist when performing
     * clean builds, such as [Ninja build files](https://ninja-build.org/). If you do not specify a
     * value for this property, the Android plugin uses the
     * `<project_dir>/<module>/.cxx/` directory by default.
     *
     * If you specify a path that does not exist, the Android plugin creates it for you. Relative
     * paths are relative to the `build.gradle` file, as shown below:
     *
     * ```
     * android {
     *     ...
     *     externalNativeBuild {
     *         cmake {
     *             ...
     *             // Tells Gradle to put outputs from external native
     *             // builds in the path specified below.
     *             buildStagingDirectory "./outputs/cmake"
     *         }
     *     }
     * }
     * ```
     *
     * If you specify a path that's a subdirectory of your project's temporary `build/` directory,
     * you get a build error. That's because files in this directory do not persist through clean
     * builds. So, you should either keep using the default
     * `<project_dir>/<module>/.cxx/` directory or specify a path outside the
     * temporary build directory.
     *
     * since 3.0.0
     */
    var buildStagingDirectory: File?

    /**
     * Specifies the path to your external native build output directory.
     *
     * This directory also includes other build system files that should persist when performing
     * clean builds, such as [Ninja build files](https://ninja-build.org/). If you do not specify a
     * value for this property, the Android plugin uses the
     * `<project_dir>/<module>/.cxx/` directory by default.
     *
     * If you specify a path that does not exist, the Android plugin creates it for you. Relative
     * paths are relative to the `build.gradle` file, as shown below:
     *
     * ```
     * android {
     *     ...
     *     externalNativeBuild {
     *         cmake {
     *             ...
     *             // Tells Gradle to put outputs from external native
     *             // builds in the path specified below.
     *             buildStagingDirectory "./outputs/cmake"
     *         }
     *     }
     * }
     * ```
     *
     * If you specify a path that's a subdirectory of your project's temporary `build/` directory,
     * you get a build error. That's because files in this directory do not persist through clean
     * builds. So, you should either keep using the default
     * `<project_dir>/<module>/.cxx/` directory or specify a path outside the
     * temporary build directory.
     *
     * since 3.0.0
     */
    fun buildStagingDirectory(buildStagingDirectory: Any?)

    /**
     * The version of CMake that the Android plugin should use when building your CMake project.
     *
     * When you specify a version of CMake, as shown below, the plugin searches for the appropriate
     * CMake binary within your PATH environmental variable. So, make sure you add the path to the
     * target CMake binary to your PATH environmental variable.
     *
     * ```
     * android {
     *     ...
     *     externalNativeBuild {
     *         cmake {
     *             ...
     *             // Specifies the version of CMake the Android plugin should use. You need to
     *             // include the path to the CMake binary of this version to your PATH
     *             // environmental variable.
     *             version "3.7.1"
     *         }
     *     }
     * }
     * ```
     *
     * If you do not configure this property, the plugin uses the version of CMake available from
     * the [SDK manager](https://developer.android.com/studio/intro/update.html#sdk-manager).
     * (Android Studio prompts you to download this version of CMake if you haven't already done
     * so).
     *
     * Alternatively, you can specify a version of CMake in your project's `local.properties` file,
     * as shown below:
     *
     * ```
     * // The path may be either absolute or relative to the local.properties file
     * // you are editing.
     * cmake.dir="<path-to-cmake>"
     * ```
     *
     * since 3.0.0
     */
    var version: String?
}
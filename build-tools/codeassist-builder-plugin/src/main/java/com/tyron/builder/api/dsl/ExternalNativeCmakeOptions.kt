package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * DSL object for per-variant CMake options, such as CMake arguments and compiler flags.
 *
 * To learn more about including CMake builds to your Android Studio projects, read
 * [Add C and C++ Code to Your Project](https://developer.android.com/studio/projects/add-native-code.html).
 * You can also read more documentation about [the Android CMake toolchain](https://developer.android.com/ndk/guides/cmake.html).
 */
@Incubating
interface ExternalNativeCmakeOptions {
    /**
     * Specifies arguments for CMake.
     *
     * The following sample enables NEON support and tells CMake to use the Clang compiler
     * toolchain:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor you configure.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // Passes optional arguments to CMake.
     *                 arguments "-DANDROID_ARM_NEON=TRUE", "-DANDROID_TOOLCHAIN=clang"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * By default, this property is `null`. For a list of properties you can configure, see
     * [CMake Variables List](https://developer.android.com/ndk/guides/cmake.html#variables).
     *
     * since 2.2.0
     */
    val arguments: MutableList<String>

    /**
     * Specifies arguments for CMake.
     *
     * The following sample enables NEON support and tells CMake to use the Clang compiler
     * toolchain:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor you configure.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // Passes optional arguments to CMake.
     *                 arguments "-DANDROID_ARM_NEON=TRUE", "-DANDROID_TOOLCHAIN=clang"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * By default, this property is `null`. For a list of properties you can configure, see
     * [CMake Variables List](https://developer.android.com/ndk/guides/cmake.html#variables).
     *
     * since 2.2.0
     */
    fun arguments(vararg arguments: String)

    /**
     * Specifies flags for the C compiler.
     *
     * The following sample enables format macro constants:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor in your build configuration.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // Sets an optional flag for the C compiler.
     *                 cFlags "-D__STDC_FORMAT_MACROS"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * By default, this property is `null`.
     *
     * since 2.2.0
     */
    val cFlags: MutableList<String>

    /**
     * Specifies flags for the C compiler.
     *
     * The following sample enables format macro constants:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor in your build configuration.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // Sets an optional flag for the C compiler.
     *                 cFlags "-D__STDC_FORMAT_MACROS"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * By default, this property is `null`.
     *
     * since 2.2.0
     */
    fun cFlags(vararg cFlags: String)

    /**
     * Specifies flags for the C++ compiler.
     *
     * The following sample enables RTTI (RunTime Type Information) support and C++ exceptions:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor in your build configuration.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // Sets optional flags for the C++ compiler.
     *                 cppFlags "-fexceptions", "-frtti"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * By default, this property is `null`.
     *
     * since 2.2.0
     */
    val cppFlags: MutableList<String>

    /**
     * Specifies flags for the C++ compiler.
     *
     * The following sample enables RTTI (RunTime Type Information) support and C++ exceptions:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor in your build configuration.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // Sets optional flags for the C++ compiler.
     *                 cppFlags "-fexceptions", "-frtti"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * By default, this property is `null`.
     *
     * since 2.2.0
     */
    fun cppFlags(vararg cppFlags: String)

    /**
     * Specifies the Application Binary Interfaces (ABI) that Gradle should build outputs for. The
     * ABIs that Gradle packages into your APK are determined by
     * [android.defaultConfig.ndk.abiFilter][Ndk.abiFilters]
     *
     * In most cases, you need to specify ABIs using only
     * [android.defaultConfig.ndk.abiFilter][Ndk.abiFilters], because it tells Gradle which ABIs to
     * both build and package into your APK. However, if you want to control what Gradle should
     * build, independently of what you want it to package into your APK, configure this property
     * with the ABIs you want Gradle to build.
     *
     * To further reduce the size of your APK, consider
     * [configuring multiple APKs based on ABI](https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split)—instead
     * of creating one large APK with all versions of your native libraries, Gradle creates a
     * separate APK for each ABI you want to support and only packages the files each ABI needs.
     *
     * By default, this property is `null`.
     *
     * since 2.2.0
     */
    val abiFilters: MutableSet<String>

    /**
     * Specifies the Application Binary Interfaces (ABI) that Gradle should build outputs for. The
     * ABIs that Gradle packages into your APK are determined by
     * [android.defaultConfig.ndk.abiFilter][Ndk.abiFilters]
     *
     * In most cases, you need to specify ABIs using only
     * [android.defaultConfig.ndk.abiFilter][Ndk.abiFilters], because it tells Gradle which ABIs to
     * both build and package into your APK. However, if you want to control what Gradle should
     * build, independently of what you want it to package into your APK, configure this property
     * with the ABIs you want Gradle to build.
     *
     * To further reduce the size of your APK, consider
     * [configuring multiple APKs based on ABI](https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split)—instead
     * of creating one large APK with all versions of your native libraries, Gradle creates a
     * separate APK for each ABI you want to support and only packages the files each ABI needs.
     *
     * By default, this property is `null`.
     *
     * since 2.2.0
     */
    fun abiFilters(vararg abiFilters: String)

    /**
     * Specifies the library and executable targets from your CMake project that Gradle should
     * build.
     *
     * For example, if your CMake project defines multiple libraries and executables, you can tell
     * Gradle to build only a subset of those outputs as follows:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor in your build configuration.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // The following tells Gradle to build only the "libexample-one.so" and
     *                 // "my-executible-two" targets from the linked CMake project. If you don't
     *                 // configure this property, Gradle builds all executables and shared object
     *                 // libraries that you define in your CMake project. However, Gradle packages
     *                 // only shared libraries into your APK.
     *                 targets "libexample-one",
     *                         // You need to specify this executable and its sources in your
     *                         // CMakeLists.txt using the add_executable() CMake command. However,
     *                         // building executables from your native sources is optional, and
     *                         // building native libraries to package into your APK satisfies most
     *                         // project requirements.
     *                         "my-executible-demo"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * If you don't configure this property, Gradle builds all executables and shared object
     * libraries that you define in your CMake project. However, by default, Gradle packages only
     * the shared libraries in your APK.
     *
     * since 2.2.0
     */
    val targets: MutableSet<String>

    /**
     * Specifies the library and executable targets from your CMake project that Gradle should
     * build.
     *
     * For example, if your CMake project defines multiple libraries and executables, you can tell
     * Gradle to build only a subset of those outputs as follows:
     *
     * ```
     * android {
     *     // Similar to other properties in the defaultConfig block, you can override
     *     // these properties for each product flavor in your build configuration.
     *     defaultConfig {
     *         externalNativeBuild {
     *             cmake {
     *                 // The following tells Gradle to build only the "libexample-one.so" and
     *                 // "my-executible-two" targets from the linked CMake project. If you don't
     *                 // configure this property, Gradle builds all executables and shared object
     *                 // libraries that you define in your CMake project. However, Gradle packages
     *                 // only shared libraries into your APK.
     *                 targets "libexample-one",
     *                         // You need to specify this executable and its sources in your
     *                         // CMakeLists.txt using the add_executable() CMake command. However,
     *                         // building executables from your native sources is optional, and
     *                         // building native libraries to package into your APK satisfies most
     *                         // project requirements.
     *                         "my-executible-demo"
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * If you don't configure this property, Gradle builds all executables and shared object
     * libraries that you define in your CMake project. However, by default, Gradle packages only
     * the shared libraries in your APK.
     *
     * since 2.2.0
     */
    fun targets(vararg targets: String)
}
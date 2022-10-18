package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import java.util.List;
import java.util.Set;

/**
 * Base interface for CMake per-variant info.
 */
public interface CoreExternalNativeCmakeOptions {
    /**
     * Specifies arguments for CMake.
     *
     * <p>The following sample enables NEON support and tells CMake to use the Clang compiler
     * toolchain:
     *
     * <pre>
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
     * </pre>
     *
     * <p>By default, this property is <code>null</code>. For a list of properties you can
     * configure, see <a href="https://developer.android.com/ndk/guides/cmake.html#variables">CMake
     * Variables List</a>.
     *
     * @since 2.2.0
     */
    @NonNull
    List<String> getArguments();

    /**
     * Specifies flags for the C compiler.
     *
     * <p>The following sample enables format macro constants:
     *
     * <pre>
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
     * </pre>
     *
     * <p>By default, this property is <code>null</code>.
     *
     * @since 2.2.0
     */
    @NonNull
    List<String> getcFlags();

    /**
     * Specifies flags for the C++ compiler.
     *
     * <p>The following sample enables RTTI (RunTime Type Information) support and C++ exceptions:
     *
     * <pre>
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
     * </pre>
     *
     * <p>By default, this property is <code>null</code>.
     *
     * @since 2.2.0
     */
    @NonNull
    List<String> getCppFlags();

    /**
     * Specifies the Application Binary Interfaces (ABI) that Gradle should build outputs for. The
     * ABIs that Gradle packages into your APK are determined by {@link
     * com.android.build.gradle.internal.dsl.NdkOptions#abiFilter
     * android.defaultConfig.ndk.abiFilter}
     *
     * <p>In most cases, you need to specify ABIs using only {@link
     * com.android.build.gradle.internal.dsl.NdkOptions#abiFilter
     * android.defaultConfig.ndk.abiFilter} , because it tells Gradle which ABIs to both build and
     * package into your APK. However, if you want to control what Gradle should build,
     * independently of what you want it to package into your APK, configure this property with the
     * ABIs you want Gradle to build.
     *
     * <p>To further reduce the size of your APK, consider <a
     * href="https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split">configuring
     * multiple APKs based on ABI</a>—instead of creating one large APK with all versions of your
     * native libraries, Gradle creates a separate APK for each ABI you want to support and only
     * packages the files each ABI needs.
     *
     * <p>By default, this property is <code>null</code>.
     *
     * @since 2.2.0
     */
    @NonNull
    Set<String> getAbiFilters();

    /**
     * Specifies the library and executable targets from your CMake project that Gradle should
     * build.
     *
     * <p>For example, if your CMake project defines multiple libraries and executables, you can
     * tell Gradle to build only a subset of those outputs as follows:
     *
     * <pre>
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
     * </pre>
     *
     * <p>If you don't configure this property, Gradle builds all executables and shared object
     * libraries that you define in your CMake project. However, by default, Gradle packages only
     * the shared libraries in your APK.
     *
     * @since 2.2.0
     */
    @NonNull
    Set<String> getTargets();
}
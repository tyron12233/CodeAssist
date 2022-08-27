package com.tyron.builder.api.variant

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty

/**
 * Parameters to use when building native components.
 *
 * Warning: Due to current limitations in how Android Gradle Plugin and native build interacts,
 * it is not possible to wire a [org.gradle.api.Task] mapped [org.gradle.api.provider.Provider] to
 * any of the collection [ListProperty] or [SetProperty] below. These properties must be resolved at
 * configuration time and therefore cannot have results based on Task execution. This limitation
 * might be lifted in the future.
 */
interface ExternalNativeBuild {

    /**
     * Specifies the Application Binary Interfaces (ABI) that Gradle should build outputs for. The
     * ABIs that Gradle packages into your APK are determined by {@link
     * com.android.build.gradle.internal.dsl.NdkOptions#abiFilter
     * android.defaultConfig.ndk.abiFilter}
     */
    val abiFilters: SetProperty<String>

    /**
     * Specifies arguments for CMake.
     */
    val arguments: ListProperty<String>

    /**
     * Specifies flags for the C compiler.
     */
    val cFlags: ListProperty<String>

    /**
     * Specifies flags for the CPP compiler.
     */
    val cppFlags: ListProperty<String>

    /**
     * Specifies the library and executable targets from your CMake project that Gradle should
     * build.
     */
    val targets: SetProperty<String>
}
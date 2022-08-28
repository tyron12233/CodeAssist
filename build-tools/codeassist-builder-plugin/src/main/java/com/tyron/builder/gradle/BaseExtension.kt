package com.tyron.builder.gradle

import com.tyron.builder.api.dsl.DefaultConfig
import com.tyron.builder.gradle.errors.DeprecationReporter
import com.tyron.builder.gradle.internal.dsl.DexOptions
import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.Action
import java.io.File

/**
 * Base extension for all Android plugins.
 *
 * You don't use this extension directly. Instead, use one of the following:
 *
 * * [ApplicationExtension]: `android` extension for the `com.android.application` plugin
 *         used to create an Android app.
 * * [LibraryExtension]: `android` extension for the `com.android.library` plugin used to
 *         [create an Android library](https://developer.android.com/studio/projects/android-library.html)
 * * [TestExtension]: `android` extension for the `com.android.test` plugin used to create
 *         a separate android test project.
 * * [DynamicFeatureExtension]: `android` extension for the `com.android.feature` plugin
 *         used to create dynamic features.
 *
 * The following applies the Android plugin to an app project `build.gradle` file:
 *
 * ```
 * // Applies the application plugin and makes the 'android' block available to specify
 * // Android-specific build options.
 * apply plugin: 'com.android.application'
 * ```
 *
 * To learn more about creating and organizing Android projects, read
 * [Projects Overview](https://developer.android.com/studio/projects/index.html)
 */
// All the public methods are meant to be exposed in the DSL. We can't use lambdas in this class
// (yet), because the DSL reference generator doesn't understand them.
abstract class BaseExtension(
    protected val dslServices: DslServices
) : AndroidConfig {

    private val _dexOptions = dslServices.newInstance(DexOptions::class.java)

    @Deprecated("Using dexOptions is obsolete.")
    override val dexOptions: DexOptions
        get() {
            dslServices.deprecationReporter.reportObsoleteUsage(
                "dexOptions",
                DeprecationReporter.DeprecationTarget.DEX_OPTIONS
            )
            return _dexOptions
        }

    abstract val defaultConfig: DefaultConfig
    abstract fun defaultConfig(action: Action<DefaultConfig>)
}
package com.tyron.builder.gradle.internal.services

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.provider.ValueSourceSpec
import java.io.File

/**
 * Services for creating Tasks.
 *
 * This contains whatever is needed during task creation
 *
 * This is meant to be used only by TaskManagers and TaskCreation actions. Other stages of the plugin
 * will use different services objects.
 *
 * This is accessed via [com.android.build.gradle.internal.component.ComponentCreationConfig]
 */
interface TaskCreationServices: BaseServices {
    fun fileProvider(provider: Provider<File>): Provider<RegularFile>
    fun files(vararg files: Any?): FileCollection
    fun directoryProperty(): DirectoryProperty
    fun fileCollection(): ConfigurableFileCollection
    fun fileCollection(vararg files: Any): ConfigurableFileCollection
    fun initializeAapt2Input(aapt2Input: Aapt2Input)

    fun <T> provider(callable: () -> T?): Provider<T>

    @Suppress("UnstableApiUsage")
    fun <T, P : ValueSourceParameters> providerOf(
        valueSourceType: Class<out ValueSource<T, P>>,
        configuration: Action<in ValueSourceSpec<P>>
    ): Provider<T>

    fun <T : Named> named(type: Class<T>, name: String): T

//    val lintFromMaven: LintFromMaven

    val configurations: ConfigurationContainer
    val dependencies: DependencyHandler

    val extraProperties: ExtraPropertiesExtension
}
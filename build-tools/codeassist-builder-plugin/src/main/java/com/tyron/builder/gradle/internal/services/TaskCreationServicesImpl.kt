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

class TaskCreationServicesImpl(projectServices: ProjectServices) : BaseServicesImpl(projectServices), TaskCreationServices {

    override fun fileProvider(provider: Provider<File>): Provider<RegularFile> {
        return projectServices.projectLayout.file(provider)
    }

    override fun files(vararg files: Any?): FileCollection {
        return projectServices.projectLayout.files(files)
    }
    override fun directoryProperty(): DirectoryProperty =
        projectServices.objectFactory.directoryProperty()

    override fun fileCollection(): ConfigurableFileCollection =
        projectServices.objectFactory.fileCollection()

    override fun fileCollection(vararg files: Any): ConfigurableFileCollection =
        projectServices.objectFactory.fileCollection().from(*files)

    override fun initializeAapt2Input(aapt2Input: Aapt2Input) {
        projectServices.initializeAapt2Input(aapt2Input)
    }

    override fun <T> provider(callable: () -> T?): Provider<T> {
        return projectServices.providerFactory.provider(callable)
    }

    @Suppress("UnstableApiUsage")
    override fun <T, P : ValueSourceParameters> providerOf(
        valueSourceType: Class<out ValueSource<T, P>>,
        configuration: Action<in ValueSourceSpec<P>>
    ): Provider<T> {
        return projectServices.providerFactory.of(valueSourceType, configuration)
    }

    override fun <T : Named> named(type: Class<T>, name: String): T =
        projectServices.objectFactory.named(type, name)

//    override val lintFromMaven: LintFromMaven get() = projectServices.lintFromMaven

    override val configurations: ConfigurationContainer
        get() = projectServices.configurationContainer

    override val dependencies: DependencyHandler
        get() = projectServices.dependencyHandler

    override val extraProperties: ExtraPropertiesExtension
        get() = projectServices.extraProperties
}

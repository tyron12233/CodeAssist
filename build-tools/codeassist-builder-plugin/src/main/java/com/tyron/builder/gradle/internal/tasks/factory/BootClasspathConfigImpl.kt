package com.tyron.builder.gradle.internal.tasks.factory

import com.google.common.collect.ImmutableList
import com.tyron.builder.gradle.internal.SdkComponentsBuildService
import com.tyron.builder.gradle.internal.dsl.CommonExtensionImpl
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.BootClasspathBuilder
import com.tyron.builder.gradle.internal.services.ProjectServices
import com.tyron.builder.gradle.internal.services.TaskCreationServicesImpl
import com.tyron.builder.gradle.internal.services.VersionedSdkLoaderService
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

class BootClasspathConfigImpl(
    project: Project,
    private val projectServices: ProjectServices,
    private val versionedSdkLoaderService: VersionedSdkLoaderService,
    // TODO: Remove dependency on common extension
    private val extension: CommonExtensionImpl<*,*,*,*>?,
    private val forUnitTest: Boolean
): BootClasspathConfig {

    override val fullBootClasspath: FileCollection by lazy {
        project.files(fullBootClasspathProvider)
    }

    override val fullBootClasspathProvider: Provider<List<RegularFile>> by lazy {
        // create a property rather than a Provider so that we can turn on finalizedValueOnRead
        // to avoid recomputing this in all the places this is used.
        val property = project.objects.listProperty(RegularFile::class.java)
        val versionedSdkLoader = versionedSdkLoaderService.versionedSdkLoader

        // we need to get a TaskCreationService to call computeClasspath
        // TODO refactor what we need out of TaskCreationServices? (creating yet another service class would not be great)
        val taskService = TaskCreationServicesImpl(projectServices)

        property.set(
            BootClasspathBuilder.computeClasspath(
                taskService,
                project.objects,
                versionedSdkLoader
                    .flatMap(
                        SdkComponentsBuildService.VersionedSdkLoader::targetBootClasspathProvider
                    ),
                versionedSdkLoader
                    .flatMap(
                        SdkComponentsBuildService.VersionedSdkLoader::targetAndroidVersionProvider
                    ),
                versionedSdkLoader
                    .flatMap(
                        SdkComponentsBuildService.VersionedSdkLoader::additionalLibrariesProvider
                    ),
                versionedSdkLoader
                    .flatMap(
                        SdkComponentsBuildService.VersionedSdkLoader::optionalLibrariesProvider
                    ),
                versionedSdkLoader
                    .flatMap(
                        SdkComponentsBuildService.VersionedSdkLoader::annotationsJarProvider
                    ),
                addAllOptionalLibraries = true,
                ImmutableList.of()
            )
        )

        // prevent further changes
        property.disallowChanges()
        // turn on memoization
        property.finalizeValueOnRead()
        // prevent too early reads
        if (!forUnitTest) {
            property.disallowUnsafeRead()
        }

        property
    }

    override val filteredBootClasspath: Provider<List<RegularFile>> by lazy {
        // create a property rather than a Provider so that we can turn on finalizeeValueOnRead
        // to avoid recomputing this in all the places this is used.
        val property = project.objects.listProperty(RegularFile::class.java)
        val versionedSdkLoader = versionedSdkLoaderService.versionedSdkLoader

        // we need to get a TaskCreationService to call computeClasspath
        // TODO refactor what we need out of TaskCreationServices? (creating yet another service class would not be great)
        val taskService = TaskCreationServicesImpl(projectServices)

        property.set(
            BootClasspathBuilder.computeClasspath(
                taskService,
                project.objects,
                versionedSdkLoader.flatMap(
                    SdkComponentsBuildService.VersionedSdkLoader::targetBootClasspathProvider
                ),
                versionedSdkLoader.flatMap(
                    SdkComponentsBuildService.VersionedSdkLoader::targetAndroidVersionProvider
                ),
                versionedSdkLoader.flatMap(
                    SdkComponentsBuildService.VersionedSdkLoader::additionalLibrariesProvider
                ),
                versionedSdkLoader.flatMap(
                    SdkComponentsBuildService.VersionedSdkLoader::optionalLibrariesProvider
                ),
                versionedSdkLoader.flatMap(
                    SdkComponentsBuildService.VersionedSdkLoader::annotationsJarProvider
                ),
                false,
                ImmutableList.copyOf(extension?.libraryRequests ?: emptyList())
            )
        )

        // prevent further changes
        property.disallowChanges()
        // turn on memoization
        property.finalizeValueOnRead()
        // This cannot be protected against unsafe reads until BaseExtension::bootClasspath
        // has been removed. Most users of that method will call it at configuration time which
        // resolves this collection. Uncomment next line once BaseExtension::bootClasspath is
        // removed.
        //if (!forUnitTest) {
        //    property.disallowUnsafeRead()
        //}

        property
    }

    override val bootClasspath: Provider<List<RegularFile>> by lazy {
        // create a property rather than a Provider so that we can turn on finalizedValueOnRead
        // to avoid recomputing this in all the places this is used.
        val property = project.objects.listProperty(RegularFile::class.java)
        val versionedSdkLoader = versionedSdkLoaderService.versionedSdkLoader

        property.addAll(filteredBootClasspath)
        if (extension?.compileOptions?.targetCompatibility?.isJava8Compatible != false) {
            property.add(
                versionedSdkLoader
                    .flatMap(
                        SdkComponentsBuildService.VersionedSdkLoader::coreLambdaStubsProvider
                    )
            )
        }

        // prevent further changes
        property.disallowChanges()
        // turn on memoization
        property.finalizeValueOnRead()
        // This cannot be protected against unsafe reads until BaseExtension::bootClasspath
        // has been removed. Most users of that method will call it at configuration time which
        // resolves this collection. Uncomment next lines once BaseExtension::bootClasspath is
        // removed.
        if (!forUnitTest) {
            property.disallowUnsafeRead()
        }

        property
    }

    internal lateinit var androidJar: Configuration

    override val mockableJarArtifact: FileCollection by lazy {
        val attributes =
            Action { container: AttributeContainer ->
                container
                    .attribute(
                        AndroidArtifacts.ARTIFACT_TYPE,
                        AndroidArtifacts.TYPE_MOCKABLE_JAR
                    )
                    .attribute(
                        AndroidArtifacts.MOCKABLE_JAR_RETURN_DEFAULT_VALUES,
                        false
//                        extension?.testOptions?.unitTests?.isReturnDefaultValues ?: false
                    )
            }
        androidJar
            .incoming
            .artifactView { config -> config.attributes(attributes) }
            .artifacts
            .artifactFiles
    }
}

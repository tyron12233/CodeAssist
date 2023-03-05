package com.tyron.builder.gradle.internal.ide.dependencies

import com.tyron.builder.gradle.internal.ide.AndroidLibraryImpl
import com.tyron.builder.gradle.internal.ide.DependenciesImpl
import com.tyron.builder.gradle.internal.ide.JavaLibraryImpl
import com.tyron.builder.gradle.internal.services.getBuildService
import com.tyron.builder.model.AndroidLibrary
import com.tyron.builder.model.Dependencies
import com.tyron.builder.model.JavaLibrary
import com.tyron.builder.model.MavenCoordinates
import com.google.common.collect.ImmutableList
import org.gradle.api.services.BuildServiceRegistry
import java.io.File

/**
 * Implementation of [ArtifactHandler] for Level1 Dependency model, for sync.
 */
class Level1ArtifactHandler(
    buildServiceRegistry: BuildServiceRegistry
) : ArtifactHandler<Unit>(
    getBuildService(
        buildServiceRegistry,
        LibraryDependencyCacheBuildService::class.java
    ).get().localJarCache,
    getBuildService(
        buildServiceRegistry,
        MavenCoordinatesCacheBuildService::class.java
    ).get()
) {

    private val _projects: ImmutableList.Builder<Dependencies.ProjectIdentifier> =
        ImmutableList.builder<Dependencies.ProjectIdentifier>()
    private val _androidLibraries: ImmutableList.Builder<AndroidLibrary> =
        ImmutableList.builder<AndroidLibrary>()
    private val _javaLibraries: ImmutableList.Builder<JavaLibrary> =
        ImmutableList.builder<JavaLibrary>()

    val projects: List<Dependencies.ProjectIdentifier>
        get() = _projects.build()

    val androidLibraries: List<AndroidLibrary>
        get() = _androidLibraries.build()

    val javaLibraries: List<JavaLibrary>
        get() = _javaLibraries.build()

    override fun handleAndroidLibrary(
        aarFile: File,
        folder: File,
        localJavaLibraries: List<File>,
        isProvided: Boolean,
        variantName: String?,
        coordinatesSupplier: () -> MavenCoordinates,
        addressSupplier: () -> String
    ) {
        _androidLibraries.add(AndroidLibraryImpl(
            coordinatesSupplier(),
            null, /*buildId*/
            null, /*projectPath*/
            aarFile,
            folder,
            variantName,
            isProvided,
            false,  /* isSkipped */
            ImmutableList.of(),  /* androidLibraries */
            ImmutableList.of(),  /* javaLibraries */
            localJavaLibraries,
            null /* lintJar */
        ))
    }

    override fun handleAndroidModule(
        projectPath: String,
        buildId: String,
        variantName: String?,
        isTestFixtures: Boolean,
        aarFile: File,
        lintJar: File?,
        isProvided: Boolean,
        coordinatesSupplier: () -> MavenCoordinates,
        addressSupplier: () -> String
    ) {
        _androidLibraries.add(AndroidLibraryImpl(
            coordinatesSupplier(),
            buildId,
            projectPath,
            aarFile,
            aarFile, // No extracted folder so pass the aar to avoid npe (!)
            variantName,
            isProvided,
            false,  /* dependencyItem.isSkipped() */
            ImmutableList.of(),  /* androidLibraries */
            ImmutableList.of(),  /* javaLibraries */
            listOf(), /* localJavaLibraries */
            lintJar
        ))
    }

    override fun handleJavaLibrary(
        jarFile: File,
        isProvided: Boolean,
        coordinatesSupplier: () -> MavenCoordinates,
        addressSupplier: () -> String
    ) {
        _javaLibraries.add(JavaLibraryImpl(
            jarFile,
            null, /*buildId*/
            null, /* projectPath */
            ImmutableList.of(), /* dependencies */
            null, /* requestedCoordinates */
            coordinatesSupplier(),
            false, /* isSkipped */
            isProvided
        ))
    }

    override fun handleJavaModule(
        projectPath: String,
        buildId: String,
        variantName: String?,
        isTestFixtures: Boolean,
        addressSupplier: () -> String
    ) {
        _projects.add(DependenciesImpl.ProjectIdentifierImpl(buildId, projectPath))
    }
}

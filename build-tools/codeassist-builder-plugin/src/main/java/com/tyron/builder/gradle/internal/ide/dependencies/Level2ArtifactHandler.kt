package com.tyron.builder.gradle.internal.ide.dependencies

import com.android.ide.common.caching.CreatingCache
import com.tyron.builder.gradle.internal.ide.level2.AndroidLibraryImpl
import com.tyron.builder.gradle.internal.ide.level2.JavaLibraryImpl
import com.tyron.builder.gradle.internal.ide.level2.ModuleLibraryImpl
import com.tyron.builder.model.MavenCoordinates
import com.tyron.builder.model.level2.Library
import java.io.File

/**
 * Implementation of [ArtifactHandler] for Level2 Dependency model, for sync.
 */
class Level2ArtifactHandler(
    localJarCache: CreatingCache<File, List<File>>,
    mavenCoordinatesCache: MavenCoordinatesCacheBuildService
) : ArtifactHandler<Library>(
    localJarCache,
    mavenCoordinatesCache
) {

    override fun handleAndroidLibrary(
        aarFile: File,
        folder: File,
        localJavaLibraries: List<File>,
        isProvided: Boolean,
        variantName: String?,
        coordinatesSupplier: () -> MavenCoordinates,
        addressSupplier: () -> String
    ): Library {
        // the localJavaLibraries are full path as File, but level 2 requires String that
        // are relative to the folder.
        // This extract work is not a problem because we're going to delete L2 and move directly
        // to v2 instead so this is temporary to keep some tests working.
        val rootLen = folder.toString().length + 1
        val localJarsAsString = localJavaLibraries.asSequence().map { it.toString().substring(rootLen) }.toMutableList()

        return AndroidLibraryImpl(
            addressSupplier(),
            aarFile,
            folder,
            null, /* resStaticLibrary */
            localJarsAsString
        )
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
    ): Library = ModuleLibraryImpl(
        addressSupplier(),
        buildId,
        projectPath,
        variantName
    )

    override fun handleJavaLibrary(
        jarFile: File,
        isProvided: Boolean,
        coordinatesSupplier: () -> MavenCoordinates,
        addressSupplier: () -> String
    ): Library = JavaLibraryImpl(addressSupplier(), jarFile)

    override fun handleJavaModule(
        projectPath: String,
        buildId: String,
        variantName: String?,
        isTestFixtures: Boolean,
        addressSupplier: () -> String
    ): Library = ModuleLibraryImpl(
        addressSupplier(),
        buildId,
        projectPath,
        variantName
    )
}

package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.AndroidLibraryData
import com.tyron.builder.model.v2.ide.Library
import com.tyron.builder.model.v2.ide.LibraryInfo
import com.tyron.builder.model.v2.ide.LibraryType
import com.tyron.builder.model.v2.ide.ProjectInfo
import java.io.File
import java.io.Serializable

/**
 * Implementation of [Library] for serialization via the Tooling API.
 */
@Suppress("DataClassPrivateConstructor")
data class LibraryImpl private constructor(
    override val key: String,
    override val type: LibraryType,
    override val projectInfo: ProjectInfo? = null,
    override val libraryInfo: LibraryInfo? = null,
    override val artifact: File? = null,
    override val lintJar: File?,
    override val androidLibraryData: AndroidLibraryData? = null
) : Library, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L

        fun createProjectLibrary(
            key: String,
            projectInfo: ProjectInfo,
            artifactFile: File?,
            lintJar: File?,
        ) = LibraryImpl(
            key = key,
            type = LibraryType.PROJECT,
            projectInfo = projectInfo,
            artifact = artifactFile,
            lintJar = lintJar,
        )

        fun createJavaLibrary(
            key: String,
            libraryInfo: LibraryInfo,
            artifact: File,
        ) = LibraryImpl(
            key = key,
            type = LibraryType.JAVA_LIBRARY,
            libraryInfo = libraryInfo,
            artifact = artifact,
            lintJar = null
        )

        fun createAndroidLibrary(
            key: String,
            libraryInfo: LibraryInfo,
            artifact: File,
            manifest: File,
            compileJarFiles: List<File>,
            runtimeJarFiles: List<File>,
            resFolder: File,
            resStaticLibrary: File,
            assetsFolder: File,
            jniFolder: File,
            aidlFolder: File,
            renderscriptFolder: File,
            proguardRules: File,
            lintJar: File?,
            externalAnnotations: File,
            publicResources: File,
            symbolFile: File
        ) = LibraryImpl(
            key = key,
            type = LibraryType.ANDROID_LIBRARY,
            libraryInfo = libraryInfo,
            artifact = artifact,
            lintJar = lintJar,
            androidLibraryData = AndroidLibraryDataImpl(
                manifest = manifest,
                compileJarFiles = compileJarFiles,
                runtimeJarFiles = runtimeJarFiles,
                resFolder = resFolder,
                resStaticLibrary = resStaticLibrary,
                assetsFolder = assetsFolder,
                jniFolder = jniFolder,
                aidlFolder = aidlFolder,
                renderscriptFolder = renderscriptFolder,
                proguardRules = proguardRules,
                externalAnnotations = externalAnnotations,
                publicResources = publicResources,
                symbolFile = symbolFile
            )
        )

        fun createRelocatedLibrary(
            key: String,
            libraryInfo: LibraryInfo,
        ) = LibraryImpl(
            key = key,
            type = LibraryType.RELOCATED,
            libraryInfo = libraryInfo,
            artifact = null,
            lintJar = null
        )

        fun createNoArtifactFileLibrary(
            key: String,
            libraryInfo: LibraryInfo,
        ) = LibraryImpl(
            key = key,
            type = LibraryType.NO_ARTIFACT_FILE,
            libraryInfo = libraryInfo,
            artifact = null,
            lintJar = null
        )
    }
}

private data class AndroidLibraryDataImpl(
    override val manifest: File,
    override val compileJarFiles: List<File>,
    override val runtimeJarFiles: List<File>,
    override val resFolder: File,
    override val resStaticLibrary: File,
    override val assetsFolder: File,
    override val jniFolder: File,
    override val aidlFolder: File,
    override val renderscriptFolder: File,
    override val proguardRules: File,
    override val externalAnnotations: File,
    override val publicResources: File,
    override val symbolFile: File
) : AndroidLibraryData, Serializable {
    companion object {
       @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}

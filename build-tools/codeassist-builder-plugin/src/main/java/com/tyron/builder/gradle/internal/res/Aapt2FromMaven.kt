package com.tyron.builder.gradle.internal.res

import com.android.SdkConstants
import com.android.Version
import com.tyron.builder.gradle.internal.dependency.GenericTransformParameters
import com.tyron.builder.gradle.options.Option
import com.tyron.builder.gradle.options.StringOption
import com.google.common.collect.Sets
import com.google.common.io.ByteStreams
import org.gradle.api.Project
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.internal.artifacts.ArtifactAttributes
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * The AAPT2 binary as (potentially) fetched from Maven.
 *
 * Contains a file collection, which will contain the directory with AAPT2 to be used,
 * and a String identifying the version of AAPT2 being used.
 */
class Aapt2FromMaven(val aapt2Directory: FileCollection, val version: String) {
    companion object {
        private const val TYPE_EXTRACTED_AAPT2_BINARY = "_internal-android-aapt2-binary"
        private const val PLATFORM_WINDOWS = "windows"
        private const val PLATFORM_LINUX = "linux"
        private const val PLATFORM_OSX = "osx"
        private val ACCEPTED_PLATFORMS = listOf(PLATFORM_WINDOWS, PLATFORM_OSX, PLATFORM_LINUX)

        private object Aapt2Version {
            val BUILD_NUMBER: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
                Aapt2Version::class.java
                    .getResourceAsStream("aapt2_version.properties")
                    .buffered()
                    .use { stream ->
                        Properties().let { properties ->
                            properties.load(stream)
                            properties.getProperty("aapt2Version")
                        }
                    }
            }
        }

        /**
         * Initializes the AAPT2 from maven for this project.
         *
         * If [StringOption.AAPT2_FROM_MAVEN_OVERRIDE] is set, the value of that flag will be
         * returned as the version since the AAPT2 version is not necessarily known in this case.
         *
         * This uses a detached configuration and is not idempotent, and should only be called when
         * creating the project services.
         */
        @JvmStatic
        fun create(project: Project, stringOption: (option: StringOption) -> String?): Aapt2FromMaven {
            // Use custom AAPT2 if it was overridden.
            val customAapt2: String = stringOption(StringOption.AAPT2_FROM_MAVEN_OVERRIDE) ?: ""
            // Use custom maven coordinates if specified.
            val overriddenVersion: String =
                    stringOption(StringOption.AAPT2_FROM_MAVEN_VERSION_OVERRIDE) ?: ""
            val defaultVersion = "${Version.ANDROID_GRADLE_PLUGIN_VERSION}-${Aapt2Version.BUILD_NUMBER}"

            if (customAapt2.any() && overriddenVersion.any()) {
                error("You cannot specify both local and remote custom versions of AAPT2.\n" +
                        "Please use either ${StringOption.AAPT2_FROM_MAVEN_OVERRIDE.propertyName}" +
                        " for setting a local path to the executable or " +
                        "${StringOption.AAPT2_FROM_MAVEN_VERSION_OVERRIDE} for specifying a Maven" +
                        " version (e.g. \"$defaultVersion\").")
            }

            if (customAapt2.any()) {
                if (!customAapt2.endsWith(SdkConstants.FN_AAPT2)) {
                    error("Custom AAPT2 location does not point to an AAPT2 executable: $customAapt2")
                }
                return Aapt2FromMaven(project.files(File(customAapt2).parentFile), customAapt2)
            }

            val version =
                    if (overriddenVersion.any())
                        overriddenVersion
                    else
                        defaultVersion

            val overriddenPlatform: String =
                    stringOption(StringOption.AAPT2_FROM_MAVEN_PLATFORM_OVERRIDE) ?: ""
            // See tools/base/aapt2 for the classifiers to use.
            val classifier =
                    if (overriddenPlatform.any()) {
                        if (!ACCEPTED_PLATFORMS.contains(overriddenPlatform)) {
                            error("Unknown platform '$overriddenPlatform'")
                        }
                        // Used when system is a not officially supported platform but can run
                        // other supported platform executables, eg. FreeBSD can use Linux.
                        // The option is experimental and we do not guarantee that the executable
                        // will work correctly.
                        overriddenPlatform
                    }
                    else
                        when (SdkConstants.currentPlatform()) {
                            SdkConstants.PLATFORM_WINDOWS -> PLATFORM_WINDOWS
                            SdkConstants.PLATFORM_DARWIN -> PLATFORM_OSX
                            SdkConstants.PLATFORM_LINUX -> PLATFORM_LINUX
                            else -> error("Unknown platform '${System.getProperty("os.name")}'")
                        }

            val configuration = project.configurations.detachedConfiguration(
                project.dependencies.module(
                    mapOf(
                        "group" to "com.android.tools.build",
                        "name" to "aapt2",
                        "version" to version,
                        "classifier" to classifier
                    )
                )
            )

            project.dependencies.registerTransform(Aapt2Extractor::class.java) {
                it.parameters.projectName.set(project.name)
                it.from.attribute(
                    ArtifactAttributes.ARTIFACT_FORMAT,
                    ArtifactTypeDefinition.JAR_TYPE
                )
                it.to.attribute(ArtifactAttributes.ARTIFACT_FORMAT, TYPE_EXTRACTED_AAPT2_BINARY)
            }

            val aapt2Directory = configuration.incoming.artifactView { config ->
                config.attributes {
                    it.attribute(
                        ArtifactAttributes.ARTIFACT_FORMAT,
                        TYPE_EXTRACTED_AAPT2_BINARY
                    )
                }
            }.artifacts.artifactFiles
            return Aapt2FromMaven(aapt2Directory, version)
        }

        abstract class Aapt2Extractor : TransformAction<GenericTransformParameters> {

            @get:PathSensitive(PathSensitivity.NAME_ONLY)
            @get:InputArtifact
            abstract val inputArtifact: Provider<FileSystemLocation>

            override fun transform(transformOutputs: TransformOutputs) {
                val input = inputArtifact.get().asFile
                val outDir = transformOutputs.dir(input.nameWithoutExtension).toPath()
                Files.createDirectories(outDir)
                ZipInputStream(input.inputStream().buffered()).use { zipInputStream ->
                    while (true) {
                        val entry = zipInputStream.nextEntry ?: break
                        if (entry.isDirectory) {
                            continue
                        }
                        val destinationFile = outDir.resolve(entry.name)
                        Files.createDirectories(destinationFile.parent)
                        Files.newOutputStream(destinationFile).buffered().use { output ->
                            ByteStreams.copy(zipInputStream, output)
                        }
                        // Mark executable on linux.
                        if (entry.name == SdkConstants.FN_AAPT2 &&
                            (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_LINUX || SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_DARWIN)
                        ) {
                            val permissions = Files.getPosixFilePermissions(destinationFile)
                            Files.setPosixFilePermissions(
                                destinationFile,
                                Sets.union(permissions, setOf(PosixFilePermission.OWNER_EXECUTE))
                            )
                        }
                    }
                }
            }
        }
    }
}


package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants.*
import com.android.Version
import com.android.ide.common.repository.GradleVersion
import com.android.sdklib.SdkVersionInfo
import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.ide.dependencies.getIdString
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.AarMetadataTask.Companion.DEFAULT_MIN_COMPILE_SDK_EXTENSION
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.utils.parseTargetHash
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.io.InputStream
import java.io.Serializable
import java.util.*

/**
 * A task that reads the dependencies' AAR metadata files and checks for compatibility.
 *
 * Caching disabled by default for this task because the task does very little work.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.VERIFICATION)
abstract class CheckAarMetadataTask : NonIncrementalTask() {

    // Dummy output allows this task to be up-to-date, and it provides a means of making other tasks
    // depend on this task.
    @get:OutputDirectory
    abstract val dummyOutputDirectory: DirectoryProperty

    @VisibleForTesting
    @get:Internal
    internal lateinit var aarMetadataArtifacts: ArtifactCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val aarMetadataFiles: FileCollection
        get() = aarMetadataArtifacts.artifactFiles

    @get:Input
    abstract val aarFormatVersion: Property<String>

    @get:Input
    abstract val aarMetadataVersion: Property<String>

    // compileSdkVersion is the full compileSdkVersion hash string coming from the DSL
    @get:Input
    abstract val compileSdkVersion: Property<String>

    // platformSdkExtension is the actual extension level of the platform, if specified within the
    // SDK directory. This value is used if the extension level is not specified in the
    // compileSdkVersion hash string.
    @get:Input
    @get:Optional
    abstract val platformSdkExtension: Property<Int>

    // platformSdkApiLevel is the actual api level of the platform. This value is used if the
    // compileSdkVersion hash string specifies a preview SDK unknown to this version of AGP.
    @get:Input
    abstract val platformSdkApiLevel: Property<Int>

    @get:Input
    abstract val agpVersion: Property<String>

    @get:Input
    abstract val maxRecommendedStableCompileSdkVersionForThisAgp: Property<Int>

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(
            CheckAarMetadataWorkAction::class.java
        ) {
            it.aarMetadataArtifacts.addAll(
                aarMetadataArtifacts.artifacts.map { artifact ->
                    AarMetadataArtifact(
                        artifact.file,
                        when (val id = artifact.id.componentIdentifier) {
                            is LibraryBinaryIdentifier -> id.projectPath
                            is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                            is ProjectComponentIdentifier -> id.getIdString()
                            else -> id.displayName
                        }
                    )
                }
            )
            it.aarFormatVersion.set(aarFormatVersion)
            it.aarMetadataVersion.set(aarMetadataVersion)
            it.compileSdkVersion.set(compileSdkVersion)
            it.platformSdkExtension.set(platformSdkExtension)
            it.platformSdkApiLevel.set(platformSdkApiLevel)
            it.agpVersion.set(agpVersion)
            it.maxRecommendedStableCompileSdkVersionForThisAgp.set(
                maxRecommendedStableCompileSdkVersionForThisAgp
            )
            it.projectPath.set(projectPath)
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<CheckAarMetadataTask, ComponentCreationConfig>(creationConfig) {

        override val name: String
            get() = computeTaskName("check", "AarMetadata")

        override val type: Class<CheckAarMetadataTask>
            get() = CheckAarMetadataTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<CheckAarMetadataTask>) {
            super.handleProvider(taskProvider)

            creationConfig.artifacts
                .setInitialProvider(taskProvider, CheckAarMetadataTask::dummyOutputDirectory)
                .on(InternalArtifactType.AAR_METADATA_CHECK)
        }

        override fun configure(task: CheckAarMetadataTask) {
            super.configure(task)

            task.aarMetadataArtifacts =
                creationConfig.variantDependencies.getArtifactCollection(
                    AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                    AndroidArtifacts.ArtifactScope.ALL,
                    AndroidArtifacts.ArtifactType.AAR_METADATA
                )
            task.aarFormatVersion.setDisallowChanges(AarMetadataTask.AAR_FORMAT_VERSION)
            task.aarMetadataVersion.setDisallowChanges(AarMetadataTask.AAR_METADATA_VERSION)
            task.compileSdkVersion.setDisallowChanges(creationConfig.global.compileSdkHashString)
            task.agpVersion.setDisallowChanges(Version.ANDROID_GRADLE_PLUGIN_VERSION)
            task.maxRecommendedStableCompileSdkVersionForThisAgp.setDisallowChanges(
//                ToolsRevisionUtils.MAX_RECOMMENDED_COMPILE_SDK_VERSION.apiLevel
            33
            )
            task.platformSdkExtension.setDisallowChanges(
//                creationConfig.global.versionedSdkLoader.flatMap { sdkLoader ->
//                    sdkLoader.targetAndroidVersionProvider.map { it.extensionLevel }
//                }
            33
            )
            task.platformSdkApiLevel.setDisallowChanges(
//                creationConfig.global.versionedSdkLoader.flatMap { sdkLoader ->
//                    sdkLoader.targetAndroidVersionProvider.map { it.featureLevel }
//                }
            33
            )
        }
    }
}

/** [WorkAction] to check AAR metadata files */
abstract class CheckAarMetadataWorkAction: WorkAction<CheckAarMetadataWorkParameters> {

    override fun execute() {
        val errorMessages: MutableList<String> = mutableListOf()
        parameters.aarMetadataArtifacts.get().forEach {
            checkAarMetadataArtifact(it, errorMessages)
        }
        if (errorMessages.size > 0) {
            throw RuntimeException(StringBuilder().apply {
                when (errorMessages.size) {
                    1 -> {
                        append("An issue was")
                    }
                    else -> {
                        append(errorMessages.size).append(" issues were")
                    }
                }
                append(" found when checking AAR metadata:")
                appendNumberedErrorMessages(errorMessages)
            }.toString())
        }
    }

    /**
     * Number and indent the issues, e.g.
     * ```
     * 2 issues were found when checking AAR metadata:
     *
     *   1.  Dependency 'displayName' requires Android Gradle plugin 3.0.0 or higher.
     *
     *       This build currently uses Android Gradle plugin 3.0.0-beta01.
     *
     *   2.  Another issue with some description.
     *
     *       More description.
     * ```
     */
    private fun StringBuilder.appendNumberedErrorMessages(errorMessages: List<String>) {
        errorMessages.forEachIndexed { index ,errorMessage ->
            append("\n\n")
            append("%3d".format(Locale.US, index+1))
            append(".  ")
            errorMessage.lines().forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) {
                    append("\n")
                    if (line.isNotEmpty()) {
                        append("      ")
                    }
                }
                append(line)
            }
        }
    }

    /** Checks the aarMetadataArtifact and add any errors to be reported to errorMessages */
    private fun checkAarMetadataArtifact(
        aarMetadataArtifact: AarMetadataArtifact,
        errorMessages: MutableList<String>
    ) {
        val aarMetadataFile = aarMetadataArtifact.file
        val displayName = aarMetadataArtifact.displayName
        val aarMetadataReader = AarMetadataReader(aarMetadataFile)

        // check aarFormatVersion
        val aarFormatVersion = aarMetadataReader.aarFormatVersion
        if (aarFormatVersion == null) {
            errorMessages.add(
                """
                    The AAR metadata for dependency '$displayName' does not specify an
                    $AAR_FORMAT_VERSION_PROPERTY value, which is a required value.
                    """.trimIndent()
            )
        } else {
            try {
                val majorAarVersion = 0 // Revision.parseRevision(aarFormatVersion).major
                val maxMajorAarVersion =
                    0//Revision.parseRevision(parameters.aarFormatVersion.get())
                        //.major
                if (majorAarVersion > maxMajorAarVersion) {
                    errorMessages.add(
                        """
                            Dependency '$displayName' has an $AAR_FORMAT_VERSION_PROPERTY value of
                            '$aarFormatVersion', which is not compatible with this version of the
                            Android Gradle plugin.

                            Please upgrade to a newer version of the Android Gradle plugin.
                            """.trimIndent()
                    )
                }
            } catch (e: NumberFormatException) {
                errorMessages.add(
                    """
                        The AAR metadata for dependency '$displayName' has an invalid
                        $AAR_FORMAT_VERSION_PROPERTY value ($aarFormatVersion).

                        ${e.message}
                        """.trimIndent()
                )
            }
        }

        // check aarMetadataVersion
        val aarMetadataVersion = aarMetadataReader.aarMetadataVersion
        if (aarMetadataVersion == null) {
            errorMessages.add(
                """
                    The AAR metadata for dependency '$displayName' does not specify an
                    $AAR_METADATA_VERSION_PROPERTY value, which is a required value.
                    """.trimIndent()
            )
        } else {
            try {
//                val majorAarMetadataVersion = Revision.parseRevision(aarMetadataVersion).major
//                val maxMajorAarMetadataVersion =
//                    Revision.parseRevision(parameters.aarMetadataVersion.get())
//                        .major
//                if (majorAarMetadataVersion > maxMajorAarMetadataVersion) {
//                    errorMessages.add(
//                        """
//                            Dependency '$displayName' has an $AAR_METADATA_VERSION_PROPERTY value of
//                            '$aarMetadataVersion', which is not compatible with this version of the
//                            Android Gradle plugin.
//
//                            Please upgrade to a newer version of the Android Gradle plugin.
//                            """.trimIndent()
//                    )
//                }
            } catch (e: NumberFormatException) {
                errorMessages.add(
                    """
                        The AAR metadata for dependency '$displayName' has an invalid
                        $AAR_METADATA_VERSION_PROPERTY value ($aarMetadataVersion).

                        ${e.message}
                        """.trimIndent()
                )
            }
        }

        // check forceCompileSdkPreview
        val forceCompileSdkPreview = aarMetadataReader.forceCompileSdkPreview
        if (forceCompileSdkPreview != null) {
            val compileSdkVersion = parameters.compileSdkVersion.get()
            val compileSdkPreview = parseTargetHash(parameters.compileSdkVersion.get()).codeName
            if (compileSdkPreview != forceCompileSdkPreview) {
                errorMessages.add(
                    """
                        Dependency '$displayName' requires libraries and applications that
                        depend on it to compile against codename "$forceCompileSdkPreview" of the
                        Android APIs.

                        ${parameters.projectPath.get()} is currently compiled against $compileSdkVersion.

                        Recommended action: Use a different version of dependency '$displayName',
                        or set compileSdkPreview to "$forceCompileSdkPreview" in your build.gradle
                        file if you intend to experiment with that preview SDK.
                    """.trimIndent()
                )
            }
        }

        // check compileSdkVersion
        val minCompileSdk = aarMetadataReader.minCompileSdk
        if (minCompileSdk != null) {
            val minCompileSdkInt = minCompileSdk.toIntOrNull()
            if (minCompileSdkInt == null) {
                errorMessages.add(
                    """
                        The AAR metadata for dependency '$displayName' has an invalid
                        $MIN_COMPILE_SDK_PROPERTY value ($minCompileSdk).

                        $MIN_COMPILE_SDK_PROPERTY must be an integer.
                        """.trimIndent()
                )
            } else {
                val compileSdkVersion = parameters.compileSdkVersion.get()
                val compileSdkVersionInt =
                    getApiIntFromString(compileSdkVersion).let {
                        if (it > SdkVersionInfo.HIGHEST_KNOWN_API) {
                            parameters.platformSdkApiLevel.get()
                        } else {
                            it
                        }
                    }
                if (minCompileSdkInt > compileSdkVersionInt) {
                    // TODO(b/199900566) - change compileSdkVersion to compileSdk for AGP 8.0.
                    val maxRecommendedCompileSdk = parameters.maxRecommendedStableCompileSdkVersionForThisAgp.get()
                    val recommendation = if (minCompileSdkInt <= maxRecommendedCompileSdk) {
                        """
                            Recommended action: Update this project to use a newer compileSdkVersion
                            of at least $minCompileSdk, for example $maxRecommendedCompileSdk.
                        """.trimIndent()
                    } else {
                        """
                            Also, the maximum recommended compile SDK version for Android Gradle
                            plugin ${parameters.agpVersion.get()} is $maxRecommendedCompileSdk.

                            Recommended action: Update this project's version of the Android Gradle
                            plugin to one that supports $minCompileSdk, then update this project to use
                            compileSdkVerion of at least $minCompileSdk.
                        """.trimIndent()
                    }
                    errorMessages.add(
                        """
                            Dependency '$displayName' requires libraries and applications that
                            depend on it to compile against version $minCompileSdk or later of the
                            Android APIs.

                            ${parameters.projectPath.get()} is currently compiled against $compileSdkVersion.
                        """.trimIndent() + "\n\n" + recommendation + "\n\n" + """
                            Note that updating a library or application's compileSdkVersion (which
                            allows newer APIs to be used) can be done separately from updating
                            targetSdkVersion (which opts the app in to new runtime behavior) and
                            minSdkVersion (which determines which devices the app can be installed
                            on).
                        """.trimIndent()
                    )
                }
            }
        }

        // check SDK extension level
        val minCompileSdkExtension = aarMetadataReader.minCompileSdkExtension
        if (minCompileSdkExtension != null) {
            val minCompileSdkExtensionInt = minCompileSdkExtension.toIntOrNull()
            if (minCompileSdkExtensionInt == null) {
                errorMessages.add(
                    """
                        The AAR metadata for dependency '$displayName' has an invalid
                        MIN_COMPILE_SDK_EXTENSION_PROPERTY value ($minCompileSdkExtension).

                        MIN_COMPILE_SDK_EXTENSION_PROPERTY must be an integer.
                        """.trimIndent()
                )
            } else {
                val compileSdkExtension =
                    parseTargetHash(parameters.compileSdkVersion.get()).sdkExtension
                        ?: parameters.platformSdkExtension.orNull
                        ?: DEFAULT_MIN_COMPILE_SDK_EXTENSION
                if (minCompileSdkExtensionInt > compileSdkExtension) {
                    errorMessages.add(
                        """
                            Dependency '$displayName' requires libraries and applications that
                            depend on it to compile against an SDK with an extension level of
                            $minCompileSdkExtension or higher.

                            Recommended action: Update this project to use a compileSdkExtension
                            value of at least $minCompileSdkExtension.
                        """.trimIndent()
                    )
                }
            }
        }

        // check agpVersion
        val minAgpVersion = aarMetadataReader.minAgpVersion
        if (minAgpVersion != null) {
            val parsedMinAgpVersion =
                GradleVersion.tryParseStableAndroidGradlePluginVersion(minAgpVersion)
            if (parsedMinAgpVersion == null) {
                errorMessages.add(
                    """
                        The AAR metadata for dependency '$displayName' has an invalid
                        $MIN_ANDROID_GRADLE_PLUGIN_VERSION_PROPERTY value ($minAgpVersion).

                        $MIN_ANDROID_GRADLE_PLUGIN_VERSION_PROPERTY must be a stable AGP version,
                        formatted with major, minor, and micro values (for example "4.0.0").
                        """.trimIndent()
                )
            } else {
                val parsedAgpVersion =
                    GradleVersion.parseAndroidGradlePluginVersion(parameters.agpVersion.get())
                if (parsedMinAgpVersion > parsedAgpVersion) {
                    errorMessages.add(
                        """
                            Dependency '$displayName' requires Android Gradle plugin $minAgpVersion or higher.

                            This build currently uses Android Gradle plugin ${parameters.agpVersion.get()}.
                            """.trimIndent()
                    )
                }
            }
        }
    }

    /**
     * Return the [Int] API version, given a string representation, or
     * [SdkVersionInfo.HIGHEST_KNOWN_API] + 1 if an unknown version.
     */
    private fun getApiIntFromString(sdkVersion: String): Int {
        val compileData = parseTargetHash(sdkVersion)
        if (compileData.apiLevel != null) {
            // this covers normal compileSdk + addons.
            return compileData.apiLevel
        }

        if (compileData.codeName != null) {
            return SdkVersionInfo.getApiByPreviewName(compileData.codeName, true)
        }

        // this should not happen since the target hash should be valid (this is running inside a
        // task).
        throw RuntimeException("Unsupported target hash: $sdkVersion")
    }
}

/** [WorkParameters] for [CheckAarMetadataWorkAction] */
abstract class CheckAarMetadataWorkParameters: WorkParameters {
    abstract val aarMetadataArtifacts: ListProperty<AarMetadataArtifact>
    abstract val aarFormatVersion: Property<String>
    abstract val aarMetadataVersion: Property<String>
    abstract val compileSdkVersion: Property<String>
    abstract val platformSdkExtension: Property<Int>
    abstract val platformSdkApiLevel: Property<Int>
    abstract val agpVersion: Property<String>
    abstract val maxRecommendedStableCompileSdkVersionForThisAgp: Property<Int>
    abstract val projectPath: Property<String>
}

data class AarMetadataReader(val inputStream: InputStream) {

    val aarFormatVersion: String?
    val aarMetadataVersion: String?
    val minCompileSdk: String?
    val minAgpVersion: String?
    val forceCompileSdkPreview: String?
    val minCompileSdkExtension: String?

    constructor(file: File) : this(file.inputStream())

    init {
        val properties = Properties()
        inputStream.use { properties.load(it) }
        aarFormatVersion = properties.getProperty(AAR_FORMAT_VERSION_PROPERTY)
        aarMetadataVersion = properties.getProperty(AAR_METADATA_VERSION_PROPERTY)
        minCompileSdk = properties.getProperty(MIN_COMPILE_SDK_PROPERTY)
        minAgpVersion = properties.getProperty(MIN_ANDROID_GRADLE_PLUGIN_VERSION_PROPERTY)
        forceCompileSdkPreview = properties.getProperty("forceCompileSdkPreview")
        minCompileSdkExtension = properties.getProperty("minCompileSdkExtension")
    }
}

data class AarMetadataArtifact(val file: File, val displayName: String): Serializable

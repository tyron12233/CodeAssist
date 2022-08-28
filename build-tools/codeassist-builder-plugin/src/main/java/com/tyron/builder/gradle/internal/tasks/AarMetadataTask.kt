package com.tyron.builder.gradle.internal.tasks

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File

/**
 * A task that writes the AAR metadata file
 *
 * Caching disabled by default for this task because the task does very little work.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 *  simply executing the task.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.METADATA, secondaryTaskCategories = [TaskCategory.SOURCE_GENERATION])
abstract class AarMetadataTask : NonIncrementalTask() {

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:Input
    abstract val aarFormatVersion: Property<String>

    @get:Input
    abstract val aarMetadataVersion: Property<String>

    /**
     * The minimum SDK API-level any consuming module must be compiled against to use this library.
     */
    @get:Input
    abstract val minCompileSdk: Property<Int>

    /**
     * The codename of the SDK being compiled against (if it's a preview version); e.g., "Tiramisu".
     * If present, any consuming module must be compiled against an SDK with the exact same
     * codename.
     */
    @get:Input
    @get:Optional
    abstract val forceCompileSdkPreview: Property<String>

    /**
     * The minimum SDK extension version any consuming module must be compiled against to use this
     * library.
     */
    @get:Input
    abstract val minCompileSdkExtension: Property<Int>

    @get:Input
    abstract val minAgpVersion: Property<String>

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(AarMetadataWorkAction::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.output.set(output)
            it.aarFormatVersion.set(aarFormatVersion)
            it.aarMetadataVersion.set(aarMetadataVersion)
            it.minCompileSdk.set(minCompileSdk)
            it.minAgpVersion.set(minAgpVersion)
            it.forceCompileSdkPreview.set(forceCompileSdkPreview)
            it.minCompileSdkExtension.set(minCompileSdkExtension)
        }
    }

//    class CreationAction(
//        creationConfig: AarCreationConfig
//    ) : VariantTaskCreationAction<AarMetadataTask, AarCreationConfig>(creationConfig) {
//
//        override val name: String
//            get() = computeTaskName("write", "AarMetadata")
//
//        override val type: Class<AarMetadataTask>
//            get() = AarMetadataTask::class.java
//
//        override fun handleProvider(
//            taskProvider: TaskProvider<AarMetadataTask>
//        ) {
//            super.handleProvider(taskProvider)
//
//            creationConfig.artifacts
//                .setInitialProvider(taskProvider, AarMetadataTask::output)
//                .withName(creationConfig.getArtifactName(AAR_METADATA_FILE_NAME))
//                .on(InternalArtifactType.AAR_METADATA)
//        }
//
//        override fun configure(task: AarMetadataTask) {
//            super.configure(task)
//
//            task.aarFormatVersion.setDisallowChanges(AAR_FORMAT_VERSION)
//            task.aarMetadataVersion.setDisallowChanges(AAR_METADATA_VERSION)
//            task.minCompileSdk.setDisallowChanges(creationConfig.aarMetadata.minCompileSdk)
//            task.minAgpVersion.setDisallowChanges(creationConfig.aarMetadata.minAgpVersion)
//            task.forceCompileSdkPreview.setDisallowChanges(
//                parseTargetHash(creationConfig.global.compileSdkHashString).codeName
//            )
//            task.minCompileSdkExtension.setDisallowChanges(
//                creationConfig.aarMetadata.minCompileSdkExtension
//            )
//        }
//    }

    companion object {
        const val AAR_METADATA_FILE_NAME = "aar-metadata.properties"
        const val AAR_METADATA_ENTRY_PATH =
            "META-INF/com/android/build/gradle/$AAR_METADATA_FILE_NAME"
        const val AAR_FORMAT_VERSION = "1.0"
        const val AAR_METADATA_VERSION = "1.0"
        const val DEFAULT_MIN_AGP_VERSION = "1.0.0"
        const val DEFAULT_MIN_COMPILE_SDK_EXTENSION = 0
    }
}

/** [WorkAction] to write AAR metadata file */
abstract class AarMetadataWorkAction: WorkAction<AarMetadataWorkParameters> {

    override fun execute() {
//        val minAgpVersion = parameters.minAgpVersion.get()
//        val parsedMinAgpVersion = tryParseStableAndroidGradlePluginVersion(minAgpVersion)
//            ?: throw RuntimeException(
//                "The specified minAgpVersion ($minAgpVersion) is not valid. The minAgpVersion " +
//                        "must be a stable AGP version, formatted with major, minor, and micro " +
//                        "values (for example \"4.0.0\")."
//            )
//        val currentAgpVersion = parseAndroidGradlePluginVersion(ANDROID_GRADLE_PLUGIN_VERSION)
//        if (parsedMinAgpVersion > currentAgpVersion) {
//            throw RuntimeException(
//                "The specified minAgpVersion ($minAgpVersion) is not valid because it is a later " +
//                        "version than the version of AGP used for this build ($currentAgpVersion)."
//            )
//        }
        writeAarMetadataFile(
            parameters.output.get().asFile,
            parameters.aarFormatVersion.get(),
            parameters.aarMetadataVersion.get(),
            parameters.minCompileSdk.get(),
            parameters.minCompileSdkExtension.get(),
            parameters.minAgpVersion.get(),
            parameters.forceCompileSdkPreview.orNull
        )
    }
}

/** [WorkParameters] for [AarMetadataWorkAction] */
abstract class AarMetadataWorkParameters: WorkParameters {
    abstract val output: RegularFileProperty
    abstract val aarFormatVersion: Property<String>
    abstract val aarMetadataVersion: Property<String>
    abstract val minCompileSdk: Property<Int>
    abstract val minAgpVersion: Property<String>
    abstract val forceCompileSdkPreview: Property<String>
    abstract val minCompileSdkExtension: Property<Int>
}

/** Writes an AAR metadata file with the given parameters */
fun writeAarMetadataFile(
    file: File,
    aarFormatVersion: String,
    aarMetadataVersion: String,
    minCompileSdk: Int,
    minCompileSdkExtension: Int,
    minAgpVersion: String,
    forceCompileSdkPreview: String? = null
) {
    // We write the file manually instead of using the java.util.Properties API because (1) that API
    // doesn't guarantee the order of properties in the file and (2) that API writes an unnecessary
    // timestamp in the file.
    file.bufferedWriter().use { writer ->
//        writer.appendLine("$AAR_FORMAT_VERSION_PROPERTY=$aarFormatVersion")
//        writer.appendLine("$AAR_METADATA_VERSION_PROPERTY=$aarMetadataVersion")
//        writer.appendLine("$MIN_COMPILE_SDK_PROPERTY=$minCompileSdk")
//        writer.appendLine("$MIN_COMPILE_SDK_EXTENSION_PROPERTY=$minCompileSdkExtension")
//        writer.appendLine("$MIN_ANDROID_GRADLE_PLUGIN_VERSION_PROPERTY=$minAgpVersion")
//        forceCompileSdkPreview?.let { writer.appendLine("$FORCE_COMPILE_SDK_PREVIEW_PROPERTY=$it") }
    }
}
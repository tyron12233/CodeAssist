package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants.*
import com.android.Version
import com.android.utils.FileUtils
import com.google.common.io.Files
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.privaysandboxsdk.PrivacySandboxSdkInternalArtifactType
import com.tyron.builder.gradle.internal.privaysandboxsdk.PrivacySandboxSdkVariantScope
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.TaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.gradle.options.StringOption
import com.tyron.builder.internal.packaging.IncrementalPackager.APP_METADATA_FILE_NAME
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * A task that writes the app metadata
 *
 * Caching disabled by default for this task because the task does very little work.
 * Input values are written to a minimal Properties file and no computation is required.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.METADATA)
abstract class AppMetadataTask : NonIncrementalTask() {

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @get:Input abstract val appMetadataVersion: Property<String>

    @get:Input abstract val agpVersion: Property<String>

    @get:Input @get:Optional abstract val agdeVersion: Property<String>

    override fun doTaskAction() {
        val appMetadataFile = outputFile.get().asFile
        FileUtils.deleteIfExists(appMetadataFile)
        Files.createParentDirs(appMetadataFile)
        writeAppMetadataFile(
            appMetadataFile,
            appMetadataVersion.get(),
            agpVersion.get(),
            agdeVersion.orNull,
        )
    }

    private fun configureTaskInputs(projectOptions: ProjectOptions) {
        appMetadataVersion.setDisallowChanges(APP_METADATA_VERSION)
        agpVersion.setDisallowChanges(Version.ANDROID_GRADLE_PLUGIN_VERSION)
        agdeVersion.setDisallowChanges(projectOptions.getProvider(StringOption.IDE_AGDE_VERSION))
    }

    class CreationAction(creationConfig: ApplicationCreationConfig) :
        VariantTaskCreationAction<AppMetadataTask, ApplicationCreationConfig>(creationConfig) {
        override val type = AppMetadataTask::class.java
        override val name = computeTaskName("write", "AppMetadata")

        override fun handleProvider(taskProvider: TaskProvider<AppMetadataTask>) {
            super.handleProvider(taskProvider)
            taskProvider.configureTaskOutputs(creationConfig.artifacts)
        }

        override fun configure(task: AppMetadataTask) {
            super.configure(task)
            task.configureTaskInputs(creationConfig.services.projectOptions)
        }
    }

    class PrivacySandboxSdkCreationAction(
        private val creationConfig: PrivacySandboxSdkVariantScope
    ) : TaskCreationAction<AppMetadataTask>() {
        override val type = AppMetadataTask::class.java
        override val name = "writeAppMetadata"

        override fun handleProvider(taskProvider: TaskProvider<AppMetadataTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts
                .setInitialProvider(taskProvider, AppMetadataTask::outputFile)
                .withName(APP_METADATA_FILE_NAME)
                .on(PrivacySandboxSdkInternalArtifactType.APP_METADATA)
        }

        override fun configure(task: AppMetadataTask) {
            task.configureVariantProperties("", task.project.gradle.sharedServices)
            task.configureTaskInputs(creationConfig.services.projectOptions)
        }
    }

    // CreationAction for use in AssetPackBundlePlugin
    class CreationForAssetPackBundleAction(
        private val artifacts: ArtifactsImpl,
        private val projectOptions: ProjectOptions,
    ) : TaskCreationAction<AppMetadataTask>() {
        override val type = AppMetadataTask::class.java
        override val name = "writeAppMetadata"

        override fun handleProvider(taskProvider: TaskProvider<AppMetadataTask>) {
            super.handleProvider(taskProvider)
            taskProvider.configureTaskOutputs(artifacts)
        }

        override fun configure(task: AppMetadataTask) {
            task.configureVariantProperties("", task.project.gradle.sharedServices)
            task.configureTaskInputs(projectOptions)
        }
    }

    companion object {
        const val APP_METADATA_VERSION = "1.1"
    }
}

/** Writes an app metadata file with the given parameters */
private fun writeAppMetadataFile(
    file: File,
    appMetadataVersion: String,
    agpVersion: String,
    agdeVersion: String?,
) {
    // We write the file manually instead of using the java.util.Properties API because (1) that API
    // doesn't guarantee the order of properties in the file and (2) that API writes an unnecessary
    // timestamp in the file.
    file.bufferedWriter().use { writer ->
        writer.appendLine("$APP_METADATA_VERSION_PROPERTY=$appMetadataVersion")
        writer.appendLine("$ANDROID_GRADLE_PLUGIN_VERSION_PROPERTY=$agpVersion")
        if (agdeVersion != null) {
            writer.appendLine("$ANDROID_GAME_DEVELOPMENT_EXTENSION_VERSION_PROPERTY=$agdeVersion")
        }
    }
}

private fun TaskProvider<AppMetadataTask>.configureTaskOutputs(artifacts: ArtifactsImpl) {
    artifacts
        .setInitialProvider(this, AppMetadataTask::outputFile)
        .withName(APP_METADATA_FILE_NAME)
        .on(InternalArtifactType.APP_METADATA)
}

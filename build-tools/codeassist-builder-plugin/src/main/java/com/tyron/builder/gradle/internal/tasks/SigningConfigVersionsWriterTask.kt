package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.signing.SigningConfigVersions
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.options.OptionalBooleanOption
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Task that writes the [SigningConfigVersions] information to a file.
 *
 * Caching disabled by default for this task because the task does very little work.
 * Input values are written to a minimal JSON file and no computation is required.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.APK_PACKAGING)
abstract class SigningConfigVersionsWriterTask : NonIncrementalTask() {

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val enableV1Signing: Property<Boolean>

    @get:Input
    abstract val enableV2Signing: Property<Boolean>

    @get:Input
    abstract val enableV3Signing: Property<Boolean>

    @get:Input
    abstract val enableV4Signing: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val overrideEnableV1Signing: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val overrideEnableV2Signing: Property<Boolean>

    public override fun doTaskAction() {
        SigningConfigUtils.saveSigningConfigVersions(
            outputFile.get().asFile,
            SigningConfigVersions(
                enableV1Signing = overrideEnableV1Signing.orNull ?: enableV1Signing.get(),
                enableV2Signing = overrideEnableV2Signing.orNull ?: enableV2Signing.get(),
                enableV3Signing = enableV3Signing.get(),
                enableV4Signing = enableV4Signing.get()
            )
        )
    }

    class CreationAction(creationConfig: ApkCreationConfig) :
        VariantTaskCreationAction<SigningConfigVersionsWriterTask, ApkCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("write", "signingConfigVersions")

        override val type: Class<SigningConfigVersionsWriterTask>
            get() = SigningConfigVersionsWriterTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<SigningConfigVersionsWriterTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts
                .setInitialProvider(
                    taskProvider,
                    SigningConfigVersionsWriterTask::outputFile
                ).withName("signing-config-versions.json")
                .on(InternalArtifactType.SIGNING_CONFIG_VERSIONS)
        }

        override fun configure(
            task: SigningConfigVersionsWriterTask
        ) {
            super.configure(task)

            val signingConfig = creationConfig.signingConfigImpl
            if (signingConfig == null) {
                task.enableV1Signing.setDisallowChanges(false)
                task.enableV2Signing.setDisallowChanges(false)
                task.enableV3Signing.setDisallowChanges(false)
                task.enableV4Signing.setDisallowChanges(false)
            } else {
                task.enableV1Signing.setDisallowChanges(signingConfig.enableV1Signing)
                task.enableV2Signing.setDisallowChanges(signingConfig.enableV2Signing)
                task.enableV3Signing.setDisallowChanges(signingConfig.enableV3Signing)
                task.enableV4Signing.setDisallowChanges(signingConfig.enableV4Signing)
            }

            task.overrideEnableV1Signing.setDisallowChanges(
                creationConfig.services.projectOptions.get(OptionalBooleanOption.SIGNING_V1_ENABLED)
            )
            task.overrideEnableV2Signing.setDisallowChanges(
                creationConfig.services.projectOptions.get(OptionalBooleanOption.SIGNING_V2_ENABLED)
            )
        }
    }
}

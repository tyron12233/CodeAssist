package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.signing.SigningConfigData
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Task that writes the SigningConfig information to a file, excluding the information about which
 * signature versions are enabled, which is handled by [SigningConfigVersionsWriterTask].
 *
 * Caching disabled by default for this task because the task does very little work.
 * Input values are written to a minimal JSON file and no computation is required.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.APK_PACKAGING)
abstract class SigningConfigWriterTask : NonIncrementalTask() {

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val validatedSigningOutput: DirectoryProperty

    @get:Nested
    @get:Optional
    abstract val signingConfigData: Property<SigningConfigData?>

    // Add the store file path as an input as SigningConfigData ignores it (see its javadoc). This
    // will break cache relocatability, but we have to accept it for correctness (see bug
    // 135509623#comment6).
    @get:Input
    @get:Optional
    abstract val storeFilePath: Property<String?>

    public override fun doTaskAction() {
        SigningConfigUtils.saveSigningConfigData(outputFile.get().asFile, signingConfigData.orNull)
    }

    class CreationAction(creationConfig: ApkCreationConfig) :
        VariantTaskCreationAction<SigningConfigWriterTask, ApkCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("signingConfigWriter")

        override val type: Class<SigningConfigWriterTask>
            get() = SigningConfigWriterTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<SigningConfigWriterTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts
                .setInitialProvider(
                    taskProvider,
                    SigningConfigWriterTask::outputFile
                ).withName("signing-config-data.json")
                .on(InternalArtifactType.SIGNING_CONFIG_DATA)
        }

        override fun configure(
            task: SigningConfigWriterTask
        ) {
            super.configure(task)

            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.VALIDATE_SIGNING_CONFIG,
                task.validatedSigningOutput
            )

            // wrap the next two task input in provider as SigningConfigData constructor resolves
            // providers during construction.
            task.signingConfigData.setDisallowChanges(
                creationConfig.services.provider {
                    val signingConfig = creationConfig.signingConfigImpl
                    if (signingConfig != null && !signingConfig.name.isNullOrEmpty()) {
                        SigningConfigData.fromSigningConfig(signingConfig)
                    } else null
                }
            )
            task.storeFilePath.setDisallowChanges(
                creationConfig.services.provider<String?> {
                    val signingConfig = creationConfig.signingConfigImpl
                    if (signingConfig != null && signingConfig.storeFile.isPresent) {
                        signingConfig.storeFile.get()?.path
                    } else null
                }
            )
        }
    }
}

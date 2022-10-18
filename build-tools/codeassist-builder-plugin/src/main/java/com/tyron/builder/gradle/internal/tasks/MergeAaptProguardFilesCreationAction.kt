package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.fromDisallowChanges
import org.gradle.api.tasks.TaskProvider

/**
 * Configuration action for a task to merge aapt proguard files.
 * See [MergeFileTask] for Task implementation.
 */
class MergeAaptProguardFilesCreationAction(
    creationConfig: ConsumableCreationConfig
) : VariantTaskCreationAction<MergeFileTask, ConsumableCreationConfig>(
    creationConfig
) {

    override val name: String
            get() = computeTaskName("merge", "AaptProguardFiles")
    override val type: Class<MergeFileTask>
        get() = MergeFileTask::class.java

    override fun handleProvider(
        taskProvider: TaskProvider<MergeFileTask>
    ) {
        super.handleProvider(taskProvider)

        creationConfig.artifacts.setInitialProvider(
            taskProvider,
            MergeFileTask::outputFile
        ).withName(SdkConstants.FN_MERGED_AAPT_RULES)
            .on(InternalArtifactType.MERGED_AAPT_PROGUARD_FILE)
    }

    override fun configure(
        task: MergeFileTask
    ) {
        super.configure(task)

        val inputFiles =
            creationConfig
                .services
                .fileCollection(
                    creationConfig.artifacts.get(InternalArtifactType.AAPT_PROGUARD_FILE),
                    creationConfig.variantDependencies.getArtifactFileCollection(
                        AndroidArtifacts.ConsumedConfigType.REVERSE_METADATA_VALUES,
                        AndroidArtifacts.ArtifactScope.PROJECT,
                        AndroidArtifacts.ArtifactType.AAPT_PROGUARD_RULES
                    )
                )
        task.inputFiles.fromDisallowChanges(inputFiles)
    }
}

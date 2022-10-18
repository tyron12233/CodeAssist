package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants
import com.tyron.builder.api.artifact.ScopedArtifact
import com.tyron.builder.api.variant.ScopedArtifacts
import com.tyron.builder.dexing.isProguardRule
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.fromDisallowChanges
import org.gradle.api.tasks.TaskProvider

/**
 * Configuration action for a task to merge generated proguard files.
 * See [MergeFileTask] for Task implementation.
 */
class MergeGeneratedProguardFilesCreationAction(
    creationConfig: ComponentCreationConfig
) : VariantTaskCreationAction<MergeFileTask, ComponentCreationConfig>(
    creationConfig
) {

    override val name: String
            get() = computeTaskName("merge", "GeneratedProguardFiles")
    override val type: Class<MergeFileTask>
        get() = MergeFileTask::class.java

    override fun handleProvider(
        taskProvider: TaskProvider<MergeFileTask>
    ) {
        super.handleProvider(taskProvider)
        creationConfig.artifacts.setInitialProvider(
            taskProvider,
            MergeFileTask::outputFile
        ).withName(SdkConstants.FN_PROGUARD_TXT).on(InternalArtifactType.GENERATED_PROGUARD_FILE)
    }

    override fun configure(
        task: MergeFileTask
    ) {
        super.configure(task)

        val allClasses = creationConfig.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .getFinalArtifacts(ScopedArtifact.CLASSES)
        val proguardFiles = allClasses.elements.map { _ ->
            allClasses.asFileTree.filter { f ->
                val baseFolders = allClasses.files
                val baseFolder = baseFolders.first { it -> f.startsWith(it) }
                isProguardRule(f.relativeTo(baseFolder).invariantSeparatorsPath)
            }
        }
        task.inputFiles.fromDisallowChanges(proguardFiles)
    }
}

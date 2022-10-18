package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.fromDisallowChanges
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ART_PROFILE, secondaryTaskCategories = [TaskCategory.MERGING])
abstract class MergeArtProfileTask: MergeFileTask() {

    @get:[InputFiles PathSensitive(PathSensitivity.RELATIVE)]
    abstract override val inputFiles: ConfigurableFileCollection

    // Use InputFiles rather than InputFile to allow the file not to exist
    @get:[InputFiles PathSensitive(PathSensitivity.RELATIVE)]
    abstract val profileSource: RegularFileProperty

    @TaskAction
    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(MergeFilesWorkAction::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.inputFiles.from(inputFiles)
            if (profileSource.get().asFile.isFile) {
                it.inputFiles.from(profileSource)
            }
            it.outputFile.set(outputFile)
        }
    }

    abstract class MergeFilesWorkAction: WorkAction<MergeFilesWorkAction.Parameters> {
        abstract class Parameters : WorkParameters {
            abstract val inputFiles: ConfigurableFileCollection
            abstract val outputFile: RegularFileProperty
        }

        override fun execute() {
            mergeFiles(parameters.inputFiles.files, parameters.outputFile.get().asFile)
        }
    }

    class CreationAction(
            creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<MergeArtProfileTask, ApkCreationConfig>(creationConfig) {

        override val name: String
            get() = creationConfig.computeTaskName("merge", "ArtProfile")
        override val type: Class<MergeArtProfileTask>
            get() = MergeArtProfileTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<MergeArtProfileTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                    taskProvider,
                    MergeFileTask::outputFile
            ).on(InternalArtifactType.MERGED_ART_PROFILE)
        }

        override fun configure(task: MergeArtProfileTask) {
            super.configure(task)
            val aarProfilesArtifactCollection = creationConfig
                    .variantDependencies
                    .getArtifactCollection(
                            AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                            AndroidArtifacts.ArtifactScope.ALL,
                            AndroidArtifacts.ArtifactType.ART_PROFILE
                    )
            task.inputFiles.fromDisallowChanges(aarProfilesArtifactCollection.artifactFiles)

            task.profileSource
                .fileProvider(
                    creationConfig.services.provider(creationConfig.variantSources::artProfile)
                )
            task.profileSource.disallowChanges()
        }
    }
}

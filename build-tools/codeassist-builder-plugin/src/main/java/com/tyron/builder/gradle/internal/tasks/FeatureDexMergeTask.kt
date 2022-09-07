package com.tyron.builder.gradle.internal.tasks
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * A task merging dex files in dynamic feature modules into a single artifact type.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.DEXING, secondaryTaskCategories = [TaskCategory.MERGING])
abstract class FeatureDexMergeTask : NonIncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dexDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(
            FeatureDexMergeWorkAction::class.java
        ) {
//            it.initializeFromAndroidVariantTask(this)
            it.dexDirs.from(dexDirs)
            it.outputDir.set(outputDir)
        }
    }

    class CreationAction(
        creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<FeatureDexMergeTask, ApkCreationConfig>(
        creationConfig
    ) {
        override val name = computeTaskName("featureDexMerge")
        override val type = FeatureDexMergeTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<FeatureDexMergeTask>) {
            super.handleProvider(taskProvider)
            creationConfig
                .artifacts
                .setInitialProvider(taskProvider, FeatureDexMergeTask::outputDir)
                .on(InternalArtifactType.FEATURE_PUBLISHED_DEX)
        }

        override fun configure(task: FeatureDexMergeTask) {
            super.configure(task)
            task.dexDirs.from(creationConfig.artifacts.getAll(InternalMultipleArtifactType.DEX))
            task.outputs.doNotCacheIf(
                "This is a copy paste task, so the cacheability overhead could outweigh its benefit"
            ) { true }
        }
    }
}

abstract class FeatureDexMergeWorkAction
    : WorkAction<FeatureDexMergeWorkAction.Params>
{
    abstract class Params: WorkParameters {
        abstract val dexDirs: ConfigurableFileCollection
        abstract val outputDir: DirectoryProperty
    }

    override fun execute() {
        val inputFiles = parameters.dexDirs.asFileTree.files
        val outputFolder = parameters.outputDir.get().asFile
        inputFiles.forEachIndexed { index, file ->
            file.copyTo(outputFolder.resolve("$index.dex"))
        }
    }
}

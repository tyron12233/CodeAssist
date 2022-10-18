package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * A task merging desugar lib keep rules generated in dynamic feature modules.
 *
 * Caching disabled by default for this task because the task does very little work.
 * Input files are read from disk and concatenated into a single output file.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.DEXING, secondaryTaskCategories = [TaskCategory.SOURCE_PROCESSING])
abstract class DesugarLibKeepRulesMergeTask : NonIncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val keepRulesFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val mergedKeepRules: RegularFileProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(
            MergeKeepRulesWorkAction::class.java
        ) {
//            it.initializeFromAndroidVariantTask(this)
            it.mergedKeepRules.set(mergedKeepRules)
            it.keepRulesFiles.from(keepRulesFiles)
        }
    }

    class CreationAction(
        creationConfig: ApkCreationConfig,
        private val enableDexingArtifactTransform: Boolean,
        private val separateFileDependenciesDexingTask: Boolean
    ) : VariantTaskCreationAction<DesugarLibKeepRulesMergeTask, ApkCreationConfig>(
        creationConfig
    ) {
        override val name = computeTaskName("desugarLibKeepRulesMerge")
        override val type = DesugarLibKeepRulesMergeTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<DesugarLibKeepRulesMergeTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts
                .setInitialProvider(taskProvider, DesugarLibKeepRulesMergeTask::mergedKeepRules)
                .withName("mergedDesugarLibKeepRules")
                .on(InternalArtifactType.DESUGAR_LIB_MERGED_KEEP_RULES)
        }

        override fun configure(task: DesugarLibKeepRulesMergeTask) {
            super.configure(task)

            setDesugarLibKeepRules(
                task.keepRulesFiles,
                creationConfig,
                enableDexingArtifactTransform,
                separateFileDependenciesDexingTask
            )
        }
    }
}

abstract class MergeKeepRulesWorkAction
    : WorkAction<MergeKeepRulesWorkAction.Params>
{
    abstract class Params: WorkParameters {
        abstract val mergedKeepRules: RegularFileProperty
        abstract val keepRulesFiles: ConfigurableFileCollection
    }

    override fun execute() {
        val outputFile = parameters.mergedKeepRules.asFile.get()
        val inputFiles = parameters.keepRulesFiles.asFileTree.files

        inputFiles.forEach{
            outputFile.appendText(it.readText())
        }
    }
}

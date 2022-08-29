package com.tyron.builder.gradle.internal.res

import com.android.SdkConstants
import com.android.utils.FileUtils
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

/**
 *  Task to create an empty R.txt and an empty res/ directory.
 *
 *  This task is used when resource processing in an Android Library module is disabled. Instead of
 *  multiple tasks merging, parsing and processing resources, the user can fully disable the
 *  resource pipeline in a library module and have this task generate the empty artifacts instead.
 *
 *  The R.txt and res/ directory are required artifacts in an AAR, even when empty, so we still need
 *  to generate them. We can however skip generating the public.txt, since it's not required
 *  (missing public.txt means all resources in the R.txt in that AAR are public, but since the R.txt
 *  is empty, we can safely skip the public.txt file).
 *
 *  Caching disabled by default for this task because the task does very little work.
 *  Calculating cache hit/miss and fetching results is likely more expensive than
 *    simply executing the task.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES)
abstract class GenerateEmptyResourceFilesTask : NonIncrementalTask() {

    @get:OutputFile
    abstract val emptyRDotTxt: RegularFileProperty

    @get:OutputDirectory
    abstract val emptyMergedResources: DirectoryProperty

    override fun doTaskAction() {
        // TODO(147579629): should this contain transitive resources or is it okay to have it empty?
        // Create empty R.txt, will be used for bundling in the AAR.
        emptyRDotTxt.asFile.get().writeText("")

        // Create empty res/ directory to bundle in the AAR.
        FileUtils.mkdirs(emptyMergedResources.asFile.get())
    }

    class CreateAction(creationConfig: ComponentCreationConfig) :
        VariantTaskCreationAction<GenerateEmptyResourceFilesTask, ComponentCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("generate", "EmptyResourceFiles")
        override val type: Class<GenerateEmptyResourceFilesTask>
            get() = GenerateEmptyResourceFilesTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<GenerateEmptyResourceFilesTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                GenerateEmptyResourceFilesTask::emptyRDotTxt
            ).withName(SdkConstants.FN_RESOURCE_TEXT).on(InternalArtifactType.COMPILE_SYMBOL_LIST)

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                GenerateEmptyResourceFilesTask::emptyMergedResources
            ).withName(SdkConstants.FD_RES).on(InternalArtifactType.PACKAGED_RES)
        }
    }
}
package com.tyron.builder.gradle.internal.tasks.databinding

import android.databinding.tool.DataBindingBuilder
import com.android.utils.FileUtils
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File

/**
 * Merges BR and Adapter artifacts from dependencies and serves it back to the annotation processor.
 * <b>
 * To account for V1 dependencies, we still copy their layout-info files from the compile classpath.

 * Caching disabled by default for this task because the task does very little work.
 * Some Input files are copied to the OutputDirectory and no computation is required.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.DATA_BINDING, secondaryTaskCategories = [TaskCategory.MERGING])
abstract class DataBindingMergeDependencyArtifactsTask : NonIncrementalTask() {
    /**
     * Classes available at Runtime. We extract BR files from there so that even if there is no
     * compile time dependency on a particular artifact, we can still generate the BR file for it.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    lateinit var runtimeDependencies: FileCollection
        private set
    /**
     * Classes that are available at Compile time. We use setter-store files in there so that
     * code access between dependencies is scoped to the compile classpath.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    lateinit var compileTimeDependencies: FileCollection
        private set
    /**
     * Folder which includes all merged artifacts.
     */
    @get:OutputDirectory
    abstract val outFolder: DirectoryProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(MergeArtifactsRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.outFolder.set(outFolder)
            it.compileTimeDependencies.set(compileTimeDependencies.asFileTree.files)
            it.runtimeDependencies.set(runtimeDependencies.asFileTree.files)
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<DataBindingMergeDependencyArtifactsTask, ComponentCreationConfig>(
        creationConfig
    ) {

        override val name: String
            get() = computeTaskName("dataBindingMergeDependencyArtifacts")
        override val type: Class<DataBindingMergeDependencyArtifactsTask>
            get() = DataBindingMergeDependencyArtifactsTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<DataBindingMergeDependencyArtifactsTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DataBindingMergeDependencyArtifactsTask::outFolder
            ).on(InternalArtifactType.DATA_BINDING_DEPENDENCY_ARTIFACTS)
        }

        override fun configure(
            task: DataBindingMergeDependencyArtifactsTask
        ) {
            super.configure(task)

            task.runtimeDependencies = creationConfig.variantDependencies.getArtifactFileCollection(
                AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                AndroidArtifacts.ArtifactScope.ALL,
                AndroidArtifacts.ArtifactType.DATA_BINDING_ARTIFACT
            )
            task.compileTimeDependencies = creationConfig.variantDependencies.getArtifactFileCollection(
                AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                AndroidArtifacts.ArtifactScope.ALL,
                AndroidArtifacts.ArtifactType.DATA_BINDING_ARTIFACT
            )
        }
    }
}

abstract class MergeArtifactsParams: WorkParameters {
    abstract val outFolder: DirectoryProperty
    abstract val compileTimeDependencies: SetProperty<File>
    abstract val runtimeDependencies: SetProperty<File>
}

abstract class MergeArtifactsRunnable: WorkAction<MergeArtifactsParams> {
    override fun execute() {
        parameters.run {
            val outputFolder = outFolder.get().asFile
            FileUtils.cleanOutputDir(outputFolder)

            compileTimeDependencies.get().filter { file ->
                DataBindingBuilder.RESOURCE_FILE_EXTENSIONS.any { ext ->
                    file.name.endsWith(ext)
                }
            }.forEach {
                FileUtils.copyFile(it, File(outputFolder, it.name))
            }
            // feature's base dependency does not show up in Runtime so we copy everything from
            // compile and add runtimeDeps on top of it. We still override files because compile
            // dependency may reference to an older version of the BR file in non-feature
            // compilation.
            runtimeDependencies.get().filter {
                it.name.endsWith(DataBindingBuilder.BR_FILE_EXT)
            }.forEach {
                FileUtils.copyFile(it, File(outputFolder, it.name))
            }
        }
    }
}
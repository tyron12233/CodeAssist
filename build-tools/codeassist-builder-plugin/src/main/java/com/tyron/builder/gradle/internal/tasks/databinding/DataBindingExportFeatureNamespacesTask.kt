package com.tyron.builder.gradle.internal.tasks.databinding

import android.databinding.tool.DataBindingBuilder
import android.databinding.tool.store.FeatureInfoList
import com.android.utils.FileUtils
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.featuresplit.FeatureSplitDeclaration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.tooling.BuildException
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.io.FileNotFoundException

/**
 * This task collects the feature information and exports their namespaces into a file which can be
 * read by the DataBindingAnnotationProcessor.
 *
 * Caching disabled by default for this task because the task does very little work.
 * The task reads a small number of files, parses the contents as JSON, and writes a new JSON file
 * containing some of the same data.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.DATA_BINDING)
abstract class DataBindingExportFeatureNamespacesTask : NonIncrementalTask() {
    // where to keep the log of the task
    @get:OutputDirectory abstract val packageListOutFolder: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    lateinit var featureDeclarations: FileCollection
        private set

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(ExportNamespacesRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.featureDeclarations.set(featureDeclarations.asFileTree.files)
            it.packageListOutFolder.set(packageListOutFolder.get().asFile)
        }
    }

    class CreationAction(
        creationConfig: VariantCreationConfig
    ) :
        VariantTaskCreationAction<DataBindingExportFeatureNamespacesTask, VariantCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("dataBindingExportFeatureNamespaces")
        override val type: Class<DataBindingExportFeatureNamespacesTask>
            get() = DataBindingExportFeatureNamespacesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<DataBindingExportFeatureNamespacesTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DataBindingExportFeatureNamespacesTask::packageListOutFolder
            ).on(InternalArtifactType.FEATURE_DATA_BINDING_BASE_FEATURE_INFO)
        }

        override fun configure(
            task: DataBindingExportFeatureNamespacesTask
        ) {
            super.configure(task)

            task.featureDeclarations = creationConfig.variantDependencies.getArtifactFileCollection(
                    AndroidArtifacts.ConsumedConfigType.REVERSE_METADATA_VALUES,
                    AndroidArtifacts.ArtifactScope.PROJECT,
                    AndroidArtifacts.ArtifactType.REVERSE_METADATA_FEATURE_DECLARATION
            )
        }
    }
}

abstract class ExportNamespacesParams : WorkParameters {
    abstract val featureDeclarations: SetProperty<File>
    abstract val packageListOutFolder: DirectoryProperty
}

abstract class ExportNamespacesRunnable: WorkAction<ExportNamespacesParams> {
    override fun execute() {
        val packages = mutableSetOf<String>()
        for (featureSplitDeclaration in parameters.featureDeclarations.get()) {
            try {
                val loaded = FeatureSplitDeclaration.load(featureSplitDeclaration)
                packages.add(loaded.namespace)
            } catch (e: FileNotFoundException) {
                throw BuildException("Cannot read features split declaration file", e)
            }
        }
        val outputFolder = parameters.packageListOutFolder.get().asFile
        FileUtils.cleanOutputDir(outputFolder)
        outputFolder.mkdirs()
        // save the list.
        FeatureInfoList(packages).serialize(
                File(
                    outputFolder,
                    DataBindingBuilder.FEATURE_PACKAGE_LIST_FILE_NAME
                )
        )
    }
}

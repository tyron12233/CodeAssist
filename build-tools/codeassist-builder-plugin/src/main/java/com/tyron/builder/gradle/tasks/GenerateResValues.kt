package com.tyron.builder.gradle.tasks

import com.android.utils.FileUtils
import com.tyron.builder.api.variant.ResValue
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.generators.ResValueGenerator
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.ResValuesTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.ResValuesTaskCreationActionImpl
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*
import java.io.File

@CacheableTask
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES, secondaryTaskCategories = [TaskCategory.SOURCE_GENERATION])
abstract class GenerateResValues : NonIncrementalTask() {

    // ----- PUBLIC TASK API -----

    @get:Internal
    val resOutputDir: File
        get() {
            return outputDirectory.get().asFile
        }

    // ----- PRIVATE TASK API -----
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val items: MapProperty<ResValue.Key, ResValue>

    override fun doTaskAction() {
        val folder = outputDirectory.get().asFile

        // Always clean up the directory before use.
        FileUtils.cleanOutputDir(folder)

        if (items.get().isNotEmpty()) {
            ResValueGenerator(folder, items.get()).generate()
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<GenerateResValues, ComponentCreationConfig>(
        creationConfig
    ), ResValuesTaskCreationAction by ResValuesTaskCreationActionImpl(creationConfig) {

        override val name = computeTaskName("generate", "ResValues")
        override val type = GenerateResValues::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<GenerateResValues>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.taskContainer.generateResValuesTask = taskProvider
            creationConfig.artifacts.setInitialProvider(
                taskProvider, GenerateResValues::outputDirectory
            ).atLocation(deprecatedGeneratedResOutputDir.get().asFile.absolutePath)
                .on(InternalArtifactType.GENERATED_RES)
        }

        override fun configure(
            task: GenerateResValues
        ) {
            super.configure(task)

            task.items.set(resValuesCreationConfig.resValues)
        }

        // use the old generated res output dir since some released plugins are directly referencing
        // the output folder location to generate resources in.
        val deprecatedGeneratedResOutputDir by lazy {
            creationConfig.paths.getGeneratedResourcesDir("resValues") }
    }
}
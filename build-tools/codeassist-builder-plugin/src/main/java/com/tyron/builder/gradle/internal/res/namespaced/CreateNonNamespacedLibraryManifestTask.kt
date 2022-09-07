package com.tyron.builder.gradle.internal.res.namespaced

import com.android.SdkConstants
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/**
 * Task to strip resource namespaces from the library's android manifest. This stripped manifest
 * needs to be bundled in the AAR as the AndroidManifest.xml artifact, so that it's consumable by
 * non-namespaced projects.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST, secondaryTaskCategories = [TaskCategory.SOURCE_GENERATION])
abstract class CreateNonNamespacedLibraryManifestTask : NonIncrementalTask() {

    @get:OutputFile
    abstract val outputStrippedManifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraryManifest: RegularFileProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation()
            .submit(CreateNonNamespacedLibraryManifestRunnable::class.java) {
//                it.initializeFromAndroidVariantTask(this)
                it.originalManifestFile.set(libraryManifest)
                it.strippedManifestFile.set(outputStrippedManifestFile)
            }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<CreateNonNamespacedLibraryManifestTask, ComponentCreationConfig>(
        creationConfig
    ) {

        override val name: String
            get() = computeTaskName("create", "NonNamespacedLibraryManifest")
        override val type: Class<CreateNonNamespacedLibraryManifestTask>
            get() = CreateNonNamespacedLibraryManifestTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<CreateNonNamespacedLibraryManifestTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                CreateNonNamespacedLibraryManifestTask::outputStrippedManifestFile
            ).withName(SdkConstants.ANDROID_MANIFEST_XML)
                .on(InternalArtifactType.NON_NAMESPACED_LIBRARY_MANIFEST)
        }

        override fun configure(
            task: CreateNonNamespacedLibraryManifestTask
        ) {
            super.configure(task)
            creationConfig.artifacts.setTaskInputToFinalProduct(
                SingleArtifact.MERGED_MANIFEST, task.libraryManifest
            )
        }
    }
}

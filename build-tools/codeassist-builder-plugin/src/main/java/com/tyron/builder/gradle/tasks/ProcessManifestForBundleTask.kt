package com.tyron.builder.gradle.tasks

import com.android.SdkConstants
import com.tyron.builder.api.variant.impl.BuiltArtifactsLoaderImpl
import com.tyron.builder.api.variant.impl.VariantOutputImpl
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Task that consumes [InternalArtifactType.MERGED_MANIFESTS] to produce a unique Android Manifest
 * file suitable for the bundle tool.
 *
 * The bundle tool manifest must have the android:splitName to all activities in case the
 * module is a dynamic feature module, otherwise it wil be unchanged.
 *
 * The merged manifest already has the annotated activities so we just need to copy the main
 * split unchanged. We cannot use republish because there can be many merged manifests, but there
 * is only one bundle tool manifest.
 *
 * Caching disabled by default for this task because the task does very little work.
 * An Input file is copied to the Output location without any changes.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST)
abstract class ProcessManifestForBundleTask: NonIncrementalTask() {

    @get:OutputFile
    abstract val bundleManifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val applicationMergedManifests: DirectoryProperty

    @get:Nested
    abstract val mainSplit: Property<VariantOutputImpl>

    @TaskAction
    override fun doTaskAction() {

        val builtArtifact = BuiltArtifactsLoaderImpl().load(applicationMergedManifests)
            ?.getBuiltArtifact(mainSplit.get())
            ?: throw RuntimeException("Cannot find main split from generated manifest files at" +
                    " ${applicationMergedManifests.asFile.get().absolutePath}")

        File(builtArtifact.outputFile).copyTo(
            target = bundleManifest.get().asFile, overwrite = true)
    }

    class CreationAction(creationConfig: ApkCreationConfig) :
        VariantTaskCreationAction<ProcessManifestForBundleTask, ApkCreationConfig>(
            creationConfig = creationConfig
        ) {
        override val name: String
            get() = computeTaskName("processApplicationManifest", "ForBundle")
        override val type: Class<ProcessManifestForBundleTask>
            get() = ProcessManifestForBundleTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<ProcessManifestForBundleTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ProcessManifestForBundleTask::bundleManifest
            )
                .withName(SdkConstants.ANDROID_MANIFEST_XML)
                .on(InternalArtifactType.BUNDLE_MANIFEST)
        }

        override fun configure(task: ProcessManifestForBundleTask) {
            super.configure(task)
            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.MERGED_MANIFESTS,
                task.applicationMergedManifests
            )
            task.mainSplit.setDisallowChanges(creationConfig.outputs.getMainSplit())
        }
    }
}

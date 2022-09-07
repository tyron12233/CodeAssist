package com.tyron.builder.gradle.tasks

import com.android.SdkConstants
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.api.variant.impl.BuiltArtifactImpl
import com.tyron.builder.api.variant.impl.BuiltArtifactsImpl
import com.tyron.builder.api.variant.impl.BuiltArtifactsLoaderImpl
import com.tyron.builder.api.variant.impl.VariantOutputImpl
import com.tyron.builder.api.variant.impl.dirName
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.manifest.mergeManifests
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.android.manifmerger.ManifestMerger2
import com.android.utils.FileUtils
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * Task that consumes [SingleArtifact.MERGED_MANIFEST] single merged manifest and create several
 * versions that are each suitable for all [VariantOutputImpl] for this variant.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST)
abstract class ProcessMultiApkApplicationManifest: ManifestProcessorTask() {

    @get:Nested
    abstract val variantOutputs: ListProperty<VariantOutputImpl>

    @get:Nested
    abstract val singleVariantOutput: Property<VariantOutputImpl>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val namespace: Property<String>

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    abstract val mainMergedManifest: RegularFileProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    @get:InputFiles
    abstract val compatibleScreensManifest: DirectoryProperty

    /** The merged Manifests files folder.  */
    @get:OutputDirectory
    abstract val multiApkManifestOutputDirectory: DirectoryProperty

    override fun doTaskAction() {
        // read the output of the compatible screen manifest.
        val compatibleScreenManifests =
            BuiltArtifactsLoaderImpl().load(compatibleScreensManifest)
                ?: throw RuntimeException(
                    "Cannot find generated compatible screen manifests, file a bug"
                )

        val multiApkManifestOutputs = mutableListOf<BuiltArtifactImpl>()

        for (variantOutput in variantOutputs.get()) {
            val compatibleScreenManifestForSplit =
                compatibleScreenManifests.getBuiltArtifact(variantOutput)

            val mergedManifestOutputFile =
                processVariantOutput(compatibleScreenManifestForSplit?.outputFile, variantOutput)

            multiApkManifestOutputs.add(
                variantOutput.toBuiltArtifact(mergedManifestOutputFile)
            )
        }
        BuiltArtifactsImpl(
            artifactType = InternalArtifactType.MERGED_MANIFESTS,
            applicationId = applicationId.get(),
            variantName = variantName,
            elements = multiApkManifestOutputs.toList()
        )
            .save(multiApkManifestOutputDirectory.get())
    }

    private fun processVariantOutput(
        compatibleScreensManifestFilePath: String?,
        variantOutput: VariantOutputImpl
    ): File {
        val dirName = variantOutput.dirName()

        val mergedManifestOutputFile = File(
            multiApkManifestOutputDirectory.get().asFile,
            FileUtils.join(
                dirName,
                SdkConstants.ANDROID_MANIFEST_XML
            )
        )

        if (compatibleScreensManifestFilePath == null) {
            if (variantOutput.versionCode.orNull == singleVariantOutput.get().versionCode.orNull
                && variantOutput.versionName.orNull == singleVariantOutput.get().versionName.orNull) {

                mainMergedManifest.get().asFile.copyTo(mergedManifestOutputFile, overwrite = true)
                return mergedManifestOutputFile
            }
        }
        mergeManifests(
            mainMergedManifest.get().asFile,
            if (compatibleScreensManifestFilePath != null)
                listOf(File(compatibleScreensManifestFilePath))
            else listOf(),
            listOf(),
            listOf(),
            null,
            packageOverride = null,
            namespace = namespace.get(),
            false,
            variantOutput.versionCode.orNull,
            variantOutput.versionName.orNull,
            null,
            null,
            null,
            testOnly = false,
            mergedManifestOutputFile.absolutePath /* aaptFriendlyManifestOutputFile */,
            null,
            ManifestMerger2.MergeType.APPLICATION,
            mapOf(),
            listOf(),
            listOf(),
            null,
            LoggerWrapper.getLogger(ProcessApplicationManifest::class.java)
        )
        return mergedManifestOutputFile
    }

    class CreationAction(
        creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<ProcessMultiApkApplicationManifest, ApkCreationConfig>(creationConfig) {
        override val name: String
            get() = computeTaskName("process", "Manifest")
        override val type: Class<ProcessMultiApkApplicationManifest>
            get() = ProcessMultiApkApplicationManifest::class.java

        override fun handleProvider(taskProvider: TaskProvider<ProcessMultiApkApplicationManifest>) {
            super.handleProvider(taskProvider)
            creationConfig.taskContainer.processManifestTask = taskProvider
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ProcessMultiApkApplicationManifest::multiApkManifestOutputDirectory
            ).on(InternalArtifactType.MERGED_MANIFESTS)
        }

        override fun configure(task: ProcessMultiApkApplicationManifest) {
            super.configure(task)

            creationConfig
                .outputs
                .getEnabledVariantOutputs()
                .forEach(task.variantOutputs::add)
            task.variantOutputs.disallowChanges()
            task.singleVariantOutput.setDisallowChanges(
                creationConfig.outputs.getMainSplit()
            )

            task.compatibleScreensManifest.setDisallowChanges(
                creationConfig.artifacts.get(
                    InternalArtifactType.COMPATIBLE_SCREEN_MANIFEST)
            )

            creationConfig
                .artifacts
                .setTaskInputToFinalProduct(
                    SingleArtifact.MERGED_MANIFEST,
                    task.mainMergedManifest
                )

            task.applicationId.setDisallowChanges(creationConfig.applicationId)
            task.namespace.setDisallowChanges(creationConfig.namespace)
        }
    }
}

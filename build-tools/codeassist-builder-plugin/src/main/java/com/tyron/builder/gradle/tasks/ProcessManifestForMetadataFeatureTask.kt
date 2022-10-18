package com.tyron.builder.gradle.tasks

import com.android.SdkConstants
import com.android.manifmerger.XmlDocument
import com.android.utils.PositionXmlParser
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.Serializable
import javax.inject.Inject

@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST)
abstract class ProcessManifestForMetadataFeatureTask : NonIncrementalTask() {

    @get:OutputFile
    abstract val metadataFeatureManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundleManifest: RegularFileProperty

    @get:Input
    abstract val dynamicFeature: Property<Boolean>

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    override fun doTaskAction() {

        val inputFile = bundleManifest.get().asFile
        val metadataFeatureManifestFile = metadataFeatureManifest.get().asFile
        // if there is no feature name to write, just use the original merged manifest file.
        if (!dynamicFeature.get()) {
            inputFile.copyTo(target = metadataFeatureManifestFile, overwrite = true)
            return
        }

        workerExecutor.noIsolation().submit(WorkItem::class.java) {
            it.inputXmlFile.set(bundleManifest)
            it.outputXmlFile.set(metadataFeatureManifestFile)
            it.namespace.set(namespace)
        }
    }

    interface WorkItemParameters: WorkParameters, Serializable {
        val inputXmlFile: RegularFileProperty
        val outputXmlFile: RegularFileProperty
        val namespace: Property<String>
    }

    abstract class WorkItem@Inject constructor(private val workItemParameters: WorkItemParameters)
        : WorkAction<WorkItemParameters> {
        override fun execute() {
            val xmlDocument = BufferedInputStream(
                FileInputStream(
                    workItemParameters.inputXmlFile.get().asFile
                )
            ).use {
                PositionXmlParser.parse(it)
            }
            stripMinSdkFromFeatureManifest(xmlDocument)
            stripUsesSplitFromFeatureManifest(xmlDocument)
            replacePackageNameInFeatureManifest(xmlDocument, workItemParameters.namespace.get())
            workItemParameters.outputXmlFile.get().asFile.writeText(
                XmlDocument.prettyPrint(xmlDocument))
        }
    }

    class CreationAction(creationConfig: ApkCreationConfig) :
        VariantTaskCreationAction<ProcessManifestForMetadataFeatureTask, ApkCreationConfig>(
            creationConfig = creationConfig
        ) {
        override val name: String
            get() = computeTaskName("processManifest", "ForFeature")
        override val type: Class<ProcessManifestForMetadataFeatureTask>
            get() = ProcessManifestForMetadataFeatureTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<ProcessManifestForMetadataFeatureTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                    ProcessManifestForMetadataFeatureTask::metadataFeatureManifest
            )
                .withName(SdkConstants.ANDROID_MANIFEST_XML)
                .on(InternalArtifactType.METADATA_FEATURE_MANIFEST)
        }

        override fun configure(task: ProcessManifestForMetadataFeatureTask) {
            super.configure(task)
            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.BUNDLE_MANIFEST,
                task.bundleManifest
            )
            task.dynamicFeature.set(creationConfig.componentType.isDynamicFeature)
            task.namespace.setDisallowChanges(creationConfig.namespace)
        }
    }
}

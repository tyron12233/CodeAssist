package com.tyron.builder.gradle.tasks

import com.android.SdkConstants
import com.tyron.builder.api.artifact.ArtifactTransformationRequest
import com.tyron.builder.api.variant.BuiltArtifact
import com.tyron.builder.api.variant.impl.dirName
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.workeractions.DecoratedWorkParameters
import com.tyron.builder.gradle.internal.workeractions.WorkActionAdapter
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.XmlDocument
import com.android.utils.FileUtils
import com.android.utils.PositionXmlParser
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.workers.WorkerExecutor
import org.w3c.dom.Document
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject

@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST)
abstract class ProcessManifestForInstantAppTask @Inject constructor(
    @get:Internal
    val workers: WorkerExecutor
): NonIncrementalTask() {

    @get:OutputDirectory
    abstract val instantAppManifests: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifests: DirectoryProperty

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<ProcessManifestForInstantAppTask>>

    @TaskAction
    override fun doTaskAction() {

        transformationRequest.get().submit(this,
            workers.noIsolation(),
            WorkItem::class.java)
        { builtArtifact: BuiltArtifact, directory: Directory, parameters: WorkItemParameters ->
            parameters.inputXmlFile.set(File(builtArtifact.outputFile))
            parameters.outputXmlFile.set(
                FileUtils.join(
                    directory.asFile,
                    builtArtifact.dirName(),
                    SdkConstants.ANDROID_MANIFEST_XML))
            parameters.outputXmlFile.get().asFile
        }
    }

    interface WorkItemParameters: DecoratedWorkParameters {
        val inputXmlFile: RegularFileProperty
        val outputXmlFile: RegularFileProperty
    }

    abstract class WorkItem@Inject constructor(private val parameters: WorkItemParameters)
        : WorkActionAdapter<WorkItemParameters> {

        override fun getParameters(): WorkItemParameters = parameters

        override fun doExecute() {
            val xmlDocument = BufferedInputStream(
                FileInputStream(
                    parameters.inputXmlFile.get().asFile)
            ).use {
                PositionXmlParser.parse(it)
            }
            setTargetSandboxVersionAttribute(xmlDocument)
            parameters.outputXmlFile.get().asFile.writeText(
                XmlDocument.prettyPrint(xmlDocument))
        }

        /**
         * Set "android:targetSandboxVersion" attribute for the manifest element.
         *
         * @param document the document whose attributes will be modified
         * @return the previous value of the targetSandboxVersion attribute or null if
         * targetSandboxVersion was not set.
         */
        private fun setTargetSandboxVersionAttribute(
            document: Document
        ): String? {
            return ManifestMerger2.setManifestAndroidAttribute(
                document, SdkConstants.ATTR_TARGET_SANDBOX_VERSION, "2"
            )
        }
    }

    class CreationAction(creationConfig: ApkCreationConfig) :
        VariantTaskCreationAction<ProcessManifestForInstantAppTask, ApkCreationConfig>(
            creationConfig = creationConfig
        ) {
        override val name: String
            get() = computeTaskName("process", "ManifestForInstantApp")
        override val type: Class<ProcessManifestForInstantAppTask>
            get() = ProcessManifestForInstantAppTask::class.java

        private lateinit var transformationRequest: ArtifactTransformationRequest<ProcessManifestForInstantAppTask>

        override fun handleProvider(taskProvider: TaskProvider<ProcessManifestForInstantAppTask>) {
            super.handleProvider(taskProvider)
            transformationRequest = creationConfig.artifacts.use(taskProvider)
                .wiredWithDirectories(
                    ProcessManifestForInstantAppTask::mergedManifests,
                    ProcessManifestForInstantAppTask::instantAppManifests)
                .toTransformMany(
                    InternalArtifactType.MERGED_MANIFESTS,
                    InternalArtifactType.INSTANT_APP_MANIFEST)
        }

        override fun configure(task: ProcessManifestForInstantAppTask) {
            super.configure(task)
            task.transformationRequest.setDisallowChanges(transformationRequest)
        }
    }
}

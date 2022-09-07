package com.tyron.builder.gradle.tasks

import com.android.SdkConstants
import com.tyron.builder.api.artifact.ArtifactTransformationRequest
import com.tyron.builder.api.variant.BuiltArtifact
import com.tyron.builder.api.variant.impl.dirName
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.manifest.ManifestProviderImpl
import com.tyron.builder.gradle.internal.tasks.manifest.mergeManifests
import com.tyron.builder.gradle.internal.workeractions.DecoratedWorkParameters
import com.tyron.builder.gradle.internal.workeractions.WorkActionAdapter
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.options.BooleanOption
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.MergingReport
import com.android.manifmerger.XmlDocument
import com.android.utils.FileUtils
import com.android.utils.PositionXmlParser
import com.tyron.builder.internal.utils.fromDisallowChanges
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
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
abstract class ProcessPackagedManifestTask @Inject constructor(
    objects: ObjectFactory,
    workers: WorkerExecutor
): NonIncrementalTask() {

    @get:OutputDirectory
    abstract val packageManifests: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifests: DirectoryProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    @get:InputFiles
    abstract val privacySandboxSdkManifestSnippets: ConfigurableFileCollection

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<ProcessPackagedManifestTask>>

    // Use a property to hold the [WorkerExecutor] so unit tests can reset it if necessary.
    @get:Internal
    val workersProperty: Property<WorkerExecutor> = objects.property(WorkerExecutor::class.java)

    init {
        workersProperty.set(workers)
    }

    @TaskAction
    override fun doTaskAction() {

        transformationRequest.get().submit(this,
            workersProperty.get().noIsolation(),
            WorkItem::class.java)
            { builtArtifact: BuiltArtifact, directory: Directory, parameters: WorkItemParameters ->

                parameters.inputXmlFile.set(File(builtArtifact.outputFile))
                parameters.privacySandboxSdkManifestSnippets.set(privacySandboxSdkManifestSnippets)
                parameters.outputXmlFile.set(
                    File(directory.asFile,
                        FileUtils.join(
                            builtArtifact.dirName(),
                            SdkConstants.ANDROID_MANIFEST_XML)))
                parameters.outputXmlFile.get().asFile

            }
    }

    interface WorkItemParameters: DecoratedWorkParameters {
        val inputXmlFile: RegularFileProperty
        val outputXmlFile: RegularFileProperty
        val privacySandboxSdkManifestSnippets: ListProperty<File>
    }

    abstract class WorkItem@Inject constructor(private val workItemParameters: WorkItemParameters)
        : WorkActionAdapter<WorkItemParameters> {
        override fun getParameters(): WorkItemParameters = workItemParameters

        override fun doExecute() {
            val inputFile = workItemParameters.inputXmlFile.get().asFile
            val manifestSnippets = workItemParameters.privacySandboxSdkManifestSnippets.get()

            val outputFile = workItemParameters.outputXmlFile.get().asFile
            outputFile.parentFile.mkdirs()

            val xmlDocument = if (manifestSnippets.isNotEmpty()) {
                mergeManifests(
                    mainManifest = inputFile,
                    manifestOverlays = emptyList(),
                    dependencies = manifestSnippets.map { ManifestProviderImpl(it, it.name) },
                    navigationJsons = emptyList(),
                    featureName = null,
                    packageOverride = null,
                    namespace = "",
                    profileable = false,
                    versionCode = null,
                    versionName = null,
                    minSdkVersion = null,
                    targetSdkVersion = null,
                    maxSdkVersion = null,
                    testOnly = false,
                    outMergedManifestLocation = outputFile.path,
                    outAaptSafeManifestLocation = null,
                    mergeType = ManifestMerger2.MergeType.APPLICATION,
                    placeHolders = emptyMap(),
                    optionalFeatures = emptyList(),
                    dependencyFeatureNames = emptyList(),
                    reportFile = null,
                    logger = LoggerWrapper.getLogger(ProcessPackagedManifestTask::class.java)
                ).getMergedXmlDocument(MergingReport.MergedManifestKind.MERGED)!!.xml
            } else {
                BufferedInputStream(FileInputStream(inputFile)).use {
                    PositionXmlParser.parse(it)
                }
            }
            removeSplitNames(document = xmlDocument)

            outputFile.writeText(XmlDocument.prettyPrint(xmlDocument))
        }
    }

    class CreationAction(creationConfig: ApkCreationConfig) :
        VariantTaskCreationAction<ProcessPackagedManifestTask, ApkCreationConfig>(
            creationConfig = creationConfig
        ) {
        override val name: String
            get() = computeTaskName("process", "ManifestForPackage")
        override val type: Class<ProcessPackagedManifestTask>
            get() = ProcessPackagedManifestTask::class.java

        private lateinit var transformationRequest: ArtifactTransformationRequest<ProcessPackagedManifestTask>

        override fun handleProvider(taskProvider: TaskProvider<ProcessPackagedManifestTask>) {
            super.handleProvider(taskProvider)
            transformationRequest = creationConfig.artifacts.use(taskProvider)
                .wiredWithDirectories(
                    ProcessPackagedManifestTask::mergedManifests,
                    ProcessPackagedManifestTask::packageManifests)
                .toTransformMany(
                    InternalArtifactType.MERGED_MANIFESTS,
                    InternalArtifactType.PACKAGED_MANIFESTS
                )
        }

        override fun configure(task: ProcessPackagedManifestTask) {
            super.configure(task)
            task.workersProperty.disallowChanges()
            task.transformationRequest.setDisallowChanges(transformationRequest)
            if (creationConfig.services.projectOptions[BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT] && creationConfig.componentType.isBaseModule) {
                task.privacySandboxSdkManifestSnippets.fromDisallowChanges(
                    creationConfig.variantDependencies.getArtifactFileCollection(
                        AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                        AndroidArtifacts.ArtifactScope.ALL,
                        AndroidArtifacts.ArtifactType.ANDROID_PRIVACY_SANDBOX_SDK_EXTRACTED_MANIFEST_SNIPPET
                    )
                )
            } else {
                task.privacySandboxSdkManifestSnippets.disallowChanges()
            }
        }
    }
}

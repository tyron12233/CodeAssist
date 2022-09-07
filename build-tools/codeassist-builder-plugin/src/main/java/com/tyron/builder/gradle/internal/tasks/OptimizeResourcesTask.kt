package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants
import com.android.ide.common.process.BaseProcessOutputHandler
import com.android.ide.common.process.CachedProcessOutputHandler
import com.android.ide.common.process.DefaultProcessExecutor
import com.android.ide.common.process.ProcessInfoBuilder
import com.android.utils.FileUtils
import com.android.utils.LineCollector
import com.android.utils.StdLogger
import com.tyron.builder.api.artifact.ArtifactTransformationRequest
import com.tyron.builder.api.variant.impl.VariantOutputImpl
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.services.Aapt2Input
import com.tyron.builder.gradle.internal.services.getAapt2Executable
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AndroidResourcesTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AndroidResourcesTaskCreationActionImpl
import com.tyron.builder.gradle.internal.workeractions.DecoratedWorkParameters
import com.tyron.builder.gradle.internal.workeractions.WorkActionAdapter
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import javax.inject.Inject

/**
 * OptimizeResourceTask attempts to use AAPT2's optimize sub-operation to reduce the size of the
 * final apk. There are a number of potential optimizations performed such as resource obfuscation,
 * path shortening and sparse encoding. If the optimized apk file size is less than before, then
 * the optimized resources content is made identical to [InternalArtifactType.PROCESSED_RES].
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.OPTIMIZATION, secondaryTaskCategories = [TaskCategory.ANDROID_RESOURCES])
abstract class OptimizeResourcesTask : NonIncrementalTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputProcessedRes: DirectoryProperty

    @get:Nested
    abstract val aapt2: Aapt2Input

    @get:Input
    abstract val enableResourceObfuscation: Property<Boolean>

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<OptimizeResourcesTask>>

    @get:Nested
    abstract val variantOutputs : ListProperty<VariantOutputImpl>

    @get:OutputDirectory
    abstract val optimizedProcessedRes: DirectoryProperty

    @TaskAction
    override fun doTaskAction() {
        transformationRequest.get().submit(
                this,
                workerExecutor.noIsolation(),
                Aapt2OptimizeWorkAction::class.java
        ) { builtArtifact, outputLocation: Directory, parameters ->
            val variantOutput = variantOutputs.get().find {
                it.variantOutputConfiguration.outputType == builtArtifact.outputType
                        && it.variantOutputConfiguration.filters == builtArtifact.filters
            } ?: throw java.lang.RuntimeException("Cannot find variant output for $builtArtifact")

            parameters.inputResFile.set(File(builtArtifact.outputFile))
            parameters.aapt2Executable.set(aapt2.getAapt2Executable().toFile())
            parameters.enableResourceObfuscation.set(enableResourceObfuscation.get())
            parameters.outputResFile.set(File(outputLocation.asFile,
                    "resources-${variantOutput.baseName}-optimize${SdkConstants.DOT_RES}"))

            parameters.outputResFile.get().asFile
        }
    }

    interface OptimizeResourcesParams : DecoratedWorkParameters {
        val aapt2Executable: RegularFileProperty
        val inputResFile: RegularFileProperty
        val enableResourceObfuscation: Property<Boolean>
        val outputResFile: RegularFileProperty
    }

    abstract class Aapt2OptimizeWorkAction
    @Inject constructor(private val params: OptimizeResourcesParams) : WorkActionAdapter<OptimizeResourcesParams> {
        override fun doExecute() = doFullTaskAction(params)
    }

    class CreateAction(
            creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<OptimizeResourcesTask, ComponentCreationConfig>(creationConfig),
        AndroidResourcesTaskCreationAction by AndroidResourcesTaskCreationActionImpl(creationConfig) {
        override val name: String
            get() = computeTaskName("optimize", "Resources")
        override val type: Class<OptimizeResourcesTask>
            get() = OptimizeResourcesTask::class.java

        private lateinit var transformationRequest: ArtifactTransformationRequest<OptimizeResourcesTask>


        override fun handleProvider(taskProvider: TaskProvider<OptimizeResourcesTask>) {
            super.handleProvider(taskProvider)
            val resourceShrinkingEnabled = androidResourcesCreationConfig.useResourceShrinker
            val operationRequest = creationConfig.artifacts.use(taskProvider).wiredWithDirectories(
                    OptimizeResourcesTask::inputProcessedRes,
                    OptimizeResourcesTask::optimizedProcessedRes)

            transformationRequest = if (resourceShrinkingEnabled) {
                operationRequest.toTransformMany(
                    InternalArtifactType.SHRUNK_PROCESSED_RES,
                    InternalArtifactType.OPTIMIZED_PROCESSED_RES)
            } else {
                operationRequest.toTransformMany(
                        InternalArtifactType.PROCESSED_RES,
                        InternalArtifactType.OPTIMIZED_PROCESSED_RES)
            }
        }

        override fun configure(task: OptimizeResourcesTask) {
            super.configure(task)
            val enabledVariantOutputs = creationConfig.outputs.getEnabledVariantOutputs()

            creationConfig.services.initializeAapt2Input(task.aapt2)

            task.enableResourceObfuscation.setDisallowChanges(false)

            task.transformationRequest.setDisallowChanges(transformationRequest)

            task.variantOutputs.setDisallowChanges(enabledVariantOutputs)
        }
    }
}

enum class AAPT2OptimizeFlags(val flag: String) {
    COLLAPSE_RESOURCE_NAMES("--collapse-resource-names"),
    SHORTEN_RESOURCE_PATHS("--shorten-resource-paths"),
    ENABLE_SPARSE_ENCODING("--enable-sparse-encoding")
}

internal fun doFullTaskAction(params: OptimizeResourcesTask.OptimizeResourcesParams)  {
    val inputFile = params.inputResFile.get().asFile
    val outputFile = params.outputResFile.get().asFile

    val optimizeFlags = mutableSetOf(
        AAPT2OptimizeFlags.SHORTEN_RESOURCE_PATHS.flag
    )
    if (params.enableResourceObfuscation.get()) {
        optimizeFlags += AAPT2OptimizeFlags.COLLAPSE_RESOURCE_NAMES.flag
    }

    val aaptInputFile = if (inputFile.isDirectory) {
        inputFile.listFiles()
                ?.filter {
                    it.extension == SdkConstants.EXT_RES
                            || it.extension == SdkConstants.EXT_ANDROID_PACKAGE
                }
                ?.get(0)
    } else {
        inputFile
    }

    aaptInputFile?.let {
        invokeAapt(
                params.aapt2Executable.get().asFile,
                "optimize",
                it.path,
                *optimizeFlags.toTypedArray(),
                "-o",
                outputFile.path
        )
        // If the optimized file is greater number of bytes than the original file, it
        // is reassigned to the original file.
        if (outputFile.length() >= it.length()) {
            FileUtils.copyFile(inputFile, outputFile)
        }
    }
}

internal fun invokeAapt(aapt2: File, vararg args: String): List<String> {
    val processOutputHeader = CachedProcessOutputHandler()
    val processInfoBuilder = ProcessInfoBuilder()
            .setExecutable(aapt2)
            .addArgs(args)
    val processExecutor = DefaultProcessExecutor(StdLogger(StdLogger.Level.ERROR))
    processExecutor
            .execute(processInfoBuilder.createProcess(), processOutputHeader)
            .rethrowFailure()
    val output: BaseProcessOutputHandler.BaseProcessOutput = processOutputHeader.processOutput
    val lineCollector = LineCollector()
    output.processStandardOutputLines(lineCollector)
    return lineCollector.result
}

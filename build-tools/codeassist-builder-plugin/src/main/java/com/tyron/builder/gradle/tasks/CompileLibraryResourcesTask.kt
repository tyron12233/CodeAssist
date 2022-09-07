package com.tyron.builder.gradle.tasks

import com.android.SdkConstants.FD_RES_VALUES
import com.android.ide.common.resources.CompileResourceRequest
import com.android.ide.common.resources.FileStatus
import com.android.utils.FileUtils
import com.tyron.builder.files.SerializableInputChanges
import com.tyron.builder.gradle.internal.aapt.WorkerExecutorResourceCompilationService
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType
import com.tyron.builder.gradle.internal.services.Aapt2Input
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AndroidResourcesTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AndroidResourcesTaskCreationActionImpl
import com.tyron.builder.internal.aapt.v2.Aapt2RenamingConventions
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.tasks.IncrementalTask
import com.tyron.builder.tasks.getChangesInSerializableForm
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES, secondaryTaskCategories = [TaskCategory.COMPILATION])
abstract class CompileLibraryResourcesTask : IncrementalTask() {

    @get:InputFiles
    @get:Incremental
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectories: ConfigurableFileCollection

    @get:Input
    abstract val pseudoLocalesEnabled: Property<Boolean>

    @get:Input
    abstract val crunchPng: Property<Boolean>

    @get:Input
    abstract val excludeValuesFiles: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    @get:Optional
    abstract val partialRDirectory: DirectoryProperty

    @get:Nested
    abstract val aapt2: Aapt2Input

    override fun doTaskAction(inputChanges: InputChanges) {
        workerExecutor.noIsolation()
            .submit(CompileLibraryResourcesAction::class.java) { parameters ->
//                parameters.initializeFromAndroidVariantTask(this)
                parameters.outputDirectory.set(outputDir)
                parameters.aapt2.set(aapt2)
                parameters.incremental.set(inputChanges.isIncremental)
                parameters.incrementalChanges.set(
                    if (inputChanges.isIncremental) {
                        inputChanges.getChangesInSerializableForm(inputDirectories)
                    } else {
                        null
                    }
                )
                parameters.inputDirectories.from(inputDirectories)
                parameters.partialRDirectory.set(partialRDirectory)
                parameters.pseudoLocalize.set(pseudoLocalesEnabled)
                parameters.crunchPng.set(crunchPng)
                parameters.excludeValues.set(excludeValuesFiles)
            }
    }

    protected abstract class CompileLibraryResourcesParams : WorkParameters {
        abstract val outputDirectory: DirectoryProperty

        @get:Nested
        abstract val aapt2: Property<Aapt2Input>
        abstract val incremental: Property<Boolean>
        abstract val incrementalChanges: Property<SerializableInputChanges>
        abstract val inputDirectories: ConfigurableFileCollection
        abstract val partialRDirectory: DirectoryProperty
        abstract val pseudoLocalize: Property<Boolean>
        abstract val crunchPng: Property<Boolean>
        abstract val excludeValues: Property<Boolean>
    }

    protected abstract class CompileLibraryResourcesAction :
        WorkAction<CompileLibraryResourcesParams> {

        @get:Inject
        abstract val workerExecutor: WorkerExecutor

        override fun execute() {

            WorkerExecutorResourceCompilationService(
//                projectPath = parameters.projectPath,
//                taskOwner = parameters.taskOwner.get(),
                workerExecutor = workerExecutor,
//                analyticsService = parameters.analyticsService,
                aapt2Input = parameters.aapt2.get()
            ).use { compilationService ->
                if (parameters.incremental.get()) {
                    handleIncrementalChanges(
                        parameters.incrementalChanges.get(),
                        compilationService
                    )
                } else {
                    handleFullRun(compilationService)
                }
            }
        }

        /**
         * In the non-namespaced case, filter out the values directories,
         * as they have to go through the resources merging pipeline.
         */
        private fun includeDirectory(directory: File): Boolean {
            if (parameters.excludeValues.get()) {
                return !directory.name.startsWith(FD_RES_VALUES)
            }
            return true
        }

        private fun handleFullRun(processor: WorkerExecutorResourceCompilationService) {
            FileUtils.deleteDirectoryContents(parameters.outputDirectory.asFile.get())

            for (inputDirectory in parameters.inputDirectories) {
                if (!inputDirectory.isDirectory) {
                    continue
                }
                inputDirectory.listFiles()!!
                    .filter { it.isDirectory && includeDirectory(it) }
                    .forEach { dir ->
                        dir.listFiles()!!.forEach { file ->
                            submitFileToBeCompiled(file, processor)
                        }
                    }
            }
        }

        /**
         *  Given an input resource file return the partial R file to generate,
         *  or null if partial R generation is not enabled
         */
        private fun computePartialR(file: File): File? {
            val partialRDirectory = parameters.partialRDirectory.orNull?.asFile ?: return null
            val compiledName = Aapt2RenamingConventions.compilationRename(file)
            return partialRDirectory.resolve(partialRDirectory.resolve("$compiledName-R.txt"))
        }

        private fun submitFileToBeCompiled(
            file: File,
            compilationService: WorkerExecutorResourceCompilationService
        ) {
            val request = CompileResourceRequest(
                file,
                parameters.outputDirectory.asFile.get(),
                partialRFile = computePartialR(file),
                isPseudoLocalize = parameters.pseudoLocalize.get(),
                isPngCrunching = parameters.crunchPng.get()
            )
            compilationService.submitCompile(request)
        }

        private fun handleModifiedFile(
            file: File,
            changeType: FileStatus,
            compilationService: WorkerExecutorResourceCompilationService
        ) {
            if (changeType == FileStatus.CHANGED || changeType == FileStatus.REMOVED) {
                FileUtils.deleteIfExists(
                    File(
                        parameters.outputDirectory.asFile.get(),
                        Aapt2RenamingConventions.compilationRename(file)
                    )
                )
                computePartialR(file)?.let { partialRFile -> FileUtils.delete(partialRFile) }

            }
            if (changeType == FileStatus.NEW || changeType == FileStatus.CHANGED) {
                submitFileToBeCompiled(file, compilationService)
            }
        }

        private fun handleIncrementalChanges(
            fileChanges: SerializableInputChanges,
            compilationService: WorkerExecutorResourceCompilationService
        ) {
            fileChanges.changes.filter { includeDirectory(it.file.parentFile) }
                .forEach { fileChange ->
                    handleModifiedFile(
                        fileChange.file,
                        fileChange.fileStatus,
                        compilationService
                    )
                }
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<CompileLibraryResourcesTask, ComponentCreationConfig>(
        creationConfig
    ), AndroidResourcesTaskCreationAction by AndroidResourcesTaskCreationActionImpl(
        creationConfig
    ) {
        override val name: String
            get() = computeTaskName("compile", "LibraryResources")
        override val type: Class<CompileLibraryResourcesTask>
            get() = CompileLibraryResourcesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<CompileLibraryResourcesTask>
        ) {
            super.handleProvider(taskProvider)

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                CompileLibraryResourcesTask::outputDir
            ).withName(creationConfig.getArtifactName("out"))
             .on(InternalArtifactType.COMPILED_LOCAL_RESOURCES)
        }

        override fun configure(
            task: CompileLibraryResourcesTask
        ) {
            super.configure(task)
            val packagedRes = creationConfig.artifacts.get(InternalArtifactType.PACKAGED_RES)
            task.inputDirectories.setFrom(
                creationConfig.services.fileCollection(packagedRes)
            )
            task.pseudoLocalesEnabled.setDisallowChanges(
                creationConfig.androidResourcesCreationConfig!!.pseudoLocalesEnabled
            )

            task.crunchPng.setDisallowChanges(androidResourcesCreationConfig.isCrunchPngs)
            task.excludeValuesFiles.setDisallowChanges(true)
            creationConfig.services.initializeAapt2Input(task.aapt2)
            task.partialRDirectory.disallowChanges()
        }
    }


    class NamespacedCreationAction(
        override val name: String,
        private val inputDirectories: FileCollection,
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<CompileLibraryResourcesTask, ComponentCreationConfig>(
        creationConfig
    ), AndroidResourcesTaskCreationAction by AndroidResourcesTaskCreationActionImpl(
        creationConfig
    ) {

        override val type: Class<CompileLibraryResourcesTask>
            get() = CompileLibraryResourcesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<CompileLibraryResourcesTask>
        ) {
            super.handleProvider(taskProvider)

            creationConfig.artifacts.use(taskProvider)
                .wiredWith(CompileLibraryResourcesTask::partialRDirectory)
                .toAppendTo(InternalMultipleArtifactType.PARTIAL_R_FILES)

            creationConfig.artifacts.use(taskProvider)
                .wiredWith(CompileLibraryResourcesTask::outputDir)
                .toAppendTo(InternalMultipleArtifactType.RES_COMPILED_FLAT_FILES)
        }

        override fun configure(
            task: CompileLibraryResourcesTask
        ) {
            super.configure(task)
            task.inputDirectories.from(inputDirectories)
            task.crunchPng.setDisallowChanges(androidResourcesCreationConfig.isCrunchPngs)
            task.pseudoLocalesEnabled.setDisallowChanges(
                androidResourcesCreationConfig.pseudoLocalesEnabled
            )
            task.excludeValuesFiles.set(false)
            task.dependsOn(creationConfig.taskContainer.resourceGenTask)

            creationConfig.services.initializeAapt2Input(task.aapt2)
        }
    }
}

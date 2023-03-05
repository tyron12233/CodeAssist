package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants
import com.tyron.builder.gradle.ProguardFiles
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.utils.getFilteredConfigurationFiles
import com.tyron.builder.gradle.internal.utils.immutableMapBuilder
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.errors.EvalIssueException
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.android.utils.FileUtils
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.util.function.Consumer

/**
 * Consolidates Proguard files into a single location for use in dependent modules
 *
 *
 * Caching disabled by default for this task because the task does very little work.
 * Some verification logic is executed and some files are copied to new locations.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.OPTIMIZATION)
abstract class ExportConsumerProguardFilesTask : NonIncrementalTask() {

    @get:Input
    var isBaseModule: Boolean = false
        private set

    @get:Input
    var isDynamicFeature: Boolean = false
        private set

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerProguardFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val ignoredKeepRules: SetProperty<String>

    @get:Input
    @get:Optional
    abstract val ignoreAllKeepRules: Property<Boolean>

    @get:Internal
    lateinit var libraryKeepRules: ArtifactCollection
        private set

    @get:Internal("only for task execution")
    abstract val buildDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    public override fun doTaskAction() {
        // We check for default files unless it's a base feature, which can include default files.
        if (!isBaseModule) {
            checkProguardFiles(
                buildDirectory,
                isDynamicFeature,
                consumerProguardFiles.files,
                Consumer { exception -> throw EvalIssueException(exception) }
            )
        }

        val filteredProguardFiles = if (isDynamicFeature) {
            getFilteredConfigurationFiles(
                    ignoredKeepRules.get(),
                    ignoreAllKeepRules.get(),
                    libraryKeepRules,
                    inputFiles,
                    LoggerWrapper.getLogger(ExportConsumerProguardFilesTask::class.java)
            )
        } else {
            inputFiles
        }

        workerExecutor.noIsolation().submit(ExportConsumerProguardRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.input.from(filteredProguardFiles)
            it.outputDir.set(outputDir)
        }
    }

    class CreationAction(creationConfig: VariantCreationConfig) :
        VariantTaskCreationAction<ExportConsumerProguardFilesTask, VariantCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("export", "ConsumerProguardFiles")

        override val type: Class<ExportConsumerProguardFilesTask>
            get() = ExportConsumerProguardFilesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<ExportConsumerProguardFilesTask>
        ) {
            super.handleProvider(taskProvider)

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ExportConsumerProguardFilesTask::outputDir
            ).on(InternalArtifactType.CONSUMER_PROGUARD_DIR)
        }

        override fun configure(
            task: ExportConsumerProguardFilesTask
        ) {
            super.configure(task)

            task.consumerProguardFiles.from(creationConfig.consumerProguardFiles)
            task.isBaseModule = creationConfig.componentType.isBaseModule
            task.isDynamicFeature = creationConfig.componentType.isDynamicFeature

            task.inputFiles.from(
                task.consumerProguardFiles,
                creationConfig
                    .artifacts
                    .get(InternalArtifactType.GENERATED_PROGUARD_FILE)
            )
            if (creationConfig.componentType.isDynamicFeature) {
                task.libraryKeepRules = creationConfig.variantDependencies.getArtifactCollection(
                        AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                        AndroidArtifacts.ArtifactScope.ALL,
                        AndroidArtifacts.ArtifactType.UNFILTERED_PROGUARD_RULES
                )
                task.inputFiles.from(task.libraryKeepRules.artifactFiles)

                task.ignoredKeepRules.setDisallowChanges(creationConfig.ignoredLibraryKeepRules)
                task.ignoreAllKeepRules.setDisallowChanges(creationConfig.ignoreAllLibraryKeepRules)
            }
            task.buildDirectory.setDisallowChanges(task.project.layout.buildDirectory)
        }
    }

    companion object {
        @JvmStatic
        fun checkProguardFiles(
            buildDirectory: DirectoryProperty,
            isDynamicFeature: Boolean,
            consumerProguardFiles: Collection<File>,
            exceptionHandler: Consumer<String>
        ) {
            val defaultFiles = immutableMapBuilder<File, String> {
                for (knownFileName in ProguardFiles.KNOWN_FILE_NAMES) {
                    this.put(
                        ProguardFiles.getDefaultProguardFile(knownFileName, buildDirectory),
                        knownFileName
                    )
                }
            }

            for (consumerProguardFile in consumerProguardFiles) {
                if (defaultFiles.containsKey(consumerProguardFile)) {
                    val errorMessage = if (isDynamicFeature) {
                        ("Default file "
                                + defaultFiles[consumerProguardFile]
                                + " should not be specified in this module."
                                + " It can be specified in the base module instead.")

                    } else {
                        ("Default file "
                                + defaultFiles[consumerProguardFile]
                                + " should not be used as a consumer configuration file.")
                    }

                    exceptionHandler.accept(errorMessage)
                }
            }
        }
    }
}

abstract class ExportConsumerProguardRunnable :
    WorkAction<ExportConsumerProguardRunnable.Params> {
    override fun execute() {
        FileUtils.deleteRecursivelyIfExists(parameters.outputDir.asFile.get())
        var counter = 0
        parameters.input.forEach { input ->
            if (input.isFile) {
                val libSubDir = getLibSubDir(counter++)
                input.copyTo(File(libSubDir, SdkConstants.FN_PROGUARD_TXT))
            } else if (input.isDirectory) {
                input.listFiles { it -> it.isDirectory }?.forEach {
                    val libSubDir = getLibSubDir(counter++)
                    it.copyRecursively(libSubDir)
                }
            }
        }
    }

    private fun getLibSubDir(count: Int) =
        File(parameters.outputDir.asFile.get(), "lib$count").also { it.mkdirs() }

    abstract class Params : WorkParameters {
        abstract val input: ConfigurableFileCollection
        abstract val outputDir: DirectoryProperty
    }
}
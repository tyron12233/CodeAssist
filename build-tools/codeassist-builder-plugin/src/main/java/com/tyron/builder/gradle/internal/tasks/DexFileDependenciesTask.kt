package com.tyron.builder.gradle.internal.tasks

import com.android.sdklib.AndroidVersion
import com.google.common.io.Closer
import com.tyron.builder.api.variant.impl.getFeatureLevel
import com.tyron.builder.dexing.ClassFileInputs
import com.tyron.builder.dexing.DexArchiveBuilder
import com.tyron.builder.dexing.DexParameters
import com.tyron.builder.dexing.r8.ClassFileProviderFactory
import com.tyron.builder.gradle.errors.MessageReceiverImpl
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.utils.getDesugarLibConfig
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.plugin.options.SyncOptions
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File

@CacheableTask
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.DEXING)
abstract class DexFileDependenciesTask: NonIncrementalTask() {

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Optional
    @get:OutputFile
    abstract val outputKeepRules: DirectoryProperty

    @get:Classpath
    abstract val classes: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val classpath: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val bootClasspath: ConfigurableFileCollection

    @get:Input
    abstract val minSdkVersion: Property<Int>

    @get:Input
    abstract val debuggable: Property<Boolean>

    @get:Optional
    @get:Input
    abstract val libConfiguration: Property<String>

    private lateinit var errorFormatMode: SyncOptions.ErrorFormatMode

    // TODO: make incremental
    override fun doTaskAction() {
        val inputs = classes.files.toList()
        val totalClasspath = inputs + classpath.files

        inputs.forEachIndexed { index, input ->
            // Desugar each jar with reference to all the others
            workerExecutor.noIsolation().submit(DexFileDependenciesWorkerAction::class.java) {
//                it.initializeFromAndroidVariantTask(this)
                it.minSdkVersion.set(minSdkVersion)
                it.debuggable.set(debuggable)
                it.bootClasspath.from(bootClasspath)
                it.classpath.from(totalClasspath)
                it.input.set(input)
                it.outputFile.set(outputDirectory.dir("${index}_${input.name}"))
                it.errorFormatMode.set(errorFormatMode)
                it.libConfiguration.set(libConfiguration)
                it.outputKeepRules.set(outputKeepRules.dir("${index}_${input.name}"))
            }
        }
    }

    abstract class WorkerActionParams: WorkParameters {
        abstract val minSdkVersion: Property<Int>
        abstract val debuggable: Property<Boolean>
        abstract val bootClasspath: ConfigurableFileCollection
        abstract val classpath: ConfigurableFileCollection
        abstract val input: Property<File>
        abstract val outputFile: DirectoryProperty
        abstract val errorFormatMode: Property<SyncOptions.ErrorFormatMode>
        abstract val libConfiguration: Property<String>
        abstract val outputKeepRules: DirectoryProperty
    }

    abstract class DexFileDependenciesWorkerAction : WorkAction<WorkerActionParams> {

        override fun execute() {
            val bootClasspath = parameters.bootClasspath.map(File::toPath)
            val classpath = parameters.classpath.map(File::toPath)
            Closer.create().use { closer ->
                val d8DexBuilder = DexArchiveBuilder.createD8DexBuilder(
                    DexParameters(
                        minSdkVersion = parameters.minSdkVersion.get(),
                        debuggable = parameters.debuggable.get(),
                        dexPerClass = false,
                        withDesugaring = true,
                        desugarBootclasspath = ClassFileProviderFactory(bootClasspath).also {
                            closer.register(it)
                        },
                        desugarClasspath = ClassFileProviderFactory(classpath).also {
                            closer.register(it)
                        },
                        coreLibDesugarConfig = parameters.libConfiguration.orNull,
                        coreLibDesugarOutputKeepRuleFile = parameters.outputKeepRules.asFile.orNull,
                        messageReceiver = MessageReceiverImpl(
                            errorFormatMode = parameters.errorFormatMode.get(),
                            logger = Logging.getLogger(DexFileDependenciesWorkerAction::class.java)
                        )
                    )
                )


                ClassFileInputs.fromPath(parameters.input.get().toPath()).use { classFileInput ->
                    classFileInput.entries { _, _ -> true }.use { classesInput ->
                        d8DexBuilder.convert(
                            classesInput,
                            parameters.outputFile.asFile.get().toPath()
                        )
                    }
                }
            }
        }
    }

    class CreationAction(creationConfig: ConsumableCreationConfig) :
        VariantTaskCreationAction<DexFileDependenciesTask, ConsumableCreationConfig>(
            creationConfig
        ) {
        override val name: String = computeTaskName("desugar", "FileDependencies")
        override val type = DexFileDependenciesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<DexFileDependenciesTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DexFileDependenciesTask::outputDirectory
            ).on(InternalArtifactType.EXTERNAL_FILE_LIB_DEX_ARCHIVES)

            if (creationConfig.needsShrinkDesugarLibrary) {
                creationConfig.artifacts
                    .setInitialProvider(taskProvider, DexFileDependenciesTask::outputKeepRules)
                    .on(InternalArtifactType.DESUGAR_LIB_EXTERNAL_FILE_LIB_KEEP_RULES)
            }
        }

        override fun configure(
            task: DexFileDependenciesTask
        ) {
            super.configure(task)
            task.debuggable
                .setDisallowChanges(creationConfig.debuggable)
            task.classes.from(
                creationConfig.variantDependencies.getArtifactFileCollection(
                    AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                    AndroidArtifacts.ArtifactScope.FILE,
                    AndroidArtifacts.ArtifactType.PROCESSED_JAR
                )
            ).disallowChanges()
            val minSdkVersionForDexing = creationConfig.minSdkVersionForDexing.getFeatureLevel()
            task.minSdkVersion.setDisallowChanges(minSdkVersionForDexing)

            // If min sdk version for dexing is >= N(24) then we can avoid adding extra classes to
            // the desugar classpaths.
            if (minSdkVersionForDexing < AndroidVersion.VersionCodes.N) {
                task.classpath.from(
                    creationConfig.variantDependencies.getArtifactFileCollection(
                        AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                        AndroidArtifacts.ArtifactScope.REPOSITORY_MODULE,
                        AndroidArtifacts.ArtifactType.PROCESSED_JAR
                    )
                )
                task.bootClasspath.from(creationConfig.global.bootClasspath)
            }

            task.errorFormatMode =
                SyncOptions.getErrorFormatMode(creationConfig.services.projectOptions)

            if (creationConfig.isCoreLibraryDesugaringEnabled) {
                task.libConfiguration.set(getDesugarLibConfig(creationConfig.services))
                task.bootClasspath.from(creationConfig.global.bootClasspath)
            }

            task.classpath.disallowChanges()
            task.bootClasspath.disallowChanges()
            task.libConfiguration.disallowChanges()
        }
    }
}

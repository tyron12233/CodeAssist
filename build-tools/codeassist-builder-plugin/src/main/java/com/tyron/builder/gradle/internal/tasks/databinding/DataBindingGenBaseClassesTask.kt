package com.tyron.builder.gradle.internal.tasks.databinding

import android.databinding.tool.BaseDataBinder
import android.databinding.tool.DataBindingBuilder
import android.databinding.tool.processing.ScopedException
import android.databinding.tool.store.LayoutInfoInput
import android.databinding.tool.util.L
import com.android.ide.common.symbols.EMPTY
import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.android.resources.ResourceType
import com.android.utils.FileUtils
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.services.SymbolTableBuildService
import com.tyron.builder.gradle.internal.services.getBuildService
import com.tyron.builder.gradle.internal.tasks.AndroidVariantTask
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.internal.utils.fromDisallowChanges
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import java.io.File
import java.io.Serializable
import javax.inject.Inject
import javax.tools.Diagnostic

/**
 * Generates base classes from data binding info files.
 *
 * This class takes the output of XML processor which generates binding info files (binding
 * information in layout files). Then it generates base classes which are the classes accessed
 * by the user code.
 *
 * Generating these classes in gradle instead of annotation processor avoids showing too many
 * errors to the user if the compilation fails before annotation processor output classes are
 * compiled.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.DATA_BINDING, secondaryTaskCategories = [TaskCategory.SOURCE_GENERATION])
abstract class DataBindingGenBaseClassesTask : AndroidVariantTask() {
    // where xml info files are
    @get:InputFiles
    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val layoutInfoDirectory: DirectoryProperty

    // the package name for the module / app
    @get:Input
    abstract val namespace: Property<String>

    // list of artifacts from dependencies
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyClassLogDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:Optional
    @get:Classpath
    abstract val dependenciesFileCollection: ConfigurableFileCollection

    // Package-aware R.txt file for the given module. Instead of actual package it will contain the
    // keyword "local". Additionally, first line is a comment. For generating references to this
    // local R the default package of this module
    // should be used.
    // See [com.android.ide.common.symbols.SymbolIo] for read/write instructions.
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localResourcesFile: RegularFileProperty

    @get:Internal
    abstract val symbolTableBuildService: Property<SymbolTableBuildService>

    // list of v1 artifacts from dependencies
    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val v1Artifacts: DirectoryProperty

    // where to keep the log of the task
    @get:OutputDirectory lateinit var logOutFolder: File
        private set
    // where to write the new files
    @get:OutputDirectory abstract val sourceOutFolder: DirectoryProperty
    @get:OutputDirectory abstract val classInfoBundleDir: DirectoryProperty

    @get:Input
    var useAndroidX: Boolean = false
        private set
    @get:Internal
    var encodeErrors: Boolean = false
        private set
    @get:Input
    var enableViewBinding: Boolean = false
        private set
    @get:Input
    var enableDataBinding: Boolean = false
        private set

    @get:Input
    var isNonTransitiveR: Boolean = false
        private set

    @TaskAction
    fun writeBaseClasses(inputChanges: InputChanges) {
        // TODO extend NewIncrementalTask when moved to new API so that we can remove the manual call to recordTaskAction

        // TODO figure out why worker execution makes the task flake.
        // Some files cannot be accessed even though they show up when directory listing is
        // invoked.
        // b/69652332
        val args = buildInputArgs(inputChanges)
        CodeGenerator(
            args,
            sourceOutFolder.get().asFile,
            Logging.getLogger(DataBindingGenBaseClassesTask::class.java),
            encodeErrors,
            collectResources()).run()
    }

    private fun collectResources(): List<SymbolTable>? {
        // Don't read anything if R class is transitive
        if (!isNonTransitiveR) return null

        // TODO: maybe filter by ResourceType.ID
        val depSymbolTables: List<SymbolTable> =
                symbolTableBuildService.get().loadClasspath(dependenciesFileCollection.files)
        val localTable = when (val localR = localResourcesFile.orNull) {
            null -> EMPTY
            else -> SymbolIo.readRDef(localR.asFile.toPath()).rename("")
        }
        return listOf(localTable).plus(depSymbolTables)
    }

    private fun buildInputArgs(inputs: InputChanges): LayoutInfoInput.Args {
        val outOfDate = ArrayList<File>()
        val removed = ArrayList<File>()
        val layoutInfoDir = layoutInfoDirectory.get().asFile

        // if dependency added/removed a file, it is handled by the LayoutInfoInput class
        if (inputs.isIncremental) {
            inputs.getFileChanges(layoutInfoDirectory).forEach { change ->
                if (change.normalizedPath.endsWith(".xml")) {
                    if (change.changeType == ChangeType.REMOVED) {
                        removed.add(change.file)
                    } else {
                        outOfDate.add(change.file)
                    }
                }
            }
        } else {
            FileUtils.cleanOutputDir(logOutFolder)
            FileUtils.cleanOutputDir(sourceOutFolder.get().asFile)
        }
        return LayoutInfoInput.Args(
                outOfDate = outOfDate,
                removed = removed,
                infoFolder = layoutInfoDir,
                dependencyClassesFolder = dependencyClassLogDirectories.files.toList().first(),
                logFolder = logOutFolder,
                incremental = inputs.isIncremental,
                packageName = namespace.get(),
                artifactFolder = classInfoBundleDir.get().asFile,
                v1ArtifactsFolder = v1Artifacts.orNull?.asFile,
                useAndroidX = useAndroidX,
                enableViewBinding = enableViewBinding,
                enableDataBinding = enableDataBinding
        )
    }

    class CreationAction(creationConfig: ComponentCreationConfig) :
        VariantTaskCreationAction<DataBindingGenBaseClassesTask, ComponentCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("dataBindingGenBaseClasses")
        override val type: Class<DataBindingGenBaseClassesTask>
            get() = DataBindingGenBaseClassesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<DataBindingGenBaseClassesTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DataBindingGenBaseClassesTask::classInfoBundleDir
            ).withName("out").on(InternalArtifactType.DATA_BINDING_BASE_CLASS_LOG_ARTIFACT)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DataBindingGenBaseClassesTask::sourceOutFolder
            ).withName("out").on(InternalArtifactType.DATA_BINDING_BASE_CLASS_SOURCE_OUT)
        }

        override fun configure(
            task: DataBindingGenBaseClassesTask
        ) {
            super.configure(task)
            val artifacts = creationConfig.artifacts

            creationConfig.artifacts.setTaskInputToFinalProduct(
                DataBindingCompilerArguments.getLayoutInfoArtifactType(creationConfig),
                task.layoutInfoDirectory)

            task.namespace.setDisallowChanges(creationConfig.namespace)

            task.dependencyClassLogDirectories.fromDisallowChanges(
                    creationConfig.variantDependencies.getArtifactFileCollection(
                        AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                        AndroidArtifacts.ArtifactScope.ALL,
                        AndroidArtifacts.ArtifactType.DATA_BINDING_BASE_CLASS_LOG_ARTIFACT
                    )
            )

            artifacts.setTaskInputToFinalProduct(
                    InternalArtifactType.DATA_BINDING_DEPENDENCY_ARTIFACTS, task.v1Artifacts)
            task.logOutFolder = creationConfig.paths.getIncrementalDir(task.name)
            task.useAndroidX = creationConfig.services.projectOptions[BooleanOption.USE_ANDROID_X]
            // needed to decide whether data binding should encode errors or not
            task.encodeErrors = creationConfig.services
                .projectOptions[BooleanOption.IDE_INVOKED_FROM_IDE]
            task.enableViewBinding = creationConfig.buildFeatures.viewBinding
            task.enableDataBinding = creationConfig.buildFeatures.dataBinding

            task.isNonTransitiveR =
                    creationConfig.services.projectOptions[BooleanOption.NON_TRANSITIVE_R_CLASS]

            if (task.isNonTransitiveR) {
                artifacts.setTaskInputToFinalProduct(
                        InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST,
                        task.localResourcesFile
                )

                task.dependenciesFileCollection.fromDisallowChanges(
                        creationConfig
                                .variantDependencies.getArtifactFileCollection(
                                        AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                                        AndroidArtifacts.ArtifactScope.ALL,
                                        AndroidArtifacts.ArtifactType.SYMBOL_LIST_WITH_PACKAGE_NAME
                                )
                )

                task.symbolTableBuildService.set(
                        getBuildService(creationConfig.services.buildServiceRegistry))
            }
        }
    }

    class CodeGenerator @Inject constructor(
        val args: LayoutInfoInput.Args,
        private val sourceOutFolder: File,
        private val logger: Logger,
        private val encodeErrors: Boolean,
        private val symbolTables: List<SymbolTable>? = null
    ) : Runnable, Serializable {
        override fun run() {
            try {
                initLogger()
                BaseDataBinder(
                        LayoutInfoInput(args),
                        if (symbolTables != null) this::getRPackage else null)
                    .generateAll(DataBindingBuilder.GradleFileWriter(sourceOutFolder.absolutePath))
            } finally {
                clearLogger()
            }
        }

        private fun getRPackage(type: String, name: String): String {
            symbolTables!!.forEach {
                if (it.containsSymbol(ResourceType.fromXmlTagName(type)!!, name)) {
                    return it.tablePackage
                }
            }
            error("Cannot find resource: $type $name")
        }

        private fun initLogger() {
            ScopedException.encodeOutput(encodeErrors)
            L.setClient { kind, message, _ ->
                printMessage(kind, message)
            }
        }

        private fun printMessage(kind: Diagnostic.Kind, message: String) {
            when(kind) {
                Diagnostic.Kind.ERROR -> logger.error(message)
                Diagnostic.Kind.WARNING -> logger.warn(message)
                else -> logger.info(message)
            }
        }

        private fun clearLogger() {
            L.setClient(null)
        }
    }
}

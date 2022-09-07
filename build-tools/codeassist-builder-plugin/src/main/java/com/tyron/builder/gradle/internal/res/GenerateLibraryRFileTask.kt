package com.tyron.builder.gradle.internal.res

import com.android.SdkConstants
import com.tyron.builder.api.variant.FilterConfiguration
import com.tyron.builder.api.variant.impl.BuiltArtifactImpl
import com.tyron.builder.api.variant.impl.BuiltArtifactsImpl
import com.tyron.builder.api.variant.impl.BuiltArtifactsLoaderImpl
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.UnitTestCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.ALL
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.services.SymbolTableBuildService
import com.tyron.builder.gradle.internal.services.getBuildService
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.tasks.ProcessAndroidResources
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.android.ide.common.symbols.IdProvider
import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.tyron.builder.internal.utils.fromDisallowChanges
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.symbols.processLibraryMainSymbolTable
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.InputChanges
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import javax.inject.Inject

@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES)
abstract class GenerateLibraryRFileTask : ProcessAndroidResources() {

    @get:OutputFile
    abstract val rClassOutputJar: RegularFileProperty

    @Internal // rClassOutputJar is already marked as @OutputFile
    override fun getSourceOutputDir(): File? = rClassOutputJar.get().asFile

    // used by Butterknife
    @Suppress("unused")
    @Internal
    fun getTextSymbolOutputFile(): File {
        return textSymbolOutputFileProperty.get().asFile
    }

    @get:OutputFile
    @get:Optional
    abstract val textSymbolOutputFileProperty: RegularFileProperty

    @get:OutputFile
    @get:Optional
    abstract val symbolsWithPackageNameOutputFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE) abstract val dependencies: ConfigurableFileCollection

    @get:Input
    abstract val namespace: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val platformAttrRTxt: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localResourcesFile: RegularFileProperty

    @get:Input
    abstract val nonTransitiveRClass: Property<Boolean>

    @get:Input
    abstract val compileClasspathLibraryRClasses: Property<Boolean>

    @get:Input
    abstract val useConstantIds: Property<Boolean>

    @get:Internal
    abstract val symbolTableBuildService: Property<SymbolTableBuildService>

    override fun doTaskAction(inputChanges: InputChanges) {
        val manifestBuiltArtifacts = BuiltArtifactsLoaderImpl().load(manifestFiles)
            ?: throw RuntimeException("Cannot load generated manifests, file a bug")
        val manifest = File(chooseOutput(manifestBuiltArtifacts).outputFile)

        workerExecutor.noIsolation().submit(GenerateLibRFileRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.localResourcesFile.set(localResourcesFile)
            it.manifest.set(manifest)
            it.androidJar.from(platformAttrRTxt)
            it.dependencies.from(dependencies)
            it.namespace.set(namespace.get())
            it.rClassOutputJar.set(rClassOutputJar)
            it.textSymbolOutputFile.set(textSymbolOutputFileProperty)
            it.nonTransitiveRClass.set(nonTransitiveRClass.get())
            it.compileClasspathLibraryRClasses.set(compileClasspathLibraryRClasses.get())
            it.symbolsWithPackageNameOutputFile.set(symbolsWithPackageNameOutputFile)
            it.useConstantIds.set(useConstantIds.get())
            it.symbolTableBuildService.set(symbolTableBuildService)
        }
    }

    private fun chooseOutput(manifestBuiltArtifacts: BuiltArtifactsImpl): BuiltArtifactImpl =
        manifestBuiltArtifacts.elements
            .firstOrNull() { output -> output.getFilter(FilterConfiguration.FilterType.DENSITY) == null }
            ?: throw RuntimeException("No non-density apk found")

    abstract class GenerateLibRFileParams : WorkParameters {
        abstract val localResourcesFile: RegularFileProperty
        abstract val manifest: RegularFileProperty
        abstract val androidJar: ConfigurableFileCollection
        abstract val dependencies: ConfigurableFileCollection
        abstract val namespace: Property<String>
        abstract val rClassOutputJar: RegularFileProperty
        abstract val textSymbolOutputFile: RegularFileProperty
        abstract val nonTransitiveRClass: Property<Boolean>
        abstract val compileClasspathLibraryRClasses: Property<Boolean>
        abstract val symbolsWithPackageNameOutputFile: RegularFileProperty
        abstract val useConstantIds: Property<Boolean>
        abstract val symbolTableBuildService: Property<SymbolTableBuildService>
    }

    abstract class GenerateLibRFileRunnable @Inject constructor() :
        WorkAction<GenerateLibRFileParams> {
        override fun execute() {
            val androidAttrSymbol = getAndroidAttrSymbols()

            val symbolTable = SymbolIo.readRDef(parameters.localResourcesFile.asFile.get().toPath())

            val depSymbolTables: List<SymbolTable> =
                parameters.symbolTableBuildService.get().loadClasspath(parameters.dependencies)

            val idProvider =
                if (parameters.useConstantIds.get()) {
                    IdProvider.constant()
                } else {
                    IdProvider.sequential()
                }
            processLibraryMainSymbolTable(
                librarySymbols = symbolTable,
                depSymbolTables = depSymbolTables,
                mainPackageName = parameters.namespace.get(),
                manifestFile = parameters.manifest.asFile.get(),
                rClassOutputJar = parameters.rClassOutputJar.asFile.orNull,
                symbolFileOut = parameters.textSymbolOutputFile.asFile.orNull,
                platformSymbols = androidAttrSymbol,
                nonTransitiveRClass = parameters.nonTransitiveRClass.get(),
                generateDependencyRClasses = !parameters.compileClasspathLibraryRClasses.get(),
                idProvider = idProvider
            )

            parameters.symbolsWithPackageNameOutputFile.asFile.orNull?.let {
                SymbolIo.writeSymbolListWithPackageName(
                    parameters.textSymbolOutputFile.get().asFile.toPath(),
                    parameters.manifest.get().asFile.toPath(),
                    it.toPath()
                )
            }
        }

        private fun getAndroidAttrSymbols() =
            if (parameters.androidJar.singleFile.exists())
                SymbolIo.readFromAapt(parameters.androidJar.singleFile, "android")
            else
                SymbolTable.builder().tablePackage("android").build()
    }

    internal class CreationAction(
        creationConfig: ComponentCreationConfig,
        val isLibrary: Boolean)
        : VariantTaskCreationAction<GenerateLibraryRFileTask, ComponentCreationConfig>(creationConfig) {

        override val name: String
            get() = computeTaskName("generate", "RFile")
        override val type: Class<GenerateLibraryRFileTask>
            get() = GenerateLibraryRFileTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<GenerateLibraryRFileTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.taskContainer.processAndroidResTask = taskProvider

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                GenerateLibraryRFileTask::rClassOutputJar
            ).withName("R.jar").on(InternalArtifactType.COMPILE_R_CLASS_JAR)

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                GenerateLibraryRFileTask::textSymbolOutputFileProperty
            ).withName(SdkConstants.FN_RESOURCE_TEXT).on(InternalArtifactType.COMPILE_SYMBOL_LIST)

            // Synthetic output for AARs (see SymbolTableWithPackageNameTransform), and created in
            // process resources for local subprojects.
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                GenerateLibraryRFileTask::symbolsWithPackageNameOutputFile
            ).withName("package-aware-r.txt").on(InternalArtifactType.SYMBOL_LIST_WITH_PACKAGE_NAME)
        }

        override fun configure(
            task: GenerateLibraryRFileTask
        ) {
            super.configure(task)

            val projectOptions = creationConfig.services.projectOptions

            task.platformAttrRTxt.fromDisallowChanges(creationConfig.global.platformAttrs)

            val nonTransitiveRClass = projectOptions[BooleanOption.NON_TRANSITIVE_R_CLASS]
            val compileClasspathLibraryRClasses = projectOptions[BooleanOption.COMPILE_CLASSPATH_LIBRARY_R_CLASSES]

            if (!nonTransitiveRClass || !compileClasspathLibraryRClasses) {
                // We need the dependencies for generating our own R class or for generating R
                // classes of the dependencies:
                //   * If we're creating a transitive (non-namespaced) R class, then we need the
                //     dependencies to include them in the local R class.
                //   * If we're using the runtime classpath (not compile classpath) then we need the
                //     dependencies for generating the R classes for each of them.
                //   * If both above are true then we use the dependencies for generating both the
                //     local R class and the dependencies' R classes.
                //   * The only case when we don't need the dependencies is if we are generating a
                //     namespaced (non-transitive) local R class AND we're using the compile
                //     classpath R class flow.
                val consumedConfigType =
                    if (compileClasspathLibraryRClasses) {
                        COMPILE_CLASSPATH
                    } else {
                        RUNTIME_CLASSPATH
                    }
                task.dependencies.from(
                    creationConfig.variantDependencies.getArtifactFileCollection(
                    consumedConfigType,
                    ALL,
                    AndroidArtifacts.ArtifactType.SYMBOL_LIST_WITH_PACKAGE_NAME
                ))
            }

            task.nonTransitiveRClass.set(nonTransitiveRClass)
            task.compileClasspathLibraryRClasses.setDisallowChanges(compileClasspathLibraryRClasses)
            task.namespace.setDisallowChanges(creationConfig.namespace)

            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.PACKAGED_MANIFESTS, task.manifestFiles)

            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.MERGED_MANIFESTS, task.mergedManifestFiles)

            task.mainSplit = creationConfig.outputs.getMainSplit()

            // This task can produce R classes with either constant IDs ("0") or sequential IDs
            // mimicking the way AAPT2 numbers IDs. If we're generating a compile time only R class
            // (either for the small merge in app or when using compile classpath resources in libs)
            // we want to use the constant IDs; otherwise, we will use sequential IDs.
            // In either case, the IDs are fake, and therefore are non-final.
            task.useConstantIds.set(
                (projectOptions[BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS] && !isLibrary)
                        || projectOptions[BooleanOption.COMPILE_CLASSPATH_LIBRARY_R_CLASSES])
            task.symbolTableBuildService.setDisallowChanges(getBuildService(creationConfig.services.buildServiceRegistry))

            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST,
                task.localResourcesFile)
        }
    }

    internal class TestRuntimeStubRClassCreationAction(creationConfig: UnitTestCreationConfig) :
        VariantTaskCreationAction<GenerateLibraryRFileTask, UnitTestCreationConfig>(
            creationConfig
        ) {

        override val name: String = computeTaskName("generate", "StubRFile")
        override val type: Class<GenerateLibraryRFileTask> = GenerateLibraryRFileTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<GenerateLibraryRFileTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                GenerateLibraryRFileTask::rClassOutputJar
            ).withName("R.jar").on(InternalArtifactType.COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR)
        }

        override fun configure(
            task: GenerateLibraryRFileTask
        ) {
            super.configure(task)
            val projectOptions = creationConfig.services.projectOptions

            task.platformAttrRTxt.fromDisallowChanges(creationConfig.global.platformAttrs)

            // We need the runtime dependencies for generating a set of consistent runtime R classes
            // for android test, and in the case of transitive R classes, we also need them
            // to include them in the local R class.
            task.dependencies.fromDisallowChanges(
                    creationConfig.variantDependencies.getArtifactFileCollection(
                        RUNTIME_CLASSPATH,
                        ALL,
                        AndroidArtifacts.ArtifactType.SYMBOL_LIST_WITH_PACKAGE_NAME
                    )
                )

            task.nonTransitiveRClass.setDisallowChanges(projectOptions[BooleanOption.NON_TRANSITIVE_R_CLASS])
            task.compileClasspathLibraryRClasses.setDisallowChanges(false)
            task.namespace.setDisallowChanges(creationConfig.namespace)
            task.mainSplit = creationConfig.outputs.getMainSplit()
            task.useConstantIds.setDisallowChanges(false)
            task.symbolTableBuildService.setDisallowChanges(getBuildService(creationConfig.services.buildServiceRegistry))

            creationConfig.onTestedVariant {
                it.artifacts.setTaskInputToFinalProduct(
                    InternalArtifactType.PACKAGED_MANIFESTS, task.manifestFiles
                )

                it.artifacts.setTaskInputToFinalProduct(
                    InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST,
                    task.localResourcesFile
                )
            }
        }
    }
}

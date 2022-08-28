package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.dependency.BaseDexingTransform
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.internal.dexing.DexParameters
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.plugin.SdkConstants
import com.tyron.builder.plugin.options.SyncOptions
import com.tyron.builder.tasks.IncrementalTask
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.util.internal.GFileUtils
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Task that converts CLASS files into dex archives.
 */
@CacheableTask
abstract class DexArchiveBuilderTask : IncrementalTask() {

    @get:Incremental
    @get:Classpath
    abstract val projectClasses: ConfigurableFileCollection

    @get:Incremental
    @get:Classpath
    abstract val subProjectClasses: ConfigurableFileCollection

    @get:Incremental
    @get:Classpath
    abstract val externalLibClasses: ConfigurableFileCollection

    /**
     * These are classes that contain multiple transform API scopes. E.g. if there is a transform
     * running before this task that outputs classes with both project and subProject scopes, this
     * input will contain them.
     */
    @get:Incremental
    @get:Classpath
    abstract val mixedScopeClasses: ConfigurableFileCollection

    @get:Nested
    abstract val projectOutputs: DexingOutputs

    @get:Nested
    abstract val subProjectOutputs: DexingOutputs

    @get:Nested
    abstract val externalLibsOutputs: DexingOutputs

    @get:Nested
    abstract val externalLibsFromArtifactTransformsOutputs: DexingOutputs

    @get:Nested
    abstract val mixedScopeOutputs: DexingOutputs

    @get:Nested
    abstract val dexParams: DexParameterInputs

    @get:LocalState
    @get:Optional
    abstract val desugarGraphDir: DirectoryProperty

    @get:Input
    abstract val projectVariant: Property<String>

    @get:LocalState
    abstract val inputJarHashesFile: RegularFileProperty

    /**
     * This property is annotated with [Internal] in order to allow cache hits across build that use
     * different number of buckets. Changing this property will not re-run the task, but that is
     * fine. See [canRunIncrementally] for details how this impacts incremental builds.
     */
    @get:Internal
    abstract val numberOfBuckets: Property<Int>

    @get:LocalState
    abstract val previousRunNumberOfBucketsFile: RegularFileProperty

    @get:Incremental
    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFiles
    abstract val externalLibDexFiles: ConfigurableFileCollection

    /**
     * Task runs incrementally if input changes allow that and if the number of buckets is the same
     * as in the previous run. This is necessary in order to have correct incremental builds as
     * the output location for an input file is computed using the number of buckets.
     *
     * The following scenarios are handled:
     * - changing the number of buckets between runs will cause non-incremental run
     * - cache hit will not restore [previousRunNumberOfBucketsFile], and it will cause next run to
     * be non-incremental
     * - build in which the number of buckets is the same as in the [previousRunNumberOfBucketsFile]
     * can be incremental
     */
    private fun canRunIncrementally(inputChanges: InputChanges): Boolean {
        val canRunIncrementally =
            if (!inputChanges.isIncremental) false
            else {
                with(previousRunNumberOfBucketsFile.asFile.get()) {
                    if (!isFile) false
                    else readText() == numberOfBuckets.get().toString()
                }
            }

        if (!canRunIncrementally) {
            // If incremental run is not possible write the current number of buckets
            with(previousRunNumberOfBucketsFile.get().asFile) {
                GFileUtils.mkdirs(parentFile)
                writeText(numberOfBuckets.get().toString())
            }
        }
        return canRunIncrementally
    }

    override fun doTaskAction(inputChanges: InputChanges) {
        val isIncremental = canRunIncrementally(inputChanges)

        if ((!externalLibDexFiles.isEmpty && !isIncremental) || getChanged(
                isIncremental,
                inputChanges,
                externalLibDexFiles
            ).isNotEmpty()
        ) {
            // If non-incremental run (with files), or any of the dex files changed, copy them again.
            workerExecutor.noIsolation().submit(CopyDexOutput::class.java) {
//                it.initializeFromAndroidVariantTask(this)
                it.inputDirs.from(externalLibDexFiles.files)
                it.outputDexDir.set(externalLibsFromArtifactTransformsOutputs.dex)
                it.outputKeepRules.set(externalLibsFromArtifactTransformsOutputs.keepRules)
            }
        }

        DexArchiveBuilderTaskDelegate(
            isIncremental = isIncremental,

            projectClasses = projectClasses.files,
            projectChangedClasses = getChanged(isIncremental, inputChanges, projectClasses),
            subProjectClasses = subProjectClasses.files,
            subProjectChangedClasses = getChanged(isIncremental, inputChanges, subProjectClasses),
            externalLibClasses = externalLibClasses.files,
            externalLibChangedClasses = getChanged(isIncremental, inputChanges, externalLibClasses),
            mixedScopeClasses = mixedScopeClasses.files,
            mixedScopeChangedClasses = getChanged(isIncremental, inputChanges, mixedScopeClasses),

            projectOutputs = DexArchiveBuilderTaskDelegate.DexingOutputs(projectOutputs),
            subProjectOutputs = DexArchiveBuilderTaskDelegate.DexingOutputs(subProjectOutputs),
            externalLibsOutputs = DexArchiveBuilderTaskDelegate.DexingOutputs(externalLibsOutputs),
            mixedScopeOutputs = DexArchiveBuilderTaskDelegate.DexingOutputs(mixedScopeOutputs),

            dexParams = dexParams.toDexParameters(),

            desugarClasspathChangedClasses = getChanged(
                isIncremental,
                inputChanges,
                dexParams.desugarClasspath
            ),
            desugarGraphDir = desugarGraphDir.get().asFile.takeIf { dexParams.withDesugaring.get() },

            inputJarHashesFile = inputJarHashesFile.get().asFile,
            numberOfBuckets = numberOfBuckets.get(),
            workerExecutor = workerExecutor,
            projectPath = project.provider { project.projectPath.toString() },
            taskPath = path,
        ).doProcess()
    }

    class CreationAction(
        creationConfig: ApkCreationConfig,
    ) : VariantTaskCreationAction<DexArchiveBuilderTask, ApkCreationConfig>(
        creationConfig
    ) {

        override val name = "dexBuilderDebug"

        override val type: Class<DexArchiveBuilderTask> = DexArchiveBuilderTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<DexArchiveBuilderTask>) {
            super.handleProvider(taskProvider)

            creationConfig.artifacts.setInitialProvider(
                taskProvider
            ) { it.projectOutputs.dex }
                .withName("out").withName("out").on(InternalArtifactType.PROJECT_DEX_ARCHIVE)
            creationConfig.artifacts.setInitialProvider(
                taskProvider
            ) { it.subProjectOutputs.dex }
                .withName("out").withName("out").on(InternalArtifactType.SUB_PROJECT_DEX_ARCHIVE)
            creationConfig.artifacts.setInitialProvider(
                taskProvider
            ) { it.externalLibsOutputs.dex }
                .withName("out").on(InternalArtifactType.EXTERNAL_LIBS_DEX_ARCHIVE)
            creationConfig.artifacts.setInitialProvider(
                taskProvider
            ) { it.externalLibsFromArtifactTransformsOutputs.dex }
                .withName("out")
                .on(InternalArtifactType.EXTERNAL_LIBS_DEX_ARCHIVE_WITH_ARTIFACT_TRANSFORMS)
            creationConfig.artifacts.setInitialProvider(
                taskProvider
            ) { it.mixedScopeOutputs.dex }
                .withName("out").on(InternalArtifactType.MIXED_SCOPE_DEX_ARCHIVE)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DexArchiveBuilderTask::inputJarHashesFile
            ).withName("out").on(InternalArtifactType.DEX_ARCHIVE_INPUT_JAR_HASHES)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DexArchiveBuilderTask::desugarGraphDir
            ).withName("out").on(InternalArtifactType.DESUGAR_GRAPH)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DexArchiveBuilderTask::previousRunNumberOfBucketsFile
            ).withName("out").on(InternalArtifactType.DEX_NUMBER_OF_BUCKETS_FILE)
        }

        override fun configure(task: DexArchiveBuilderTask) {
            super.configure(task)

            task.projectClasses.setFrom(
                // TODO: proper path
                task.project.buildDir.resolve("intermediates/javac/debug/classes")
            )
            task.projectVariant.set("debug")

            task.externalLibClasses
                .from(getDexForExternalLibs(task, "jar"))
            task.externalLibClasses
                .from(getDexForExternalLibs(task, "dir"))

            task.numberOfBuckets.set(
                task.project.providers.provider{DEFAULT_NUM_BUCKETS}
            )
            task.dexParams.debuggable.set(true)
            task.dexParams.errorFormatMode
                .set(SyncOptions.ErrorFormatMode.HUMAN_READABLE)
            task.dexParams.minSdkVersion.set(creationConfig.minSdkVersion.apiLevel)
            task.dexParams.withDesugaring.set(creationConfig.isCoreLibraryDesugaringEnabled)
        }

        private fun getDexForExternalLibs(
            task: DexArchiveBuilderTask,
            inputType: String
        ): FileCollection? {

            val runtimeClasspath = task.project.configurations
                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
            task.project.dependencies.registerTransform(
                DexingExternalLibArtifactTransform::class.java
            ) { spec ->
                spec.parameters {
                    it.projectName.set(task.project.name)
                    it.minSdkVersion.set(task.dexParams.minSdkVersion)
                    it.debuggable.set(task.dexParams.debuggable)
                    it.bootClasspath.from(task.dexParams.desugarClasspath)
                    it.desugaringClasspath.from()
                    it.enableDesugaring.set(task.dexParams.withDesugaring)
                    it.libConfiguration.set(task.dexParams.coreLibDesugarConfig)
                    it.errorFormat.set(task.dexParams.errorFormatMode)
                }

                // Until Gradle provides a better way to run artifact transforms for arbitrary
                // configuration, use "artifactType" attribute as that one is always present.
                spec.from.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    inputType
                )
                // Make this attribute unique by using task name. This ensures that every task will
                // have a unique transform to run which is required as input parameters are
                // task-specific.
                spec.to.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    "ext-dex-$name"
                )
            }
            val externalLibraryDependencies = runtimeClasspath.allDependencies.stream()
                .filter { it !is ProjectDependency }
                .collect(Collectors.toList())
            val dex = task.project.configurations.detachedConfiguration()
            dex.dependencies.addAll(
                externalLibraryDependencies.stream().map {
                    task.project.dependencies.create(it)
                }.collect(Collectors.toList())
            )
            return dex.incoming.artifactView {
                it.attributes.attribute(AndroidArtifacts.ARTIFACT_TYPE, AndroidArtifacts.ArtifactType.CLASSES_JAR.type)
                it.attributes.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    "ext-dex-$name"
                )
            }.artifacts.artifactFiles
        }
    }


    /** Outputs for dexing (with d8) */
    abstract class DexingOutputs {

        @get:OutputDirectory
        abstract val dex: DirectoryProperty

        /** Core library desugaring keep rules */
        @get:Optional
        @get:OutputDirectory
        abstract val keepRules: DirectoryProperty
    }

    companion object {
        /**
         * Some files will be reported as both added and removed, as order of inputs may shift and we
         * are using @Classpath on inputs. For those, ignore the removed change,
         * and just handle them as added. For non-incremental builds return an empty set as dexing
         * pipeline traverses directories and we'd like to avoid serializing this information to the
         * worker action.
         */
        fun getChanged(
            canRunIncrementally: Boolean,
            inputChanges: InputChanges,
            input: FileCollection
        ): Set<FileChange> {
            if (!canRunIncrementally) {
                return emptySet()
            }
            val fileChanges = mutableMapOf<File, FileChange>()

            inputChanges.getFileChanges(input).forEach { change ->
                val currentValue = fileChanges[change.file]
                if (currentValue == null || (currentValue.changeType == ChangeType.REMOVED && change.changeType == ChangeType.ADDED)) {
                    fileChanges[change.file] = change
                }
            }
            return fileChanges.values.toSet()
        }
    }
}

val DEFAULT_NUM_BUCKETS = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

/** Parameters required for dexing (with D8). */
abstract class DexParameterInputs {

    @get:Input
    abstract val minSdkVersion: Property<Int>

    @get:Input
    abstract val debuggable: Property<Boolean>

    @get:Input
    abstract val withDesugaring: Property<Boolean>

    @get:CompileClasspath
    abstract val desugarBootclasspath: ConfigurableFileCollection

    @get:Incremental
    @get:CompileClasspath
    abstract val desugarClasspath: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val coreLibDesugarConfig: Property<String>

    @get:Input
    abstract val errorFormatMode: Property<SyncOptions.ErrorFormatMode>

    fun toDexParameters():  DexParameters {
        return DexParameters(
            minSdkVersion = minSdkVersion.get(),
            debuggable = debuggable.get(),
            withDesugaring = withDesugaring.get(),
            desugarBootclasspath = desugarBootclasspath.files.toList(),
            desugarClasspath = desugarClasspath.files.toList(),
            coreLibDesugarConfig = coreLibDesugarConfig.orNull,
            errorFormatMode = errorFormatMode.get()
        )
    }
}

/**
 * Ad-hoc artifact transform used to desugar and dex external libraries, that is using Gradle
 * built-in caching. Every external library is desugared against classpath that consists of all
 * external libraries.
 */
@CacheableTransform
abstract class DexingExternalLibArtifactTransform: BaseDexingTransform<DexingExternalLibArtifactTransform.Parameters>() {
    interface Parameters: BaseDexingTransform.Parameters {
        @get:CompileClasspath
        val desugaringClasspath: ConfigurableFileCollection
    }

    override fun computeClasspathFiles(): List<Path> {
        return parameters.desugaringClasspath.files.map(File::toPath)
    }
}

/**
 * Implementation of the worker action that copies dex files and core library desugaring keep rules
 * to the final output locations. Originating files are output of [DexingExternalLibArtifactTransform]
 * transform.
 */
abstract class CopyDexOutput : WorkAction<CopyDexOutput.Params> {
    abstract class Params : WorkParameters {
        abstract val inputDirs: ConfigurableFileCollection
        abstract val outputDexDir: DirectoryProperty
        abstract val outputKeepRules: DirectoryProperty
    }
    override fun execute() {
        GFileUtils.cleanOutputDir(parameters.outputDexDir.asFile.get())
        var dexId = 0
        var keepRulesId = 0
        parameters.inputDirs.files.forEach { inputDir ->
            inputDir.walk().filter { it.extension == SdkConstants.EXT_DEX }.forEach { dexFile ->
                dexFile.copyTo(parameters.outputDexDir.asFile.get().resolve("classes_ext_${dexId++}.dex"))
            }
            parameters.outputKeepRules.asFile.orNull?.let {
                inputDir.resolve("keep_rules").let {rules ->
                    if (rules.isFile) rules.copyTo(it.resolve("core_lib_keep_rules_${keepRulesId++}.txt"))
                }
            }
        }
    }
}
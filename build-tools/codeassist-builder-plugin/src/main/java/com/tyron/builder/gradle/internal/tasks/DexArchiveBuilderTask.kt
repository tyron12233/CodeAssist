package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants
import com.android.sdklib.AndroidVersion
import com.tyron.builder.api.variant.impl.getFeatureLevel
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.dependency.BaseDexingTransform
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.Java8LangSupport
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.utils.getDesugarLibConfig
import com.tyron.builder.gradle.options.IntegerOption
import com.tyron.builder.internal.dexing.DexParameters
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.plugin.options.SyncOptions
import com.tyron.builder.tasks.IncrementalTask
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
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
        classesClasspathUtils: ClassesClasspathUtils,
    ) : VariantTaskCreationAction<DexArchiveBuilderTask, ApkCreationConfig>(
        creationConfig
    ) {

        override val name = creationConfig.computeTaskName("dexBuilder")

        private val projectClasses: FileCollection
        private val subProjectsClasses: FileCollection
        private val externalLibraryClasses: FileCollection
        private val mixedScopeClasses: FileCollection
        private val desugaringClasspathClasses: FileCollection
        // Difference between this property and desugaringClasspathClasses is that for the test
        // variant, this property does not contain tested project code, allowing us to have more
        // cache hits when using artifact transforms.
        private val desugaringClasspathForArtifactTransforms: FileCollection
        private val dexExternalLibsInArtifactTransform: Boolean

        init {
            desugaringClasspathClasses = classesClasspathUtils.desugaringClasspathClasses
            projectClasses = classesClasspathUtils.projectClasses
            dexExternalLibsInArtifactTransform = classesClasspathUtils.dexExternalLibsInArtifactTransform

            subProjectsClasses = classesClasspathUtils.subProjectsClasses
            externalLibraryClasses = classesClasspathUtils.externalLibraryClasses
            mixedScopeClasses = classesClasspathUtils.mixedScopeClasses
            desugaringClasspathForArtifactTransforms = classesClasspathUtils.desugaringClasspathForArtifactTransforms
        }

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
                .withName("out").on(InternalArtifactType.EXTERNAL_LIBS_DEX_ARCHIVE_WITH_ARTIFACT_TRANSFORMS)
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
            if (creationConfig.needsShrinkDesugarLibrary) {
                creationConfig.artifacts.setInitialProvider(
                    taskProvider
                ) { it.projectOutputs.keepRules }
                    .withName("out").on(InternalArtifactType.DESUGAR_LIB_PROJECT_KEEP_RULES)
                creationConfig.artifacts.setInitialProvider(
                    taskProvider
                ) { it.subProjectOutputs.keepRules }
                    .withName("out").on(InternalArtifactType.DESUGAR_LIB_SUBPROJECT_KEEP_RULES)
                creationConfig.artifacts.setInitialProvider(
                    taskProvider
                ) { it.externalLibsOutputs.keepRules }
                    .withName("out").on(InternalArtifactType.DESUGAR_LIB_EXTERNAL_LIBS_KEEP_RULES)
                creationConfig.artifacts.setInitialProvider(
                    taskProvider
                ) { it.externalLibsFromArtifactTransformsOutputs.keepRules }
                    .withName("out").on(InternalArtifactType.DESUGAR_LIB_EXTERNAL_LIBS_ARTIFACT_TRANSFORM_KEEP_RULES)
                creationConfig.artifacts.setInitialProvider(
                    taskProvider
                ) { it.mixedScopeOutputs.keepRules }
                    .withName("out").on(InternalArtifactType.DESUGAR_LIB_MIXED_SCOPE_KEEP_RULES)
            }
        }

        override fun configure(task: DexArchiveBuilderTask) {
            super.configure(task)

            val projectOptions = creationConfig.services.projectOptions

            task.projectClasses.from(projectClasses)
            task.subProjectClasses.from(subProjectsClasses)
            task.mixedScopeClasses.from(mixedScopeClasses)

            val minSdkVersionForDexing = creationConfig.minSdkVersionForDexing.getFeatureLevel()
            task.dexParams.minSdkVersion.set(minSdkVersionForDexing)
            val languageDesugaring =
                creationConfig.getJava8LangSupportType() == Java8LangSupport.D8
            task.dexParams.withDesugaring.set(languageDesugaring)

            // If min sdk version for dexing is >= N(24) then we can avoid adding extra classes to
            // the desugar classpaths.
            val languageDesugaringNeeded = languageDesugaring && minSdkVersionForDexing < AndroidVersion.VersionCodes.N
            if (languageDesugaringNeeded) {
                // Set classpath only if desugaring with D8 and minSdkVersion < 24
                task.dexParams.desugarClasspath.from(desugaringClasspathClasses)
                if (dexExternalLibsInArtifactTransform) {
                    // If dexing external libraries in artifact transforms, make sure to add them
                    // as desugaring classpath.
                    task.dexParams.desugarClasspath.from(externalLibraryClasses)
                }
            }
            // Set bootclasspath only for two cases:
            // 1. language desugaring with D8 and minSdkVersionForDexing < 24
            // 2. library desugaring enabled(required for API conversion)
            val libraryDesugaring = creationConfig.isCoreLibraryDesugaringEnabled
            if (languageDesugaringNeeded || libraryDesugaring) {
                task.dexParams.desugarBootclasspath
                    .from(creationConfig.global.filteredBootClasspath)
            }

            task.dexParams.errorFormatMode.set(SyncOptions.getErrorFormatMode(projectOptions))
            task.dexParams.debuggable.setDisallowChanges(
                creationConfig.debuggable
            )
            task.projectVariant.set(
                "${task.project.name}:${creationConfig.name}"
            )
            task.numberOfBuckets.set(
                task.project.providers.provider {
                    projectOptions.getProvider(IntegerOption.DEXING_NUMBER_OF_BUCKETS).orNull
                        ?: DEFAULT_NUM_BUCKETS
                }
            )
            if (libraryDesugaring) {
                task.dexParams.coreLibDesugarConfig.set(getDesugarLibConfig(creationConfig.services))
            }

            if (dexExternalLibsInArtifactTransform) {
                task.externalLibDexFiles.from(getDexForExternalLibs(task, "jar"))
                task.externalLibDexFiles.from(getDexForExternalLibs(task, "dir"))
            } else {
                task.externalLibClasses.from(externalLibraryClasses)
            }
        }

        /** Creates a detached configuration and sets up artifact transform for dexing. */
        private fun getDexForExternalLibs(task: DexArchiveBuilderTask, inputType: String): FileCollection {
            val services = creationConfig.services

            services.dependencies.registerTransform(
                DexingExternalLibArtifactTransform::class.java
            ) {
                it.parameters.run {
                    this.projectName.set(services.projectInfo.name)
                    this.minSdkVersion.set(task.dexParams.minSdkVersion)
                    this.debuggable.set(task.dexParams.debuggable)
                    this.bootClasspath.from(task.dexParams.desugarBootclasspath)
                    this.desugaringClasspath.from(desugaringClasspathForArtifactTransforms)
                    this.enableDesugaring.set(task.dexParams.withDesugaring)
                    this.libConfiguration.set(task.dexParams.coreLibDesugarConfig)
                    this.errorFormat.set(task.dexParams.errorFormatMode)
                }

                // Until Gradle provides a better way to run artifact transforms for arbitrary
                // configuration, use "artifactType" attribute as that one is always present.
                it.from.attribute(Attribute.of("artifactType", String::class.java), inputType)
                // Make this attribute unique by using task name. This ensures that every task will
                // have a unique transform to run which is required as input parameters are
                // task-specific.
                it.to.attribute(Attribute.of("artifactType", String::class.java), "ext-dex-$name")
            }

            val detachedExtConf = services.configurations.detachedConfiguration()
            detachedExtConf.dependencies.add(services.dependencies.create(externalLibraryClasses))

            return detachedExtConf.incoming.artifactView {
                it.attributes.attribute(
                    Attribute.of("artifactType", String::class.java),
                    "ext-dex-$name"
                )
            }.files
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
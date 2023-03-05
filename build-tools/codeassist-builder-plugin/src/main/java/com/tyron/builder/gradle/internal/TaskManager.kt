package com.tyron.builder.gradle.internal

import android.databinding.tool.DataBindingBuilder
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.MultipleArtifact
import com.tyron.builder.api.artifact.ScopedArtifact
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.api.transform.QualifiedContent
import com.tyron.builder.api.variant.ScopedArtifacts
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.api.variant.impl.TaskProviderBasedDirectoryEntryImpl
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.core.ComponentType
import com.tyron.builder.dexing.DexingType
import com.tyron.builder.dexing.isLegacyMultiDexMode
import com.tyron.builder.gradle.BaseExtension
import com.tyron.builder.gradle.api.AndroidSourceSet
import com.tyron.builder.gradle.internal.component.*
import com.tyron.builder.gradle.internal.dependency.AndroidXDependencySubstitution.androidXMappings
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.packaging.getDefaultDebugKeystoreLocation
import com.tyron.builder.gradle.internal.pipeline.OriginalStream
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactScope
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType
import com.tyron.builder.gradle.internal.res.GenerateLibraryRFileTask
import com.tyron.builder.gradle.internal.res.LinkAndroidResForBundleTask
import com.tyron.builder.gradle.internal.res.LinkApplicationAndroidResourcesTask
import com.tyron.builder.gradle.internal.res.ParseLibraryResourcesTask
import com.tyron.builder.gradle.internal.res.namespaced.NamespacedResourcesTaskManager
import com.tyron.builder.gradle.internal.scope.*
import com.tyron.builder.gradle.internal.services.AndroidLocationsBuildService
import com.tyron.builder.gradle.internal.services.getBuildService
import com.tyron.builder.gradle.internal.tasks.*
import com.tyron.builder.gradle.internal.tasks.databinding.*
import com.tyron.builder.gradle.internal.tasks.databinding.DataBindingCompilerArguments.Companion.createArguments
import com.tyron.builder.gradle.internal.tasks.factory.*
import com.tyron.builder.gradle.internal.tasks.featuresplit.getFeatureName
import com.tyron.builder.gradle.internal.utils.getProjectKotlinPluginKotlinVersion
import com.tyron.builder.gradle.internal.utils.isKotlinKaptPluginApplied
import com.tyron.builder.gradle.internal.utils.isKspPluginApplied
import com.tyron.builder.gradle.internal.variant.ApkVariantData
import com.tyron.builder.gradle.internal.variant.ComponentInfo
import com.tyron.builder.gradle.internal.variant.VariantModel
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.tasks.*
import com.tyron.builder.internal.tasks.factory.TaskFactoryImpl
import com.tyron.builder.plugin.tasks.PackageApplication
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

abstract class TaskManager<VariantBuilderT : VariantBuilder, VariantT : VariantCreationConfig>(
    @JvmField protected val project: Project,
    private val variants: Collection<ComponentInfo<VariantBuilderT, VariantT>>,
    private val testComponents: Collection<TestComponentCreationConfig>,
    private val testFixturesComponents: Collection<TestFixturesCreationConfig>,
    @JvmField protected val globalConfig: GlobalTaskCreationConfig,
    @JvmField protected val localConfig: TaskManagerConfig,
    @JvmField protected val extension: BaseExtension,
) {
    protected val logger: Logger = Logging.getLogger(this.javaClass)

    @JvmField
    protected val taskFactory: TaskFactory = TaskFactoryImpl(project.tasks)

    @JvmField
    protected val variantPropertiesList: List<VariantT> =
        variants.map(ComponentInfo<VariantBuilderT, VariantT>::variant)
    private val nestedComponents: List<NestedComponentCreationConfig> =
        testComponents + testFixturesComponents
    private val allPropertiesList: List<ComponentCreationConfig> =
        variantPropertiesList + nestedComponents


    /**
     * This is the main entry point into the task manager
     *
     *
     * This creates the tasks for all the variants and all the test components
     */
    fun createTasks(
        componentType: ComponentType, variantModel: VariantModel
    ) {
        // this is called before all the variants are created since they are all going to depend
        // on the global LINT_PUBLISH_JAR task output
        // setup the task that reads the config and put the lint jar in the intermediate folder
        // so that the bundle tasks can copy it, and the inter-project publishing can publish it
//        createPrepareLintJarForPublishTask()

        // create a lifecycle task to build the lintChecks dependencies
//        taskFactory.register(COMPILE_LINT_CHECKS_TASK) { task: Task ->
//            task.dependsOn(globalConfig.localCustomLintChecks)
//        }

        // Create top level test tasks.
        createTopLevelTestTasks()

        // Create tasks to manage test devices.
        createTestDevicesTasks()

        // Create tasks for all variants (main, testFixtures and tests)
        for (variant in variants) {
            createTasksForVariant(variant)
        }
        for (testFixturesComponent in testFixturesComponents) {
            createTasksForTestFixtures(testFixturesComponent)
        }
        for (testComponent in testComponents) {
            createTasksForTest(testComponent)
        }
        createTopLevelTasks(componentType, variantModel)
    }

    open fun createTopLevelTasks(componentType: ComponentType, variantModel: VariantModel) {
//        lintTaskManager.createLintTasks(
//            componentType,
//            variantModel,
//            variantPropertiesList,
//            testComponents
//        )
//        createReportTasks()
//
//        val androidLocationBuildService: Provider<AndroidLocationsBuildService> =
//            getBuildService(project.gradle.sharedServices)
//        createCxxTasks(
//            androidLocationBuildService.get(),
//            getBuildService(
//                globalConfig.services.buildServiceRegistry,
//                SdkComponentsBuildService::class.java
//            ).get(),
//            globalConfig.services.issueReporter,
//            taskFactory,
//            globalConfig.services.projectOptions,
//            variants,
//            project.providers,
//            project.layout
//        )
        // Global tasks required for privacy sandbox sdk consumption
//        if (globalConfig.services.projectOptions.get(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT)) {
//            taskFactory.register(ValidateSigningTask.PrivacySandboxSdkCreationAction(globalConfig))
//        }
    }

    fun createPostApiTasks() {

    }

    /**
     * Create tasks for the specified variant.
     *
     *
     * This creates tasks common to all variant types.
     */
    private fun createTasksForVariant(
        variant: ComponentInfo<VariantBuilderT, VariantT>,
    ) {
        val variantProperties = variant.variant
        val componentType = variantProperties.componentType
        val variantDependencies = variantProperties.variantDependencies

        if (variantProperties.dexingType.isLegacyMultiDexMode()
            && variantProperties.componentType.isApk
        ) {
            val multiDexDependency =
                if (variantProperties
                        .services
                        .projectOptions[BooleanOption.USE_ANDROID_X]
                )
                    ANDROIDX_MULTIDEX_MULTIDEX
                else COM_ANDROID_SUPPORT_MULTIDEX
            project.dependencies
                .add(variantDependencies.compileClasspath.name, multiDexDependency)
            project.dependencies
                .add(variantDependencies.runtimeClasspath.name, multiDexDependency)
        }
        if (variantProperties.renderscriptCreationConfig?.renderscript?.supportModeEnabled?.get()
            == true) {
            // TODO: renderscript support
            val fileCollection = project.files(
//                globalConfig.versionedSdkLoader.flatMap {
//                    it.renderScriptSupportJarProvider
//                }
            )
            project.dependencies.add(variantDependencies.compileClasspath.name, fileCollection)
            if (componentType.isApk && !componentType.isForTesting) {
                project.dependencies.add(variantDependencies.runtimeClasspath.name, fileCollection)
            }
        }
        createAssembleTask(variantProperties)
        if (variantProperties.services.projectOptions.get(BooleanOption.IDE_INVOKED_FROM_IDE)) {
            variantProperties.taskContainer.assembleTask.configure {
                it.dependsOn(variantProperties.artifacts.get(InternalArtifactType.VARIANT_MODEL))
            }
        }

        if (componentType.isBaseModule) {
            createBundleTask(variantProperties)
        }
        doCreateTasksForVariant(variant)
    }

    /**
     * Entry point for each specialized TaskManager to create the tasks for a given VariantT
     *
     * @param variantInfo the variantInfo for which to create the tasks
     */
    protected abstract fun doCreateTasksForVariant(
        variantInfo: ComponentInfo<VariantBuilderT, VariantT>
    )

    private fun createTopLevelTestTasks() {

    }

    protected fun createTestDevicesTasks() {

    }

    private fun createTasksForTestFixtures(testFixturesComponent: TestFixturesCreationConfig) {

    }

    /** Create tasks for the specified variant.  */
    private fun createTasksForTest(testVariant: TestComponentCreationConfig) {

    }

    protected open fun createVariantPreBuildTask(creationConfig: ComponentCreationConfig) {
        // default pre-built task.
        createDefaultPreBuildTask(creationConfig)
    }

    protected fun createDefaultPreBuildTask(creationConfig: ComponentCreationConfig) {
        taskFactory.register(PreBuildCreationAction(creationConfig))
    }

    abstract class AbstractPreBuildCreationAction<
            TaskT : AndroidVariantTask,
            ComponentT: ComponentCreationConfig>(
        creationConfig: ComponentT
    ) : VariantTaskCreationAction<TaskT, ComponentT>(creationConfig, false) {

        override val name: String
            get() = computeTaskName("pre", "Build")

        override fun handleProvider(taskProvider: TaskProvider<TaskT>) {
            super.handleProvider(taskProvider)
            creationConfig.taskContainer.preBuildTask = taskProvider
        }

        override fun configure(task: TaskT) {
            super.configure(task)
            task.dependsOn(MAIN_PREBUILD)
        }
    }

    private class PreBuildCreationAction(creationConfig: ComponentCreationConfig) :
        AbstractPreBuildCreationAction<AndroidVariantTask, ComponentCreationConfig>(creationConfig) {

        override val type: Class<AndroidVariantTask>
            get() = AndroidVariantTask::class.java
    }

    private fun handleJacocoDependencies(creationConfig: ComponentCreationConfig) {
        if (creationConfig is ApkCreationConfig && creationConfig.packageJacocoRuntime) {
//            val jacocoAgentRuntimeDependency = JacocoConfigurations.getAgentRuntimeDependency(
//                JacocoTask.getJacocoVersion(creationConfig))
//            project.dependencies
//                .add(
//                    creationConfig.variantDependencies.runtimeClasspath.name,
//                    jacocoAgentRuntimeDependency)
//
//            // we need to force the same version of Jacoco we use for instrumentation
//            creationConfig
//                .variantDependencies
//                .runtimeClasspath
//                .resolutionStrategy { r: ResolutionStrategy ->
//                    r.force(jacocoAgentRuntimeDependency)
//                }
//            taskFactory.register(JacocoPropertiesTask.CreationAction(creationConfig))
        }
    }

    /**
     * Creates the task for creating *.class files using javac. These tasks are created regardless
     * of whether Jack is used or not, but assemble will not depend on them if it is. They are
     * always used when running unit tests.
     */
    fun createJavacTask(
        creationConfig: ComponentCreationConfig
    ): TaskProvider<out JavaCompile> {
        val usingKapt = isKotlinKaptPluginApplied(project)
        val usingKsp = isKspPluginApplied(project)
        taskFactory.register(JavaPreCompileTask.CreationAction(creationConfig, usingKapt, usingKsp))
        val javacTask: TaskProvider<out JavaCompile> =
            taskFactory.register(
                JavaCompileCreationAction(
                    creationConfig,
                    project.objects,
                    usingKapt
                )
            )
        postJavacCreation(creationConfig)
        return javacTask
    }

    fun createAidlTask(creationConfig: ConsumableCreationConfig) {
        if (creationConfig.buildFeatures.aidl) {
            val taskContainer = creationConfig.taskContainer
            val aidlCompileTask = taskFactory.register(AidlCompile.CreationAction(creationConfig))
            taskContainer.sourceGenTask.dependsOn(aidlCompileTask)
        }
    }

    /**
     * Creates the java resources processing tasks.
     *
     * [Sync] task configured with [ProcessJavaResTask.CreationAction] will sync
     * all source folders into a single folder identified by [InternalArtifactType]
     *
     * This sets up only the Sync part. The java res merging is setup via [ ][.createMergeJavaResTask]
     */
    fun createProcessJavaResTask(creationConfig: ComponentCreationConfig) {
        // Copy the source folders java resources into the temporary location, mainly to
        // maintain the PluginDsl COPY semantics.
        taskFactory.register(ProcessJavaResTask.CreationAction(creationConfig))
    }

    /**
     * Sets up the Merge Java Res task.
     *
     * @see .createProcessJavaResTask
     */
    fun createMergeJavaResTask(creationConfig: ConsumableCreationConfig) {
        val transformManager = creationConfig.transformManager

        // Compute the scopes that need to be merged.
        @Suppress("DEPRECATION") // Legacy support
        val mergeScopes = getJavaResMergingScopes(creationConfig)
        taskFactory.register(MergeJavaResourceTask.CreationAction(mergeScopes, creationConfig))

        // also add a new merged java res stream if needed.
        if (creationConfig.needsMergedJavaResStream) {
            val mergedJavaResProvider = creationConfig.artifacts.get(InternalArtifactType.MERGED_JAVA_RES)
            transformManager.addStream(
                OriginalStream.builder("merged-java-res")
                    .addContentTypes(TransformManager.CONTENT_RESOURCES)
                    .addScopes(mergeScopes)
                    .setFileCollection(project.layout.files(mergedJavaResProvider))
                    .build())
        }
    }

    fun createMergeJniLibFoldersTasks(creationConfig: ConsumableCreationConfig) {
        // merge the source folders together using the proper priority.
        taskFactory.register(
            MergeSourceSetFolders.MergeJniLibFoldersCreationAction(creationConfig))
        taskFactory.register(MergeNativeLibsTask.CreationAction(creationConfig))
    }

    fun createMergeAssetsTask(creationConfig: ComponentCreationConfig) {
        taskFactory.register(MergeSourceSetFolders.MergeAppAssetCreationAction(creationConfig))
    }

    @Suppress("DEPRECATION") // Legacy support
    protected fun maybeCreateTransformClassesWithAsmTask(
        creationConfig: ComponentCreationConfig
    ) {

    }

    fun createBuildConfigTask(creationConfig: ConsumableCreationConfig) {
        creationConfig.buildConfigCreationConfig?.let { buildConfigCreationConfig ->
            val generateBuildConfigTask =
                taskFactory.register(GenerateBuildConfig.CreationAction(creationConfig))
            val isBuildConfigBytecodeEnabled = creationConfig
                .services
                .projectOptions[BooleanOption.ENABLE_BUILD_CONFIG_AS_BYTECODE]
            if (!isBuildConfigBytecodeEnabled
                // TODO(b/224758957): This is wrong we need to check the final build config
                //  fields from the variant API
                || buildConfigCreationConfig.dslBuildConfigFields.isNotEmpty()
            ) {
                creationConfig.taskContainer.sourceGenTask.dependsOn(generateBuildConfigTask)
            }
        }
    }

    fun createMergeApkManifestsTask(creationConfig: ApkCreationConfig) {
        val apkVariantData = creationConfig.oldVariantApiLegacySupport!!.variantData as ApkVariantData
        val screenSizes = apkVariantData.compatibleScreens
        taskFactory.register(
            CompatibleScreensManifest.CreationAction(creationConfig, screenSizes))
        val processManifestTask = createMergeManifestTasks(creationConfig)
        val taskContainer = creationConfig.taskContainer
        if (taskContainer.microApkTask != null) {
            processManifestTask.dependsOn(taskContainer.microApkTask)
        }
    }

    /** Creates the merge manifests task.  */
    protected open fun createMergeManifestTasks(
        creationConfig: ApkCreationConfig): TaskProvider<out ManifestProcessorTask?> {
        taskFactory.register(ProcessManifestForBundleTask.CreationAction(creationConfig))
        taskFactory.register(
            ProcessManifestForMetadataFeatureTask.CreationAction(creationConfig))
        taskFactory.register(ProcessManifestForInstantAppTask.CreationAction(creationConfig))
        taskFactory.register(ProcessPackagedManifestTask.CreationAction(creationConfig))
        taskFactory.register(GenerateManifestJarTask.CreationAction(creationConfig))
        taskFactory.register(ProcessApplicationManifest.CreationAction(creationConfig))
        return taskFactory.register(
            ProcessMultiApkApplicationManifest.CreationAction(creationConfig))
    }

    fun createGenerateResValuesTask(creationConfig: ComponentCreationConfig) {
        if (creationConfig.buildFeatures.resValues) {
            val generateResValuesTask =
                taskFactory.register(GenerateResValues.
                CreationAction(creationConfig))
            creationConfig.taskContainer.resourceGenTask.dependsOn(generateResValuesTask)
        }
    }

    /**
     * Creates the post-compilation tasks for the given Variant.
     *
     *
     * These tasks create the dex file from the .class files, plus optional intermediary steps
     * like proguard and jacoco
     */
    fun createPostCompilationTasks(creationConfig: ApkCreationConfig) {
        Preconditions.checkNotNull(creationConfig.taskContainer.javacTask)
        val transformManager = creationConfig.transformManager
        taskFactory.register(MergeGeneratedProguardFilesCreationAction(creationConfig))

        // Merge Java Resources.
        createMergeJavaResTask(creationConfig)

        // -----------------------------------------------------------------------------------------
        // The following task registrations MUST follow the order:
        //   ASM API -> Legacy transforms -> jacoco transforms
        // -----------------------------------------------------------------------------------------

        maybeCreateTransformClassesWithAsmTask(creationConfig)

        // New gradle-transform jacoco instrumentation support.
        if (creationConfig.isAndroidTestCoverageEnabled &&
            !creationConfig.componentType.isForTesting) {
//            createJacocoTask(creationConfig)
            TODO()
        }

        // initialize the all classes scope, at this point we do not consume the classes, just read
        // the content as folks can be accessing these classes without transforming them and
        // re-injecting them in the build flow.
        creationConfig.artifacts.forScope(ScopedArtifacts.Scope.ALL)
            .getScopedArtifactsContainer(ScopedArtifact.CLASSES)
            .initialScopedContent
            .from(
                creationConfig
                    .transformManager
                    .getPipelineOutputAsFileCollection { contentTypes, scopes ->
                        contentTypes.contains(com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.CLASSES)
                                && scopes.intersect(TransformManager.SCOPE_FULL_PROJECT_WITH_LOCAL_JARS).isNotEmpty()
                    }
            )

        // let's check if the ALL scoped classes are transformed.
        if (creationConfig.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                .getScopedArtifactsContainer(ScopedArtifact.CLASSES).artifactsAltered.get()) {

            // at this point, we need to consume all these streams as they will be provided by the
            // final producer of the CLASSES artifact.
            creationConfig.transformManager
                .consumeStreams(
                    TransformManager.SCOPE_FULL_PROJECT_WITH_LOCAL_JARS,
                    setOf(com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.CLASSES)
                )

            // and register the final transformed version back into the transform pipeline.
            creationConfig.transformManager
                .addStream(
                    OriginalStream.builder("variant-api-transformed-classes")
                        .addContentTypes(TransformManager.CONTENT_CLASS)
                        .addScope(QualifiedContent.Scope.PROJECT)
                        .setFileCollection(
                            creationConfig.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                                .getFinalArtifacts(ScopedArtifact.CLASSES)
                        )
                        .build()
                )
        }

        // Add a task to create merged runtime classes if this is a dynamic-feature,
        // or a base module consuming feature jars. Merged runtime classes are needed if code
        // minification is enabled in a project with features or dynamic-features.
        if (creationConfig.componentType.isDynamicFeature
            || (creationConfig as? ApplicationCreationConfig)?.consumesFeatureJars == true) {
            taskFactory.register(MergeClassesTask.CreationAction(creationConfig))
        }

        // Produce better error messages when we have duplicated classes.
        maybeCreateCheckDuplicateClassesTask(creationConfig)

        // Resource Shrinking
        maybeCreateResourcesShrinkerTasks(creationConfig)

        // Code Shrinking
        // Since the shrinker (R8) also dexes the class files, if we have minifedEnabled we stop
        // the flow and don't set-up dexing.
        maybeCreateJavaCodeShrinkerTask(creationConfig)
        if (creationConfig.minifiedEnabled) {
            maybeCreateDesugarLibTask(creationConfig, false)
            return
        }

        // Code Dexing (MonoDex, Legacy Multidex or Native Multidex)
        if (creationConfig.needsMainDexListForBundle) {
            taskFactory.register(D8BundleMainDexListTask.CreationAction(creationConfig))
        }
        if (creationConfig.componentType.isDynamicFeature) {
            taskFactory.register(FeatureDexMergeTask.CreationAction(creationConfig))
        }
        createDexTasks(creationConfig, creationConfig.dexingType)
    }

    /**
     * Creates tasks used for DEX generation. This will use an incremental pipeline that uses dex
     * archives in order to enable incremental dexing support.
     */
    private fun createDexTasks(
        creationConfig: ApkCreationConfig,
        dexingType: DexingType
    ) {
        val java8LangSupport = creationConfig.getJava8LangSupportType()
        val supportsDesugaringViaArtifactTransform =
            (java8LangSupport == Java8LangSupport.UNUSED
                    || (java8LangSupport == Java8LangSupport.D8
                    && creationConfig
                .services
                .projectOptions[BooleanOption.ENABLE_DEXING_DESUGARING_ARTIFACT_TRANSFORM]))

        val classesAlteredTroughVariantAPI = creationConfig
            .artifacts
            .forScope(ScopedArtifacts.Scope.ALL)
            .getScopedArtifactsContainer(ScopedArtifact.CLASSES)
            .artifactsAltered
            .get()

        val enableDexingArtifactTransform = (creationConfig
            .services
            .projectOptions[BooleanOption.ENABLE_DEXING_ARTIFACT_TRANSFORM]
                && supportsDesugaringViaArtifactTransform)
                && !classesAlteredTroughVariantAPI
        val classpathUtils = ClassesClasspathUtils(
            creationConfig,
            enableDexingArtifactTransform,
            classesAlteredTroughVariantAPI
        )
        taskFactory.register(
            DexArchiveBuilderTask.CreationAction(
                creationConfig,
                classpathUtils,
            )
        )

        maybeCreateDesugarLibTask(creationConfig, enableDexingArtifactTransform)
        createDexMergingTasks(creationConfig, dexingType, enableDexingArtifactTransform, classesAlteredTroughVariantAPI)
    }

    /**
     * Set up dex merging tasks when artifact transforms are used.
     *
     *
     * External libraries are merged in mono-dex and native multidex modes. In case of a native
     * multidex debuggable variant these dex files get packaged. In mono-dex case, we will re-merge
     * these files. Because this task will be almost always up-to-date, having a second merger run
     * over the external libraries will not cause a performance regression. In addition to that,
     * second dex merger will perform less I/O compared to reading all external library dex files
     * individually. For legacy multidex, we must merge all dex files in a single invocation in
     * order to generate correct primary dex file in presence of desugaring. See b/120039166.
     *
     *
     * When merging native multidex, debuggable variant, project's dex files are merged
     * independently. Also, the library projects' dex files are merged independently.
     *
     *
     * For all other variants (release, mono-dex, legacy-multidex), we merge all dex files in a
     * single invocation. This means that external libraries, library projects and project dex files
     * will be merged in a single task.
     */
    private fun createDexMergingTasks(
        creationConfig: ApkCreationConfig,
        dexingType: DexingType,
        dexingUsingArtifactTransforms: Boolean,
        classesAlteredThroughVariantAPI: Boolean,
    ) {

        // if classes were altered at the ALL scoped level, we just need to merge the single jar
        // file resulting.
        if (classesAlteredThroughVariantAPI) {
            taskFactory.register(DexMergingTask.CreationAction(
                creationConfig,
                DexMergingAction.MERGE_TRANSFORMED_CLASSES,
                dexingType,
                dexingUsingArtifactTransforms))
            return
        }

        // When desugaring, The file dependencies are dexed in a task with the whole
        // remote classpath present, as they lack dependency information to desugar
        // them correctly in an artifact transform.
        // This should only be passed to Legacy Multidex MERGE_ALL or MERGE_EXTERNAL_LIBS of
        // other dexing modes, otherwise it will cause the output of DexFileDependenciesTask
        // to be included multiple times and will cause the build to fail because of same types
        // being defined multiple times in the final dex.
        val separateFileDependenciesDexingTask =
            (creationConfig.getJava8LangSupportType() == Java8LangSupport.D8
                    && dexingUsingArtifactTransforms)
        if (separateFileDependenciesDexingTask) {
            val desugarFileDeps = DexFileDependenciesTask.CreationAction(creationConfig)
            taskFactory.register(desugarFileDeps)
        }

        when (dexingType) {
            DexingType.MONO_DEX -> {
                taskFactory.register(
                    DexMergingTask.CreationAction(
                        creationConfig,
                        DexMergingAction.MERGE_EXTERNAL_LIBS,
                        dexingType,
                        dexingUsingArtifactTransforms,
                        separateFileDependenciesDexingTask,
                        InternalMultipleArtifactType.EXTERNAL_LIBS_DEX))
                taskFactory.register(
                    DexMergingTask.CreationAction(
                        creationConfig,
                        DexMergingAction.MERGE_ALL,
                        dexingType,
                        dexingUsingArtifactTransforms))
            }
            DexingType.LEGACY_MULTIDEX -> {
                // For Legacy Multidex we cannot employ the same optimization of first merging
                // the external libraries, because in that step we don't have a main dex list file
                // to pass to D8 yet, and together with the fact that we'll be setting minApiLevel
                // to 20 or less it will make the external libs be merged in a way equivalent to
                // MonoDex, which might cause the build to fail if the external libraries alone
                // cannot fit into a single dex.
                taskFactory.register(
                    DexMergingTask.CreationAction(
                        creationConfig,
                        DexMergingAction.MERGE_ALL,
                        dexingType,
                        dexingUsingArtifactTransforms,
                        separateFileDependenciesDexingTask))
            }
            DexingType.NATIVE_MULTIDEX -> {
                // For a debuggable variant, we merge different bits in separate tasks.
                // Potentially more .dex files being created, but during development-cycle of
                // developers, code changes will hopefully impact less .dex files and will make
                // the build be faster.
                // For non-debuggable (release) builds, we do only a MERGE_EXTERNAL_LIBS in a
                // separate task and then merge everything using a single MERGE_ALL pass in order
                // to minimize the number of produced .dex files since there is a systemic overhead
                // (size-wise) when we have multiple .dex files.
                if (creationConfig.debuggable) {
                    taskFactory.register(
                        DexMergingTask.CreationAction(
                            creationConfig,
                            DexMergingAction.MERGE_EXTERNAL_LIBS,
                            dexingType,
                            dexingUsingArtifactTransforms,
                            separateFileDependenciesDexingTask))
                    taskFactory.register(
                        DexMergingTask.CreationAction(
                            creationConfig,
                            DexMergingAction.MERGE_PROJECT,
                            dexingType,
                            dexingUsingArtifactTransforms))
                    taskFactory.register(
                        DexMergingTask.CreationAction(
                            creationConfig,
                            DexMergingAction.MERGE_LIBRARY_PROJECTS,
                            dexingType,
                            dexingUsingArtifactTransforms))
                } else {
                    taskFactory.register(
                        DexMergingTask.CreationAction(
                            creationConfig,
                            DexMergingAction.MERGE_EXTERNAL_LIBS,
                            dexingType,
                            dexingUsingArtifactTransforms,
                            separateFileDependenciesDexingTask,
                            InternalMultipleArtifactType.EXTERNAL_LIBS_DEX))
                    taskFactory.register(
                        DexMergingTask.CreationAction(
                            creationConfig,
                            DexMergingAction.MERGE_ALL,
                            dexingType,
                            dexingUsingArtifactTransforms))
                }
            }
        }
    }



    private fun maybeCreateDesugarLibTask(
        apkCreationConfig: ApkCreationConfig,
        enableDexingArtifactTransform: Boolean) {
        val separateFileDependenciesDexingTask =
            (apkCreationConfig.getJava8LangSupportType() == Java8LangSupport.D8
                    && enableDexingArtifactTransform)
        if (apkCreationConfig.shouldPackageDesugarLibDex) {
            taskFactory.register(
                L8DexDesugarLibTask.CreationAction(
                    apkCreationConfig,
                    enableDexingArtifactTransform,
                    separateFileDependenciesDexingTask))
        }

        if(apkCreationConfig.componentType.isDynamicFeature
            && apkCreationConfig.needsShrinkDesugarLibrary
        ) {
            taskFactory.register(
                DesugarLibKeepRulesMergeTask.CreationAction(
                    apkCreationConfig,
                    enableDexingArtifactTransform,
                    separateFileDependenciesDexingTask
                )
            )
        }
    }

    protected open fun maybeCreateJavaCodeShrinkerTask(
        creationConfig: ConsumableCreationConfig) {
        if (creationConfig.minifiedEnabled) {
            doCreateJavaCodeShrinkerTask(creationConfig)
        }
    }

    /**
     * Actually creates the minify transform, using the given mapping configuration. The mapping is
     * only used by test-only modules.
     */
    protected fun doCreateJavaCodeShrinkerTask(
        creationConfig: ConsumableCreationConfig,
        isTestApplication: Boolean = false) {
        // The compile R class jar is added to the classes to be processed in libraries so that
        // proguard can shrink an empty library project, as the R class is always kept and
        // then removed by library jar transforms.
//        val addCompileRClass = (this is LibraryTaskManager
//                && creationConfig.buildFeatures.androidResources)
//        val task: TaskProvider<out Task> =
//            createR8Task(creationConfig, isTestApplication, addCompileRClass)
//        if (creationConfig.postProcessingFeatures != null) {
//            val checkFilesTask =
//                taskFactory.register(CheckProguardFiles.CreationAction(creationConfig))
//            task.dependsOn(checkFilesTask)
//        }
    }



    /**
     * If resource shrinker is enabled, set-up and register the appropriate tasks.
     */
    private fun maybeCreateResourcesShrinkerTasks(
        creationConfig: ConsumableCreationConfig) {
//        if (creationConfig.androidResourcesCreationConfig?.useResourceShrinker != true) {
//            return
//        }
//        if (creationConfig.componentType.isDynamicFeature) {
//            // For bundles resources are shrunk once bundle is packaged so the task is applicable
//            // for base module only.
//            return
//        }
//        if (creationConfig.services.projectOptions.get(BooleanOption.ENABLE_NEW_RESOURCE_SHRINKER)) {
//            // Shrink resources in APK with a new resource shrinker and produce stripped res
//            // package.
//            taskFactory.register(ShrinkResourcesNewShrinkerTask.CreationAction(creationConfig))
//            // Shrink resources in bundles with new resource shrinker.
//            taskFactory.register(ShrinkAppBundleResourcesTask.CreationAction(creationConfig))
//        } else {
//            // Shrink resources in APK with old resource shrinker and produce stripped res package.
//            taskFactory.register(ShrinkResourcesOldShrinkerTask.CreationAction(creationConfig))
//            // Shrink base module resources in proto format to be packaged to bundle.
//            taskFactory.register(
//                LegacyShrinkBundleModuleResourcesTask.CreationAction(creationConfig))
//        }
    }

    private fun maybeCreateCheckDuplicateClassesTask(
        creationConfig: ComponentCreationConfig) {
        if (creationConfig
                .services
                .projectOptions[BooleanOption.ENABLE_DUPLICATE_CLASSES_CHECK]) {
            taskFactory.register(CheckDuplicateClassesTask.CreationAction(creationConfig))
        }
    }

    protected open fun postJavacCreation(creationConfig: ComponentCreationConfig) {
        // Use the deprecated public artifact types to register the pre/post JavaC hooks as well as
        // the javac output itself.
        // It is necessary to do so in case some third-party plugin is using those deprecated public
        // artifact type to append/transform/replace content.
        // Once the deprecated types can be removed, all the methods below should use the
        // [ScopedArtifacts.setInitialContent] methods to initialize directly the scoped container.
        creationConfig.oldVariantApiLegacySupport?.variantData?.let { variantData ->
            creationConfig
                .artifacts
                .appendAll(
                    MultipleArtifact.ALL_CLASSES_JARS,
                    variantData.allPreJavacGeneratedBytecode.getRegularFiles(
                        project.layout.projectDirectory
                    )
                )

            creationConfig
                .artifacts
                .appendAll(
                    MultipleArtifact.ALL_CLASSES_DIRS,
                    variantData.allPreJavacGeneratedBytecode.getDirectories(
                        project.layout.projectDirectory
                    )
                )

            creationConfig
                .artifacts
                .appendAll(
                    MultipleArtifact.ALL_CLASSES_JARS,
                    variantData.allPostJavacGeneratedBytecode.getRegularFiles(
                        project.layout.projectDirectory
                    )
                )

            creationConfig
                .artifacts
                .appendAll(
                    MultipleArtifact.ALL_CLASSES_DIRS,
                    variantData.allPostJavacGeneratedBytecode.getDirectories(
                        project.layout.projectDirectory
                    )
                )
        }
        creationConfig
            .artifacts
            .appendTo(
                MultipleArtifact.ALL_CLASSES_DIRS,
                InternalArtifactType.JAVAC
            )
    }

    /**
     * Add stream of classes compiled by javac to transform manager.
     *
     *
     * This should not be called for classes that will also be compiled from source by jack.
     */
    @Suppress("DEPRECATION") // Legacy support
    protected fun addJavacClassesStream(creationConfig: ComponentCreationConfig) {
        // create separate streams for all the classes coming from javac, pre/post hooks and R.
        val transformManager = creationConfig.transformManager
        transformManager.addStream(
            OriginalStream.builder("all-classes") // Need both classes and resources because some annotation
                // processors generate resources
                .addContentTypes(setOf(QualifiedContent.DefaultContentType.CLASSES))
                .addScope(QualifiedContent.Scope.PROJECT)
                .setFileCollection(creationConfig
                    .artifacts
                    .forScope(ScopedArtifacts.Scope.PROJECT)
                    .getFinalArtifacts(ScopedArtifact.CLASSES))
                .build())
    }

    fun createApkProcessResTask(creationConfig: ApkCreationConfig) {
        val componentType = creationConfig.componentType
        val packageOutputType: InternalArtifactType<Directory>? =
            if (componentType.isApk && !componentType.isForTesting) InternalArtifactType.FEATURE_RESOURCE_PKG else null
        createApkProcessResTask(creationConfig, packageOutputType)
        if ((creationConfig as? ApplicationCreationConfig)?.consumesFeatureJars == true) {
            taskFactory.register(MergeAaptProguardFilesCreationAction(creationConfig))
        }
    }

    private fun createApkProcessResTask(
        creationConfig: ComponentCreationConfig,
        packageOutputType: Artifact.Single<Directory>?) {
        // Check AAR metadata files
        taskFactory.register(CheckAarMetadataTask.CreationAction(creationConfig))

        val projectInfo = creationConfig.services.projectInfo

        // Create the APK_ file with processed resources and manifest. Generate the R class.
        createProcessResTask(
            creationConfig,
            packageOutputType,
            MergeType.MERGE,
            projectInfo.getProjectBaseName())
        val projectOptions = creationConfig.services.projectOptions
        val nonTransitiveR = projectOptions[BooleanOption.NON_TRANSITIVE_R_CLASS]
        val namespaced: Boolean = globalConfig.namespacedAndroidResources

        // TODO(b/138780301): Also use compile time R class in android tests.
        if ((projectOptions[BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS] || nonTransitiveR)
            && !creationConfig.componentType.isForTesting
            && !namespaced) {
            // Generate the COMPILE TIME only R class using the local resources instead of waiting
            // for the above full link to finish. Linking will still output the RUN TIME R class.
            // Since we're gonna use AAPT2 to generate the keep rules, do not generate them here.
            createProcessResTask(
                creationConfig,
                packageOutputType,
                MergeType.PACKAGE,
                projectInfo.getProjectBaseName())
        }
    }

    fun createProcessResTask(
        creationConfig: ComponentCreationConfig,
        packageOutputType: Artifact.Single<Directory>?,
        mergeType: MergeType,
        baseName: String) {
        if (!creationConfig.buildFeatures.androidResources &&
            creationConfig !is AndroidTestCreationConfig) {
            return
        }
        creationConfig.oldVariantApiLegacySupport?.variantData?.calculateFilters(
            creationConfig.global.splits
        )

        // The manifest main dex list proguard rules are always needed for the bundle,
        // even if legacy multidex is not explicitly enabled.
        val useAaptToGenerateLegacyMultidexMainDexProguardRules =
            (creationConfig is ApkCreationConfig
                    && creationConfig
                .dexingType
                .needsMainDexList)
        if (globalConfig.namespacedAndroidResources) {
            // TODO: make sure we generate the proguard rules in the namespaced case.
            NamespacedResourcesTaskManager(taskFactory, creationConfig)
                .createNamespacedResourceTasks(
                    packageOutputType,
                    baseName,
                    useAaptToGenerateLegacyMultidexMainDexProguardRules)
            val rFiles: FileCollection = project.files(
                creationConfig.artifacts.get(InternalArtifactType.RUNTIME_R_CLASS_CLASSES))
            @Suppress("DEPRECATION") // Legacy support
            creationConfig
                .transformManager
                .addStream(
                    OriginalStream.builder("final-r-classes")
                        .addContentTypes(setOf(QualifiedContent.DefaultContentType.CLASSES))
                        .addScope(QualifiedContent.Scope.PROJECT)
                        .setFileCollection(rFiles)
                        .build())
            creationConfig
                .artifacts
                .appendTo(MultipleArtifact.ALL_CLASSES_DIRS,
                    InternalArtifactType.RUNTIME_R_CLASS_CLASSES
                )
            return
        }
        createNonNamespacedResourceTasks(
            creationConfig,
            packageOutputType,
            mergeType,
            baseName,
            useAaptToGenerateLegacyMultidexMainDexProguardRules)
    }

    private fun createNonNamespacedResourceTasks(
        creationConfig: ComponentCreationConfig,
        packageOutputType: Artifact.Single<Directory>?,
        mergeType: MergeType,
        baseName: String,
        useAaptToGenerateLegacyMultidexMainDexProguardRules: Boolean) {
        val artifacts = creationConfig.artifacts
        val projectOptions = creationConfig.services.projectOptions
        when(mergeType) {
            MergeType.PACKAGE -> {
                // MergeType.PACKAGE means we will only merged the resources from our current module
                // (little merge). This is used for finding what goes into the AAR (packaging), and also
                // for parsing the local resources and merging them with the R.txt files from its
                // dependencies to write the R.txt for this module and R.jar for this module and its
                // dependencies.

                // First collect symbols from this module.
                taskFactory.register(ParseLibraryResourcesTask.CreateAction(creationConfig))

                // Only generate the keep rules when we need them. We don't need to generate them here
                // for non-library modules since AAPT2 will generate them from MergeType.MERGE.
                if (generatesProguardOutputFile(creationConfig) &&
                    creationConfig.componentType.isAar) {
                    taskFactory.register(
                        GenerateLibraryProguardRulesTask.CreationAction(creationConfig))
                }
                val nonTransitiveRClassInApp = projectOptions[BooleanOption.NON_TRANSITIVE_R_CLASS]
                val compileTimeRClassInApp = projectOptions[BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS]
                // Generate the R class for a library using both local symbols and symbols
                // from dependencies.
                // TODO: double check this (what about dynamic features?)
                if (!nonTransitiveRClassInApp || compileTimeRClassInApp || creationConfig.componentType.isAar) {
                    taskFactory.register(
                        GenerateLibraryRFileTask.CreationAction(
                        creationConfig,
                        creationConfig.componentType.isAar
                    ))
                }
            }
            MergeType.MERGE -> {
                // MergeType.MERGE means we merged the whole universe.
                taskFactory.register(
                    LinkApplicationAndroidResourcesTask.CreationAction(
                        creationConfig,
                        useAaptToGenerateLegacyMultidexMainDexProguardRules,
                        mergeType,
                        baseName,
                        creationConfig.componentType.isAar))
                if (packageOutputType != null) {
                    creationConfig.artifacts.republish(InternalArtifactType.PROCESSED_RES, packageOutputType)
                }

                // TODO: also support stable IDs for the bundle (does it matter?)
                // create the task that creates the aapt output for the bundle.
                if (creationConfig is ApkCreationConfig
                    && !creationConfig.componentType.isForTesting) {
                    taskFactory.register(
                        LinkAndroidResForBundleTask.CreationAction(
                            creationConfig))
                }
                artifacts.appendTo(
                    MultipleArtifact.ALL_CLASSES_JARS,
                    InternalArtifactType.COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR
                )

                if (!creationConfig.debuggable &&
                    !creationConfig.componentType.isForTesting &&
                    projectOptions[BooleanOption.ENABLE_RESOURCE_OPTIMIZATIONS]) {
                    taskFactory.register(OptimizeResourcesTask.CreateAction(creationConfig))
                }
            }
            else -> throw RuntimeException("Unhandled merge type : $mergeType")
        }
    }

    fun createMergeResourcesTask(
        creationConfig: ComponentCreationConfig,
        processResources: Boolean,
        flags: Set<MergeResources.Flag>) {
        if (!creationConfig.buildFeatures.androidResources &&
            creationConfig !is AndroidTestCreationConfig) {
            return
        }
        val alsoOutputNotCompiledResources = (creationConfig.componentType.isApk
                && !creationConfig.componentType.isForTesting
                && creationConfig.androidResourcesCreationConfig!!.useResourceShrinker)
        val includeDependencies = true
        basicCreateMergeResourcesTask(
            creationConfig,
            MergeType.MERGE,
            includeDependencies,
            processResources,
            alsoOutputNotCompiledResources,
            flags,
            null /*configCallback*/)
        taskFactory.register(
            MapSourceSetPathsTask.CreateAction(creationConfig, includeDependencies))
    }

    /** Defines the merge type for [.basicCreateMergeResourcesTask]  */
    enum class MergeType {

        /** Merge all resources with all the dependencies resources (i.e. "big merge").  */
        MERGE {

            override val outputType: Artifact.Single<Directory>
                get() = InternalArtifactType.MERGED_RES
        },

        /**
         * Merge all resources without the dependencies resources for an aar (i.e. "small merge").
         */
        PACKAGE {

            override val outputType: Artifact.Single<Directory>
                get() = InternalArtifactType.PACKAGED_RES
        };

        abstract val outputType: Artifact.Single<Directory>
    }

    fun basicCreateMergeResourcesTask(
        creationConfig: ComponentCreationConfig,
        mergeType: MergeType,
        includeDependencies: Boolean,
        processResources: Boolean,
        alsoOutputNotCompiledResources: Boolean,
        flags: Set<MergeResources.Flag>,
        taskProviderCallback: TaskProviderCallback<MergeResources>?
    ): TaskProvider<MergeResources> {
        val mergedNotCompiledDir = if (alsoOutputNotCompiledResources) File(
            creationConfig.services.projectInfo.getIntermediatesDir()
                .toString() + "/merged-not-compiled-resources/"
                    + creationConfig.dirName) else null
        val mergeResourcesTask: TaskProvider<MergeResources> = taskFactory.register(
            MergeResources.CreationAction(
                creationConfig,
                mergeType,
                mergedNotCompiledDir,
                includeDependencies,
                processResources,
                flags,
                creationConfig.componentType.isAar),
            null,
            null,
            taskProviderCallback)
//        if (globalConfig.testOptions.unitTests.isIncludeAndroidResources) {
//            creationConfig.taskContainer.compileTask.dependsOn(mergeResourcesTask)
//        }
        return mergeResourcesTask
    }

    /**
     * Returns the scopes for which the java resources should be merged.
     *
     * @param creationConfig the scope of the variant being processed.
     * @param contentType the contentType of java resources, must be RESOURCES or NATIVE_LIBS
     * @return the list of scopes for which to merge the java resources.
     */
    @Suppress("DEPRECATION") // Legacy support
    protected abstract fun getJavaResMergingScopes(
        creationConfig: ComponentCreationConfig): Set<com.tyron.builder.api.transform.QualifiedContent.ScopeType>

    @Suppress("DEPRECATION") // Legacy support
    protected open fun createDependencyStreams(creationConfig: ComponentCreationConfig) {
        // Since it's going to chance the configurations, we need to do it before
        // we start doing queries to fill the streams.
        handleJacocoDependencies(creationConfig)
        creationConfig.instrumentationCreationConfig?.configureAndLockAsmClassesVisitors(
            project.objects
        )
        val transformManager = creationConfig.transformManager

        fun getFinalRuntimeClassesJarsFromComponent(
            component: ComponentCreationConfig,
            scope: ArtifactScope
        ): FileCollection {
            return component.instrumentationCreationConfig?.getDependenciesClassesJarsPostInstrumentation(
                scope
            ) ?: component.variantDependencies.getArtifactFileCollection(
                ConsumedConfigType.RUNTIME_CLASSPATH,
                scope,
                AndroidArtifacts.ArtifactType.CLASSES_JAR
            )
        }

        // This might be consumed by RecalculateFixedStackFrames if that's created
        transformManager.addStream(
            OriginalStream.builder("ext-libs-classes")
                .addContentTypes(TransformManager.CONTENT_CLASS)
                .addScope(com.tyron.builder.api.transform.QualifiedContent.Scope.EXTERNAL_LIBRARIES)
                .setFileCollection(
                    getFinalRuntimeClassesJarsFromComponent(
                        creationConfig,
                        ArtifactScope.EXTERNAL
                    )
                ).build()
        )

        // Add stream of external java resources if EXTERNAL_LIBRARIES isn't in the set of java res
        // merging scopes.
        if (!getJavaResMergingScopes(creationConfig)
                .contains(com.tyron.builder.api.transform.QualifiedContent.Scope.EXTERNAL_LIBRARIES)) {
            transformManager.addStream(
                OriginalStream.builder("ext-libs-java-res")
                    .addContentTypes(com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.RESOURCES)
                    .addScope(com.tyron.builder.api.transform.QualifiedContent.Scope.EXTERNAL_LIBRARIES)
                    .setArtifactCollection(
                        creationConfig
                            .variantDependencies
                            .getArtifactCollection(ConsumedConfigType.RUNTIME_CLASSPATH,
                                ArtifactScope.EXTERNAL,
                                AndroidArtifacts.ArtifactType.JAVA_RES))
                    .build())
        }

        // for the sub modules, new intermediary classes artifact has its own stream
        transformManager.addStream(
            OriginalStream.builder("sub-projects-classes")
                .addContentTypes(TransformManager.CONTENT_CLASS)
                .addScope(com.tyron.builder.api.transform.QualifiedContent.Scope.SUB_PROJECTS)
                .setFileCollection(
                    getFinalRuntimeClassesJarsFromComponent(
                        creationConfig,
                        ArtifactScope.PROJECT
                    )
                ).build()
        )

        // same for the java resources, if SUB_PROJECTS isn't in the set of java res merging scopes.
        if (!getJavaResMergingScopes(creationConfig).contains(
                com.tyron.builder.api.transform.QualifiedContent.Scope.SUB_PROJECTS)) {
            transformManager.addStream(
                OriginalStream.builder("sub-projects-java-res")
                    .addContentTypes(com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.RESOURCES)
                    .addScope(com.tyron.builder.api.transform.QualifiedContent.Scope.SUB_PROJECTS)
                    .setArtifactCollection(
                        creationConfig
                            .variantDependencies
                            .getArtifactCollection(
                                ConsumedConfigType.RUNTIME_CLASSPATH,
                                ArtifactScope.PROJECT,
                                AndroidArtifacts.ArtifactType.JAVA_RES))
                    .build())
        }

        // if consumesFeatureJars, add streams of classes from features or
        // dynamic-features.
        // The main dex list calculation for the bundle also needs the feature classes for reference
        // only
        if ((creationConfig as? ApplicationCreationConfig)?.consumesFeatureJars == true ||
            (creationConfig as? ConsumableCreationConfig)?.needsMainDexListForBundle == true) {
            transformManager.addStream(
                OriginalStream.builder("metadata-classes")
                    .addContentTypes(TransformManager.CONTENT_CLASS)
                    .addScope(InternalScope.FEATURES)
                    .setArtifactCollection(
                        creationConfig
                            .variantDependencies
                            .getArtifactCollection(
                                ConsumedConfigType.REVERSE_METADATA_VALUES,
                                ArtifactScope.PROJECT,
                                AndroidArtifacts.ArtifactType.REVERSE_METADATA_CLASSES))
                    .build())
        }

        // provided only scopes.
        transformManager.addStream(
            OriginalStream.builder("provided-classes")
                .addContentTypes(TransformManager.CONTENT_CLASS)
                .addScope(com.tyron.builder.api.transform.QualifiedContent.Scope.PROVIDED_ONLY)
                .setFileCollection(creationConfig.providedOnlyClasspath)
                .build())
        (creationConfig as? TestComponentCreationConfig)?.onTestedVariant { testedVariant ->
            val testedCodeDeps = getFinalRuntimeClassesJarsFromComponent(
                testedVariant,
                ArtifactScope.ALL
            )
            transformManager.addStream(
                OriginalStream.builder("tested-code-deps")
                    .addContentTypes(com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.CLASSES)
                    .addScope(com.tyron.builder.api.transform.QualifiedContent.Scope.TESTED_CODE)
                    .setFileCollection(testedCodeDeps)
                    .build())
            null
        }
    }

    protected fun createDataBindingTasksIfNecessary(creationConfig: ComponentCreationConfig) {
        val dataBindingEnabled = creationConfig.buildFeatures.dataBinding
        val viewBindingEnabled = creationConfig.buildFeatures.viewBinding
        if (!dataBindingEnabled && !viewBindingEnabled) {
            return
        }
        taskFactory.register(
            DataBindingMergeDependencyArtifactsTask.CreationAction(creationConfig))
        DataBindingBuilder.setDebugLogEnabled(logger.isDebugEnabled)
        taskFactory.register(DataBindingGenBaseClassesTask.CreationAction(creationConfig))

        // DATA_BINDING_TRIGGER artifact is created for data binding only (not view binding)
        if (dataBindingEnabled) {
            if (creationConfig.services.projectOptions.get(BooleanOption.NON_TRANSITIVE_R_CLASS)
                && isKotlinKaptPluginApplied(project)) {
                val kotlinVersion = getProjectKotlinPluginKotlinVersion(project)
                if (kotlinVersion != null && kotlinVersion < KAPT_FIX_KOTLIN_VERSION) {
                    // Before Kotlin version 1.5.20 there was an issue with KAPT resolving files
                    // at configuration time. We only need this task as a workaround for it, if the
                    // version is newer than 1.5.20 or KAPT isn't applied, we can skip it.
                    taskFactory.register(
                        MergeRFilesForDataBindingTask.CreationAction(creationConfig))
                }
            }
            taskFactory.register(DataBindingTriggerTask.CreationAction(creationConfig))
            creationConfig.sources.java.addSource(
                TaskProviderBasedDirectoryEntryImpl(
                    name = "databinding_generated",
                    directoryProvider = creationConfig.artifacts.get(
                        InternalArtifactType.DATA_BINDING_TRIGGER
                    ),
                )
            )
            setDataBindingAnnotationProcessorParams(creationConfig)
        }
    }

    private fun setDataBindingAnnotationProcessorParams(
        creationConfig: ComponentCreationConfig) {
        val processorOptions = creationConfig.javaCompilation.annotationProcessor

        val dataBindingArgs = createArguments(
            creationConfig,
            logger.isDebugEnabled,
            DataBindingBuilder.getPrintMachineReadableOutput(),
            isKotlinKaptPluginApplied(project),
            getProjectKotlinPluginKotlinVersion(project))

        // add it the Variant API objects, this is what our tasks use
        processorOptions.argumentProviders.add(dataBindingArgs)
    }

    /**
     * Creates the final packaging task, and optionally the zipalign task (if the variant is signed)
     */
    fun createPackagingTask(creationConfig: ApkCreationConfig) {
        // ApkVariantData variantData = (ApkVariantData) variantScope.getVariantData();
        val taskContainer = creationConfig.taskContainer
        val signedApk = creationConfig.signingConfigImpl?.isSigningReady() ?: false

        /*
         * PrePackaging step class that will look if the packaging of the main FULL_APK split is
         * necessary when running in InstantRun mode. In InstantRun mode targeting an api 23 or
         * above device, resources are packaged in the main split FULL_APK. However when a warm swap
         * is possible, it is not necessary to produce immediately the new main SPLIT since the
         * runtime use the resources.ap_ file directly. However, as soon as an incompatible change
         * forcing a cold swap is triggered, the main FULL_APK must be rebuilt (even if the
         * resources were changed in a previous build).
         */
        val manifestType: InternalArtifactType<Directory> =
            creationConfig.global.manifestArtifactType
        val manifests = creationConfig.artifacts.get(manifestType)

        // Common code for both packaging tasks.
        val configureResourcesAndAssetsDependencies = Action { task: Task ->
            task.dependsOn(taskContainer.mergeAssetsTask)
            if (taskContainer.processAndroidResTask != null) {
                task.dependsOn(taskContainer.processAndroidResTask!!)
            }
        }
        taskFactory.register(
            PackageApplication.CreationAction(
                creationConfig,
                creationConfig.paths.apkLocation,
                manifests,
                manifestType),
            null,
            object : TaskConfigAction<PackageApplication> {
                override fun configure(task: PackageApplication) {
                    task.dependsOn(taskContainer.javacTask)
                    if (taskContainer.packageSplitResourcesTask != null) {
                        task.dependsOn(taskContainer.packageSplitResourcesTask!!)
                    }
                    if (taskContainer.packageSplitAbiTask != null) {
                        task.dependsOn(taskContainer.packageSplitAbiTask!!)
                    }
                    configureResourcesAndAssetsDependencies.execute(task)
                }
            },
            null)

        // create the listing file redirect
        taskFactory.register(
            ListingFileRedirectTask.CreationAction(
                creationConfig = creationConfig,
                taskSuffix = "Apk",
                inputArtifactType = InternalArtifactType.APK_IDE_MODEL,
                outputArtifactType = InternalArtifactType.APK_IDE_REDIRECT_FILE
            )
        )

        taskContainer
            .assembleTask
            .configure { task: Task ->
                task.dependsOn(
                    creationConfig.artifacts.get(SingleArtifact.APK),
                )
            }


        // create install task for the variant Data. This will deal with finding the
        // right output if there are more than one.
        // Add a task to install the application package
        if (signedApk) {
            createInstallTask(creationConfig)
        }

        // add an uninstall task
//        val uninstallTask = taskFactory.register(UninstallTask.CreationAction(creationConfig))
//        taskFactory.configure(UNINSTALL_ALL) { uninstallAll: Task ->
//            uninstallAll.dependsOn(uninstallTask)
//        }
    }

    protected open fun createInstallTask(creationConfig: ApkCreationConfig) {
            taskFactory.register(InstallVariantTask.CreationAction(creationConfig))
    }

    protected fun createValidateSigningTask(creationConfig: ApkCreationConfig) {
        if (creationConfig.signingConfigImpl?.isSigningReady() != true) {
            return
        }

        val service: Provider<AndroidLocationsBuildService> =
            getBuildService(
                creationConfig.services.buildServiceRegistry,
                AndroidLocationsBuildService::class.java
            )

        // FIXME create one per signing config instead of one per variant.
        taskFactory.register(
            ValidateSigningTask.CreationAction(
                creationConfig,
                service.get().getDefaultDebugKeystoreLocation()
            ))
    }

    fun createAnchorTasks(creationConfig: ComponentCreationConfig) {
        createVariantPreBuildTask(creationConfig)

        // also create sourceGenTask
        creationConfig
            .taskContainer
            .sourceGenTask = taskFactory.register(
            creationConfig.computeTaskName("generate", "Sources")
        ) { task: Task ->
            task.dependsOn(COMPILE_LINT_CHECKS_TASK)
            if (creationConfig.componentType.isAar) {
//                task.dependsOn(PrepareLintJarForPublish.NAME)
                TODO()
            }
            creationConfig.oldVariantApiLegacySupport?.variantData?.extraGeneratedResFolders?.let {
                task.dependsOn(it)
            }
        }

        // and resGenTask
        creationConfig
            .taskContainer
            .resourceGenTask = taskFactory.register(
            creationConfig.computeTaskName("generate", "Resources"))
        creationConfig
            .taskContainer
            .assetGenTask =
            taskFactory.register(creationConfig.computeTaskName("generate", "Assets"))

        // Create anchor task for creating instrumentation test coverage reports
        if (creationConfig is VariantCreationConfig && creationConfig.isAndroidTestCoverageEnabled) {
            creationConfig
                .taskContainer
                .coverageReportTask = taskFactory.register(
                creationConfig.computeTaskName("create", "CoverageReport")
            ) { task: Task ->
                task.group = JavaBasePlugin.VERIFICATION_GROUP
                task.description = String.format(
                    "Creates instrumentation test coverage reports for the %s variant.",
                    creationConfig.name)
            }
        }

        // and compile task
        createCompileAnchorTask(creationConfig)
    }

    private fun createCompileAnchorTask(creationConfig: ComponentCreationConfig) {
        val taskContainer = creationConfig.taskContainer
        taskContainer.compileTask = taskFactory.register(
            creationConfig.computeTaskName("compile", "Sources")
        ) { task: Task -> task.group = BUILD_GROUP }
    }

    private fun createAssembleTask(component: ComponentCreationConfig) {
        taskFactory.register(
            component.computeTaskName("assemble"),
            null /*preConfigAction*/,
            object : TaskConfigAction<Task> {
                override fun configure(task: Task) {
                    task.description =
                        "Assembles main output for variant " + component.name
                }

            },
            object : TaskProviderCallback<Task> {
                override fun handleProvider(taskProvider: TaskProvider<Task>) {
                    component.taskContainer.assembleTask =
                        taskProvider
                }
            }
        )
    }

    private fun createBundleTask(component: ComponentCreationConfig) {

    }

    // This is for config attribute debugging
    open class ConfigAttrTask : DefaultTask() {
        @get:Internal
        var consumable = false
        @get:Internal
        var resolvable = false
        @TaskAction
        fun run() {
            for (config in project.configurations) {
                val attributes = config.attributes
                if (consumable && config.isCanBeConsumed
                    || resolvable && config.isCanBeResolved) {
                    println(config.name)
                    println("\tcanBeResolved: " + config.isCanBeResolved)
                    println("\tcanBeConsumed: " + config.isCanBeConsumed)
                    for (attr in attributes.keySet()) {
                        println(
                            "\t" + attr.name + ": " + attributes.getAttribute(attr))
                    }
                    if (consumable && config.isCanBeConsumed) {
                        for (artifact in config.artifacts) {
                            println("\tArtifact: " + artifact.name + " (" + artifact.file.name + ")")
                        }
                        for (cv in config.outgoing.variants) {
                            println("\tConfigurationVariant: " + cv.name)
                            for (pa in cv.artifacts) {
                                println("\t\tArtifact: " + pa.file)
                                println("\t\tType:" + pa.type)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {

        private const val MULTIDEX_VERSION = "1.0.2"
        private const val COM_ANDROID_SUPPORT_MULTIDEX =
            "com.android.support:multidex:" + MULTIDEX_VERSION
        private val ANDROIDX_MULTIDEX_MULTIDEX by lazy {
            androidXMappings.getValue("com.android.support:multidex")
        }
        private const val COM_ANDROID_SUPPORT_MULTIDEX_INSTRUMENTATION =
            "com.android.support:multidex-instrumentation:" + MULTIDEX_VERSION
        private val ANDROIDX_MULTIDEX_MULTIDEX_INSTRUMENTATION by lazy {
            androidXMappings.getValue("com.android.support:multidex-instrumentation")
        }

        // name of the task that triggers compilation of the custom lint Checks
        private const val COMPILE_LINT_CHECKS_TASK = "compileLintChecks"
        const val INSTALL_GROUP = "Install"
        const val BUILD_GROUP = BasePlugin.BUILD_GROUP
        const val ANDROID_GROUP = "Android"
        const val FEATURE_SUFFIX = "Feature"

        // Task names. These cannot be AndroidTasks as in the component model world there is nothing to
        // force generateTasksBeforeEvaluate to happen before the variant tasks are created.
        const val MAIN_PREBUILD = "preBuild"
        const val UNINSTALL_ALL = "uninstallAll"
        const val DEVICE_CHECK = "deviceCheck"
        const val DEVICE_ANDROID_TEST = BuilderConstants.DEVICE + ComponentType.ANDROID_TEST_SUFFIX
        const val CONNECTED_CHECK = "connectedCheck"
        const val ALL_DEVICES_CHECK = "allDevicesCheck"
        const val CONNECTED_ANDROID_TEST =
            BuilderConstants.CONNECTED + ComponentType.ANDROID_TEST_SUFFIX
        const val ASSEMBLE_ANDROID_TEST = "assembleAndroidTest"
        const val LINT = "lint"
        const val LINT_FIX = "lintFix"

        // Temporary static variables for Kotlin+Compose configuration
        const val KOTLIN_COMPILER_CLASSPATH_CONFIGURATION_NAME = "kotlinCompilerClasspath"
        const val COMPOSE_KOTLIN_COMPILER_EXTENSION_VERSION = "1.2.0"
        const val CREATE_MOCKABLE_JAR_TASK_NAME = "createMockableJar"

        /**
         * Create tasks before the evaluation (on plugin apply). This is useful for tasks that could be
         * referenced by custom build logic.
         *
         * @param componentType the main variant type as returned by the [     ]
         * @param sourceSetContainer the container of source set from the DSL.
         */
        @JvmStatic
        fun createTasksBeforeEvaluate(
            project: Project,
            componentType: ComponentType,
            sourceSetContainer: Iterable<AndroidSourceSet?>,
            globalConfig: GlobalTaskCreationConfig
        )  {
            val taskFactory = TaskFactoryImpl(project.tasks)
            taskFactory.register(
                UNINSTALL_ALL
            ) { uninstallAllTask: Task ->
                uninstallAllTask.description = "Uninstall all applications."
                uninstallAllTask.group = INSTALL_GROUP
            }
            taskFactory.register(
                DEVICE_CHECK
            ) { deviceCheckTask: Task ->
                deviceCheckTask.description =
                    "Runs all device checks using Device Providers and Test Servers."
                deviceCheckTask.group = JavaBasePlugin.VERIFICATION_GROUP
            }
//            taskFactory.register(
//                CONNECTED_CHECK,
//                DeviceSerialTestTask::class.java
//            ) { connectedCheckTask: DeviceSerialTestTask ->
//                connectedCheckTask.description =
//                    "Runs all device checks on currently connected devices."
//                connectedCheckTask.group = JavaBasePlugin.VERIFICATION_GROUP
//            }
            // Make sure MAIN_PREBUILD runs first:
            taskFactory.register(MAIN_PREBUILD)
            taskFactory.register(ExtractProguardFiles.CreationAction(globalConfig))
                .configure { it: ExtractProguardFiles -> it.dependsOn(MAIN_PREBUILD) }
            taskFactory.register(SourceSetsTask.CreationAction(sourceSetContainer))
            taskFactory.register(
                ASSEMBLE_ANDROID_TEST
            ) { assembleAndroidTestTask: Task ->
                assembleAndroidTestTask.group = BasePlugin.BUILD_GROUP
                assembleAndroidTestTask.description = "Assembles all the Test applications."
            }

            // for testing only.
            taskFactory.register(
                "resolveConfigAttr",
                ConfigAttrTask::class.java) { task: ConfigAttrTask -> task.resolvable = true }
            taskFactory.register(
                "consumeConfigAttr",
                ConfigAttrTask::class.java) { task: ConfigAttrTask -> task.consumable = true }
            createCoreLibraryDesugaringConfig(project)
        }

        fun createCoreLibraryDesugaringConfig(project: Project) {
            var coreLibraryDesugaring =
                project.configurations.findByName(VariantDependencies.CONFIG_NAME_CORE_LIBRARY_DESUGARING)
            if (coreLibraryDesugaring == null) {
                coreLibraryDesugaring =
                    project.configurations.create(VariantDependencies.CONFIG_NAME_CORE_LIBRARY_DESUGARING)
                coreLibraryDesugaring.isVisible = false
                coreLibraryDesugaring.isCanBeConsumed = false
                coreLibraryDesugaring.description = "Configuration to desugar libraries"
            }
        }

        private fun generatesProguardOutputFile(creationConfig: ComponentCreationConfig): Boolean {
            return ((creationConfig is ConsumableCreationConfig
                    && creationConfig.minifiedEnabled)
                    || creationConfig.componentType.isDynamicFeature)
        }

        /** Makes the given task the one used by top-level "compile" task.  */
        @JvmStatic
        fun setJavaCompilerTask(
            javaCompilerTask: TaskProvider<out JavaCompile>,
            creationConfig: ComponentCreationConfig) {
            creationConfig.taskContainer.compileTask.dependsOn(javaCompilerTask)
        }

        /**
         * Method to reliably generate matching feature file names when dex splitter is used.
         *
         * @param modulePath the gradle module path for the feature
         * @param fileExtension the desired file extension (e.g., ".jar"), or null if no file extension
         * (e.g., for a folder)
         * @return name of file
         */
        fun getFeatureFileName(
            modulePath: String, fileExtension: String?): String {
            val featureName = getFeatureName(modulePath)
            val sanitizedFeatureName = if (":" == featureName) "" else featureName
            // Prepend "feature-" to fileName in case a non-base module has module path ":base".
            return "feature-" + sanitizedFeatureName + Strings.nullToEmpty(fileExtension)
        }
    }
}
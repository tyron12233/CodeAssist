package com.tyron.builder.gradle.tasks

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestMerger2.Invoker
import com.android.manifmerger.ManifestMerger2.WEAR_APP_SUB_MANIFEST
import com.android.manifmerger.ManifestProvider
import com.android.utils.FileUtils
import com.google.common.base.Preconditions
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.api.variant.impl.VariantOutputImpl
import com.tyron.builder.api.variant.impl.getApiString
import com.tyron.builder.dexing.DexingType
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.component.DynamicFeatureCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.dependency.ArtifactCollectionWithExtraArtifact.ExtraComponentIdentifier
import com.tyron.builder.gradle.internal.ide.dependencies.getIdString
import com.tyron.builder.gradle.internal.profile.ProfilingMode
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactScope
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.MANIFEST_MERGE_REPORT
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.NAVIGATION_JSON
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.manifest.ManifestProviderImpl
import com.tyron.builder.gradle.internal.tasks.manifest.mergeManifests
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.StringOption
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.util.*

/** A task that processes the manifest  */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST)
abstract class ProcessApplicationManifest : ManifestProcessorTask() {

    private var manifests: ArtifactCollection? = null
    private var featureManifests: ArtifactCollection? = null

    /** The merged Manifests files folder.  */
    @get:OutputFile
    abstract val mergedManifest: RegularFileProperty

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    @get:InputFiles
    var dependencyFeatureNameArtifacts: FileCollection? = null
        private set

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    @get:InputFiles
    abstract val microApkManifest: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val baseModuleDebuggable: Property<Boolean>

    @get:Optional
    @get:Input
    abstract val packageOverride: Property<String>

    @get:Input
    abstract val namespace: Property<String>

    @get:Input
    abstract val profileable: Property<Boolean>

    @get:Input
    abstract val testOnly: Property<Boolean>

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    abstract val manifestOverlays: ListProperty<File>

    @get:Optional
    @get:Input
    abstract val manifestPlaceholders: MapProperty<String, Any>

    private var isFeatureSplitVariantType = false
    private var buildTypeName: String? = null

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    @get:Optional
    var navigationJsons: FileCollection? = null
        private set

    @Throws(IOException::class)

    override fun doTaskAction() {
        if (baseModuleDebuggable.isPresent) {
            val isDebuggable = optionalFeatures.get()
                .contains(Invoker.Feature.DEBUGGABLE)
            if (baseModuleDebuggable.get() != isDebuggable) {
                val errorMessage = String.format(
                    "Dynamic Feature '%1\$s' (build type '%2\$s') %3\$s debuggable,\n"
                            + "and the corresponding build type in the base "
                            + "application %4\$s debuggable.\n"
                            + "Recommendation: \n"
                            + "   in  %5\$s\n"
                            + "   set android.buildTypes.%2\$s.debuggable = %6\$s",
                    projectPath.get(),
                    buildTypeName,
                    if (isDebuggable) "is" else "is not",
                    if (baseModuleDebuggable.get()) "is" else "is not",
                    projectBuildFile.get().asFile,
                    if (baseModuleDebuggable.get()) "true" else "false"
                )
                throw InvalidUserDataException(errorMessage)
            }
        }
        val navJsons = navigationJsons?.files ?: setOf()

        val mergingReport = mergeManifests(
            mainManifest.get(),
            manifestOverlays.get(),
            computeFullProviderList(),
            navJsons,
            featureName.orNull,
            packageOverride.get(),
            namespace.get(),
            profileable.get(),
            variantOutput.get().versionCode.orNull,
            variantOutput.get().versionName.orNull,
            minSdkVersion.orNull,
            targetSdkVersion.orNull,
            maxSdkVersion.orNull,
            testOnly.get(),
            mergedManifest.get().asFile.absolutePath /* aaptFriendlyManifestOutputFile */,
            null /* outAaptSafeManifestLocation */,
            ManifestMerger2.MergeType.APPLICATION,
            manifestPlaceholders.get(),
            optionalFeatures.get().plus(
                mutableListOf<Invoker.Feature>().also {
                    if (!jniLibsUseLegacyPackaging.get()) {
                        it.add(Invoker.Feature.DO_NOT_EXTRACT_NATIVE_LIBS)
                    }
                }
            ),
            dependencyFeatureNames,
            reportFile.get().asFile,
            LoggerWrapper.getLogger(ProcessApplicationManifest::class.java)
        )
        outputMergeBlameContents(mergingReport, mergeBlameFile.get().asFile)
    }

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    abstract val mainManifest: Property<File>

    /**
     * Compute the final list of providers based on the manifest file collection and the other
     * providers.
     *
     * @return the list of providers.
     */
    private fun computeFullProviderList(): List<ManifestProvider> {
        val artifacts = manifests!!.artifacts
        val providers = mutableListOf<ManifestProvider>()
        for (artifact in artifacts) {
            providers.add(
                    ManifestProviderImpl(
                            artifact.file,
                            getArtifactName(artifact)
                    )
            )
        }
        if (microApkManifest.isPresent) {
            // this is now always present if embedding is enabled, but it doesn't mean
            // anything got embedded so the file may not run (the file path exists and is
            // returned by the FC but the file doesn't exist.
            val microManifest = microApkManifest.get().asFile
            if (microManifest.isFile) {
                providers.add(
                        ManifestProviderImpl(
                                microManifest, WEAR_APP_SUB_MANIFEST
                        )
                )
            }
        }

        if (featureManifests != null) {
            providers.addAll(computeProviders(featureManifests!!.artifacts))
        }
        return providers
    }

    // Only feature splits can have feature dependencies
    private val dependencyFeatureNames: List<String>
        get() {
            val list: MutableList<String> = ArrayList()
            return if (!isFeatureSplitVariantType) { // Only feature splits can have feature dependencies
                list
            } else try {
                for (file in dependencyFeatureNameArtifacts!!.files) {
                    list.add(org.apache.commons.io.FileUtils.readFileToString(file))
                }
                list
            } catch (e: IOException) {
                throw UncheckedIOException("Could not load feature declaration", e)
            }
        }

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val componentType: Property<String?>

    @get:Optional
    @get:Input
    abstract val minSdkVersion: Property<String?>

    @get:Optional
    @get:Input
    abstract val targetSdkVersion: Property<String?>

    @get:Optional
    @get:Input
    abstract val maxSdkVersion: Property<Int?>

    @get:Input
    abstract val optionalFeatures: SetProperty<Invoker.Feature>

    @get:Input
    abstract val jniLibsUseLegacyPackaging: Property<Boolean>

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getManifests(): FileCollection {
        return manifests!!.artifactFiles
    }

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getFeatureManifests(): FileCollection? {
        return if (featureManifests == null) {
            null
        } else featureManifests!!.artifactFiles
    }

    @get:Optional
    @get:Input
    abstract val featureName: Property<String>

    @get:Internal("only for task execution")
    abstract val projectBuildFile: RegularFileProperty

    @get:Nested
    abstract val variantOutput: Property<VariantOutputImpl>

    class CreationAction(
        creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<ProcessApplicationManifest, ApkCreationConfig>(creationConfig) {
        override val name: String
            get() = computeTaskName("process", "MainManifest")

        override val type: Class<ProcessApplicationManifest>
            get() = ProcessApplicationManifest::class.java

        override fun preConfigure(
            taskName: String
        ) {
            super.preConfigure(taskName)
            val componentType = creationConfig.componentType
            Preconditions.checkState(!componentType.isTestComponent)
            val artifacts = creationConfig.artifacts
            artifacts.republish(
                InternalArtifactType.PACKAGED_MANIFESTS,
                InternalArtifactType.MANIFEST_METADATA
            )
        }

        override fun handleProvider(
            taskProvider: TaskProvider<ProcessApplicationManifest>
        ) {
            super.handleProvider(taskProvider)
            val artifacts = creationConfig.artifacts
            artifacts.setInitialProvider(
                taskProvider,
                ProcessApplicationManifest::mergedManifest
            )
                .on(SingleArtifact.MERGED_MANIFEST)

            artifacts.setInitialProvider(
                taskProvider,
                ManifestProcessorTask::mergeBlameFile
            )
                .withName("manifest-merger-blame-" + creationConfig.baseName + "-report.txt")
                .on(InternalArtifactType.MANIFEST_MERGE_BLAME_FILE)
            artifacts.setInitialProvider(
                taskProvider,
                ProcessApplicationManifest::reportFile
            )
                .atLocation(
                    FileUtils.join(
                        creationConfig.services.projectInfo.getOutputsDir(),
                        "logs"
                    )
                        .absolutePath
                )
                .withName("manifest-merger-" + creationConfig.baseName + "-report.txt")
                .on(MANIFEST_MERGE_REPORT)
        }

        override fun configure(
            task: ProcessApplicationManifest
        ) {
            super.configure(task)
            val variantSources = creationConfig.variantSources
            val componentType = creationConfig.componentType
            // This includes the dependent libraries.
            task.manifests = creationConfig
                .variantDependencies
                .getArtifactCollection(
                    ConsumedConfigType.RUNTIME_CLASSPATH,
                    ArtifactScope.ALL,
                    AndroidArtifacts.ArtifactType.MANIFEST
                )
            // optional manifest files too.
            if (creationConfig.taskContainer.microApkTask != null
                && creationConfig.embedsMicroApp
            ) {
                creationConfig.artifacts.setTaskInputToFinalProduct(
                        InternalArtifactType.MICRO_APK_MANIFEST_FILE,
                        task.microApkManifest
                )
            }

            task.applicationId.set(creationConfig.applicationId)
            task.applicationId.disallowChanges()
            task.componentType.set(creationConfig.componentType.toString())
            task.componentType.disallowChanges()
            task.minSdkVersion.setDisallowChanges(creationConfig.minSdkVersion.getApiString())
            task.targetSdkVersion
                .setDisallowChanges(
                        if (creationConfig.targetSdkVersion.apiLevel < 1)
                            null
                        else creationConfig.targetSdkVersion.getApiString()
                )
            task.maxSdkVersion.setDisallowChanges(
                (creationConfig as VariantCreationConfig).maxSdkVersion
            )
            task.optionalFeatures.setDisallowChanges(creationConfig.services.provider {
                getOptionalFeatures(
                    creationConfig
                )
            })
            task.jniLibsUseLegacyPackaging.setDisallowChanges(
                creationConfig.packaging.jniLibs.useLegacyPackaging
            )
            task.variantOutput.setDisallowChanges(
                creationConfig.outputs.getMainSplit()
            )
            // set optional inputs per module type
            if (componentType.isBaseModule) {
                task.featureManifests = creationConfig
                    .variantDependencies
                    .getArtifactCollection(
                        ConsumedConfigType.REVERSE_METADATA_VALUES,
                        ArtifactScope.PROJECT,
                        AndroidArtifacts.ArtifactType.REVERSE_METADATA_FEATURE_MANIFEST
                    )
            } else if (componentType.isDynamicFeature) {
                val dfCreationConfig =
                    creationConfig as DynamicFeatureCreationConfig
                task.featureName.setDisallowChanges(dfCreationConfig.featureName)
                task.baseModuleDebuggable.setDisallowChanges(dfCreationConfig.baseModuleDebuggable)
                task.dependencyFeatureNameArtifacts = creationConfig
                    .variantDependencies
                    .getArtifactFileCollection(
                        ConsumedConfigType.RUNTIME_CLASSPATH,
                        ArtifactScope.PROJECT,
                        AndroidArtifacts.ArtifactType.FEATURE_NAME
                    )
            }
            if (!creationConfig.global.namespacedAndroidResources) {
                task.navigationJsons = creationConfig.services.fileCollection(
                    creationConfig.artifacts.get(NAVIGATION_JSON),
                    creationConfig
                        .variantDependencies
                        .getArtifactFileCollection(
                            ConsumedConfigType.RUNTIME_CLASSPATH,
                            ArtifactScope.ALL,
                            AndroidArtifacts.ArtifactType.NAVIGATION_JSON
                        )
                )
            }
            task.packageOverride.setDisallowChanges(creationConfig.applicationId)
            task.namespace.setDisallowChanges(creationConfig.namespace)
            task.profileable.setDisallowChanges(
                (creationConfig as? ApplicationCreationConfig)?.profileable ?: false
            )
            task.testOnly.setDisallowChanges(
                ProfilingMode.getProfilingModeType(
                    creationConfig.services.projectOptions[StringOption.PROFILING_MODE]
                ) != ProfilingMode.UNDEFINED
            )
            task.manifestPlaceholders.set(creationConfig.manifestPlaceholders)
            task.manifestPlaceholders.disallowChanges()
            task.mainManifest.setDisallowChanges(creationConfig.services.provider(variantSources::mainManifestFilePath))
            task.manifestOverlays.setDisallowChanges(
                task.project.provider(variantSources::manifestOverlays)
            )
            task.isFeatureSplitVariantType = creationConfig.componentType.isDynamicFeature
            task.buildTypeName = creationConfig.buildType
            task.projectBuildFile.set(task.project.buildFile)
            task.projectBuildFile.disallowChanges()
            // TODO: here in the "else" block should be the code path for the namespaced pipeline
        }

    }

    companion object {
        private fun computeProviders(
            artifacts: Set<ResolvedArtifactResult>
        ): List<ManifestProvider> {
            val providers: MutableList<ManifestProvider> = mutableListOf()
            for (artifact in artifacts) {
                providers.add(
                    ManifestProviderImpl(
                        artifact.file,
                        getArtifactName(artifact)
                    )
                )
            }
            return providers
        }

        // TODO put somewhere else?
        @JvmStatic
        fun getArtifactName(artifact: ResolvedArtifactResult): String {
            return when(val id = artifact.id.componentIdentifier) {
                is ProjectComponentIdentifier -> id.getIdString()
                is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                is OpaqueComponentArtifactIdentifier -> id.displayName
                is ExtraComponentIdentifier -> id.displayName
                else -> throw RuntimeException("Unsupported type of ComponentIdentifier")
            }
        }

        private fun getOptionalFeatures(
            creationConfig: ApkCreationConfig
        ): EnumSet<Invoker.Feature> {
            val features: MutableList<Invoker.Feature> =
                ArrayList()
            val componentType = creationConfig.componentType
            if (componentType.isDynamicFeature) {
                features.add(Invoker.Feature.ADD_DYNAMIC_FEATURE_ATTRIBUTES)
            }
            if (creationConfig.testOnlyApk) {
                features.add(Invoker.Feature.TEST_ONLY)
            }
            if (creationConfig.debuggable) {
                features.add(Invoker.Feature.DEBUGGABLE)
                if (creationConfig.advancedProfilingTransforms.isNotEmpty()) {
                    features.add(Invoker.Feature.ADVANCED_PROFILING)
                }
            }
            if (creationConfig.dexingType === DexingType.LEGACY_MULTIDEX) {
                features.add(
                    if (creationConfig.services.projectOptions[BooleanOption.USE_ANDROID_X]) {
                        Invoker.Feature.ADD_ANDROIDX_MULTIDEX_APPLICATION_IF_NO_NAME
                    } else {
                        Invoker.Feature.ADD_SUPPORT_MULTIDEX_APPLICATION_IF_NO_NAME
                    })
            }
            if (creationConfig.services.projectOptions[BooleanOption.ENFORCE_UNIQUE_PACKAGE_NAMES]
            ) {
                features.add(Invoker.Feature.ENFORCE_UNIQUE_PACKAGE_NAME)
            }
            if (creationConfig.services.projectOptions[BooleanOption.DISABLE_MINSDKLIBRARY_CHECK]) {
                features.add(Invoker.Feature.DISABLE_MINSDKLIBRARY_CHECK)
            }
            return if (features.isEmpty())
                EnumSet.noneOf(Invoker.Feature::class.java)
            else EnumSet.copyOf(features)
        }
    }
}

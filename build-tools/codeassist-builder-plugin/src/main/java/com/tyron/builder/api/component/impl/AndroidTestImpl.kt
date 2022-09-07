package com.tyron.builder.api.component.impl

import com.tyron.builder.api.artifact.MultipleArtifact
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.component.impl.features.AndroidResourcesCreationConfigImpl
import com.tyron.builder.api.component.impl.features.BuildConfigCreationConfigImpl
import com.tyron.builder.api.component.impl.features.ManifestPlaceholdersCreationConfigImpl
import com.tyron.builder.api.component.impl.features.RenderscriptCreationConfigImpl
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar
import com.tyron.builder.api.variant.*
import com.tyron.builder.api.variant.impl.ApkPackagingImpl
import com.tyron.builder.api.variant.impl.ResValueKeyImpl
import com.tyron.builder.api.variant.impl.SigningConfigImpl
import com.tyron.builder.dexing.DexingType
import com.tyron.builder.gradle.internal.PostprocessingFeatures
import com.tyron.builder.gradle.internal.ProguardFileType
import com.tyron.builder.gradle.internal.component.AndroidTestCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.component.features.*
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.AndroidTestComponentDslInfo
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.Java8LangSupport
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.ProjectServices
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.variant.BaseVariantData
import com.tyron.builder.gradle.internal.variant.VariantPathHelper
import com.tyron.builder.gradle.options.IntegerOption
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.*
import java.io.Serializable
import javax.inject.Inject

open class AndroidTestImpl @Inject constructor(
    componentIdentity: ComponentIdentity,
    buildFeatureValues: BuildFeatureValues,
    dslInfo: AndroidTestComponentDslInfo,
    variantDependencies: VariantDependencies,
    variantSources: VariantSources,
    paths: VariantPathHelper,
    artifacts: ArtifactsImpl,
    variantData: BaseVariantData,
    taskContainer: MutableTaskContainer,
    mainVariant: VariantCreationConfig,
    transformManager: TransformManager,
    variantServices: VariantServices,
    taskCreationServices: TaskCreationServices,
    global: GlobalTaskCreationConfig,
) : TestComponentImpl<AndroidTestComponentDslInfo>(
    componentIdentity,
    buildFeatureValues,
    dslInfo,
    variantDependencies,
    variantSources,
    paths,
    artifacts,
    variantData,
    taskContainer,
    mainVariant,
    transformManager,
    variantServices,
    taskCreationServices,
    global,
), AndroidTest, AndroidTestCreationConfig {

    init {
        dslInfo.multiDexKeepProguard?.let {
            artifacts.getArtifactContainer(MultipleArtifact.MULTIDEX_KEEP_PROGUARD)
                .addInitialProvider(null, internalServices.toRegularFileProvider(it))
        }
    }

    private val delegate by lazy {
        AndroidTestCreationConfigImpl(
            this, dslInfo
        )
    }

    // ---------------------------------------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------------------------------------

    override val debuggable: Boolean
        get() = dslInfo.isDebuggable

    override val namespaceForR: Provider<String> = dslInfo.namespaceForR

    override val minSdkVersion: AndroidVersion
        get() = mainVariant.minSdkVersion

    override val targetSdkVersion: AndroidVersion
        get() = mainVariant.targetSdkVersion

    override val applicationId: Property<String> = internalServices.propertyOf(
        String::class.java,
        dslInfo.applicationId
    )

    override val androidResources: AndroidResources by lazy {
        getAndroidResources()
    }

    override val pseudoLocalesEnabled: Property<Boolean> by lazy {
        androidResourcesCreationConfig.pseudoLocalesEnabled
    }

    override val packaging: ApkPackaging by lazy {
        ApkPackagingImpl(
            dslInfo.mainVariantDslInfo.packaging,
            variantServices,
            minSdkVersion.apiLevel
        )
    }

    override val minifiedEnabled: Boolean
        get() {
            return when {
                mainVariant.componentType.isAar -> false
                !dslInfo.postProcessingOptions.hasPostProcessingConfiguration() ->
                    mainVariant.minifiedEnabled
                else -> dslInfo.postProcessingOptions.codeShrinkerEnabled()
            }
        }

    override val resourcesShrink: Boolean
        get() {
            return when {
                mainVariant.componentType.isAar -> false
                !dslInfo.postProcessingOptions.hasPostProcessingConfiguration() ->
                    mainVariant.resourcesShrink
                else -> dslInfo.postProcessingOptions.resourcesShrinkingEnabled()
            }
        }

    override val instrumentationRunner: Property<String> =
        internalServices.propertyOf(
            String::class.java,
            dslInfo.getInstrumentationRunner(dexingType)
        )

    override val handleProfiling: Property<Boolean> =
        internalServices.propertyOf(Boolean::class.java, dslInfo.handleProfiling)

    override val functionalTest: Property<Boolean> =
        internalServices.propertyOf(Boolean::class.java, dslInfo.functionalTest)

    override val testLabel: Property<String?> =
        internalServices.nullablePropertyOf(String::class.java, dslInfo.testLabel)

    override val buildConfigFields: MapProperty<String, BuildConfigField<out Serializable>> by lazy {
        buildConfigCreationConfig?.buildConfigFields
            ?: warnAboutAccessingVariantApiValueForDisabledFeature(
                featureName = FeatureNames.BUILD_CONFIG,
                apiName = "buildConfigFields",
                value = internalServices.mapPropertyOf(
                    String::class.java,
                    BuildConfigField::class.java,
                    dslInfo.getBuildConfigFields()
                )
            )
    }

    override val signingConfig: SigningConfig?
        get() = signingConfigImpl

    override val signingConfigImpl: SigningConfigImpl? by lazy {
        dslInfo.signingConfig?.let {
            SigningConfigImpl(
                it,
                variantServices,
                minSdkVersion.apiLevel,
                services.projectOptions.get(IntegerOption.IDE_TARGET_DEVICE_API)
            )
        }
    }

    override val renderscript: Renderscript? by lazy {
        renderscriptCreationConfig?.renderscript
    }

    override val proguardFiles: ListProperty<RegularFile> by lazy {
        variantServices.listPropertyOf(RegularFile::class.java) {
            val projectDir = services.projectInfo.projectDirectory
            it.addAll(
                dslInfo.gatherProguardFiles(ProguardFileType.TEST).map { file ->
                    projectDir.file(file.absolutePath)
                }
            )
        }
    }

    override fun makeResValueKey(type: String, name: String): ResValue.Key =
        ResValueKeyImpl(type, name)

    override val resValues: MapProperty<ResValue.Key, ResValue> by lazy {
        resValuesCreationConfig?.resValues
            ?: warnAboutAccessingVariantApiValueForDisabledFeature(
                featureName = FeatureNames.RES_VALUES,
                apiName = "resValues",
                value = internalServices.mapPropertyOf(
                    ResValue.Key::class.java,
                    ResValue::class.java,
                    dslInfo.androidResourcesDsl!!.getResValues()
                )
            )
    }

    override val manifestPlaceholders: MapProperty<String, String>
        get() = manifestPlaceholdersCreationConfig.placeholders

    // ---------------------------------------------------------------------------------------------
    // INTERNAL API
    // ---------------------------------------------------------------------------------------------

    // Even if android resources is disabled in a library project, we still need to merge and link
    // external resources to create the test apk.
    override val androidResourcesCreationConfig: AndroidResourcesCreationConfig by lazy(LazyThreadSafetyMode.NONE) {
        AndroidResourcesCreationConfigImpl(
            this,
            dslInfo,
            dslInfo.androidResourcesDsl!!,
            internalServices,
        )
    }

    override val buildConfigCreationConfig: BuildConfigCreationConfig? by lazy(LazyThreadSafetyMode.NONE) {
        if (buildFeatures.buildConfig) {
            BuildConfigCreationConfigImpl(
                this,
                dslInfo,
                internalServices
            )
        } else {
            null
        }
    }

    override val renderscriptCreationConfig: RenderscriptCreationConfig? by lazy(LazyThreadSafetyMode.NONE) {
        if (buildFeatures.renderScript) {
            RenderscriptCreationConfigImpl(
                dslInfo,
                internalServices,
                renderscriptTargetApi = mainVariant.renderscriptCreationConfig!!.renderscriptTargetApi
            )
        } else {
            null
        }
    }

    override val manifestPlaceholdersCreationConfig: ManifestPlaceholdersCreationConfig by lazy(LazyThreadSafetyMode.NONE) {
        ManifestPlaceholdersCreationConfigImpl(
            dslInfo,
            internalServices
        )
    }

    override val targetSdkVersionOverride: AndroidVersion?
        get() = mainVariant.targetSdkVersionOverride

    // always false for this type
    override val embedsMicroApp: Boolean
        get() = false

    // always true for this kind
    override val testOnlyApk: Boolean
        get() = true

    override val testedApplicationId: Provider<String>
        get() = if (mainVariant.componentType.isAar) {
            // if the tested variant is an AAR, the test is self contained and therefore
            // testedAppID == appId
            applicationId
        } else {
            mainVariant.applicationId
        }

    override val instrumentationRunnerArguments: Map<String, String>
        get() = dslInfo.instrumentationRunnerArguments

    /**
     * Package desugar_lib DEX for base feature androidTest only if the base packages shrunk
     * desugar_lib. This should be fixed properly by analyzing the test code when generating L8
     * keep rules, and thus packaging desugar_lib dex in the tested APK only which contains
     * necessary classes used both in test and tested APKs.
     */
    override val shouldPackageDesugarLibDex: Boolean
        get() = when {
            !isCoreLibraryDesugaringEnabled -> false
            mainVariant.componentType.isAar -> true
            else -> mainVariant.componentType.isBaseModule && needsShrinkDesugarLibrary
        }

    override val minSdkVersionForDexing: AndroidVersion =
        mainVariant.minSdkVersionForDexing

    override val isMultiDexEnabled: Boolean =
        mainVariant.isMultiDexEnabled

    override val needsShrinkDesugarLibrary: Boolean
        get() = delegate.needsShrinkDesugarLibrary

    override val isCoreLibraryDesugaringEnabled: Boolean
        get() = delegate.isCoreLibraryDesugaringEnabled

    override val dexingType: DexingType
        get() = delegate.dexingType

    override val needsMainDexListForBundle: Boolean
        get() = false
//
    override fun <T : Component> createUserVisibleVariantObject(
        projectServices: ProjectServices,
        operationsRegistrar: VariantApiOperationsRegistrar<out CommonExtension<*, *, *, *>, out VariantBuilder, out Variant>,
        stats: Any?
    ): T =
        if (stats == null) {
            this as T
        } else {
            TODO()
//            projectServices.objectFactory.newInstance(
//                AnalyticsEnabledAndroidTest::class.java,
//                this,
//                stats
//            ) as T
        }

    override val shouldPackageProfilerDependencies: Boolean = false

    override val advancedProfilingTransforms: List<String> = emptyList()

    override val needsMergedJavaResStream: Boolean =
        delegate.getNeedsMergedJavaResStream()

    override fun getJava8LangSupportType(): Java8LangSupport = delegate.getJava8LangSupportType()

    override val defaultGlslcArgs: List<String>
        get() = dslInfo.defaultGlslcArgs
    override val scopedGlslcArgs: Map<String, List<String>>
        get() = dslInfo.scopedGlslcArgs

    override val ignoredLibraryKeepRules: SetProperty<String>
        get() = internalServices.setPropertyOf(
            String::class.java,
            dslInfo.ignoredLibraryKeepRules
        )

    override val ignoreAllLibraryKeepRules: Boolean
        get() = dslInfo.ignoreAllLibraryKeepRules

    override val isAndroidTestCoverageEnabled: Boolean
        get() = dslInfo.isAndroidTestCoverageEnabled

    // Only instrument library androidTests. In app modules, the main classes are instrumented.
    override val useJacocoTransformInstrumentation: Boolean
        get() = dslInfo.isAndroidTestCoverageEnabled && mainVariant.componentType.isAar

    // Only include the jacoco agent if coverage is enabled in library test components
    // as in apps it will have already been included in the tested application.
    override val packageJacocoRuntime: Boolean
        get() = dslInfo.isAndroidTestCoverageEnabled && mainVariant.componentType.isAar

    override val postProcessingFeatures: PostprocessingFeatures?
        get() = dslInfo.postProcessingOptions.getPostprocessingFeatures()

    // ---------------------------------------------------------------------------------------------
    // DO NOT USE, Deprecated DSL APIs.
    // ---------------------------------------------------------------------------------------------

    override val multiDexKeepFile = dslInfo.multiDexKeepFile
}


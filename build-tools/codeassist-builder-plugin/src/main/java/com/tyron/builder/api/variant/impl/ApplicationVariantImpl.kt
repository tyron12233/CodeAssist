package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.artifact.MultipleArtifact
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.component.impl.*
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar
import com.tyron.builder.api.variant.*
import com.tyron.builder.dexing.DexingType
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.ApplicationVariantDslInfo
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.dsl.NdkOptions.DebugSymbolLevel
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.publishing.VariantPublishingInfo
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
import com.tyron.builder.gradle.options.StringOption
import org.gradle.api.provider.Property
import javax.inject.Inject

open class ApplicationVariantImpl @Inject constructor(
    override val variantBuilder: ApplicationVariantBuilderImpl,
    buildFeatureValues: BuildFeatureValues,
    dslInfo: ApplicationVariantDslInfo,
    variantDependencies: VariantDependencies,
    variantSources: VariantSources,
    paths: VariantPathHelper,
    artifacts: ArtifactsImpl,
    variantData: BaseVariantData,
    taskContainer: MutableTaskContainer,
    dependenciesInfoBuilder: DependenciesInfoBuilder,
    transformManager: TransformManager,
    internalServices: VariantServices,
    taskCreationServices: TaskCreationServices,
    globalTaskCreationConfig: GlobalTaskCreationConfig
) : VariantImpl<ApplicationVariantDslInfo>(
    variantBuilder,
    buildFeatureValues,
    dslInfo,
    variantDependencies,
    variantSources,
    paths,
    artifacts,
    variantData,
    taskContainer,
    transformManager,
    internalServices,
    taskCreationServices,
    globalTaskCreationConfig
), ApplicationVariant, ApplicationCreationConfig, HasAndroidTest, HasTestFixtures {

    override val isAndroidTestCoverageEnabled: Boolean
        get() = false

    override val externalNativeBuild: ExternalNativeBuild?
        by lazy { TODO() }

    val delegate by lazy { ApkCreationConfigImpl(
        this,
        dslInfo) }

    init {
        dslInfo.multiDexKeepProguard?.let {
            artifacts.getArtifactContainer(MultipleArtifact.MULTIDEX_KEEP_PROGUARD)
                .addInitialProvider(null, internalServices.toRegularFileProvider(it))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------------------------------------

    override val applicationId: Property<String> = dslInfo.applicationId

    override val embedsMicroApp: Boolean
        get() = dslInfo.isEmbedMicroApp

    override val dependenciesInfo: DependenciesInfo by lazy {
        DependenciesInfoImpl(
                dependenciesInfoBuilder.includedInApk,
                dependenciesInfoBuilder.includedInBundle
        )
    }

    override val androidResources: AndroidResources by lazy {
        getAndroidResources()
    }

    override val signingConfigImpl: SigningConfigImpl? by lazy {
        signingConfig
    }

    override val signingConfig: SigningConfigImpl by lazy {
        SigningConfigImpl(
            dslInfo.signingConfig,
            internalServices,
            minSdkVersion.apiLevel,
            internalServices.projectOptions.get(IntegerOption.IDE_TARGET_DEVICE_API)
        )
    }

    override val packaging: ApkPackaging by lazy {
        ApkPackagingImpl(
            dslInfo.packaging,
            internalServices,
            minSdkVersion.apiLevel
        )
    }

    override val publishInfo: VariantPublishingInfo?
        get() = dslInfo.publishInfo

    override val minifiedEnabled: Boolean
        get() = variantBuilder.isMinifyEnabled
    override val resourcesShrink: Boolean
        get() = variantBuilder.shrinkResources

    override var androidTest: AndroidTestImpl? = null

    override var testFixtures: TestFixturesImpl? = null

    override val renderscript: Renderscript? by lazy {
        renderscriptCreationConfig?.renderscript
    }

    override val isMinifyEnabled: Boolean
        get() = variantBuilder.isMinifyEnabled

    override val shrinkResources: Boolean
        get() = variantBuilder.shrinkResources

    // ---------------------------------------------------------------------------------------------
    // INTERNAL API
    // ---------------------------------------------------------------------------------------------

    override val testOnlyApk: Boolean
        get() = isTestApk()

    override val needAssetPackTasks: Boolean
        get() = global.assetPacks.isNotEmpty()

    override val shouldPackageDesugarLibDex: Boolean
        get() = delegate.isCoreLibraryDesugaringEnabled
    override val debuggable: Boolean
        get() = delegate.isDebuggable
    override val profileable: Boolean
        get() = dslInfo.isProfileable
    override val isCoreLibraryDesugaringEnabled: Boolean
        get() = delegate.isCoreLibraryDesugaringEnabled

    override val shouldPackageProfilerDependencies: Boolean
        get() = advancedProfilingTransforms.isNotEmpty()

    override val advancedProfilingTransforms: List<String>
        get() {
            return services.projectOptions[StringOption.IDE_ANDROID_CUSTOM_CLASS_TRANSFORMS]?.split(
                ","
            ) ?: emptyList()
        }

    override val nativeDebugSymbolLevel: DebugSymbolLevel
        get() {
            val debugSymbolLevelOrNull = null
//                NdkOptions.DEBUG_SYMBOL_LEVEL_CONVERTER.convert(
//                    dslInfo.ndkConfig.debugSymbolLevel
//                )
            return debugSymbolLevelOrNull ?: if (debuggable) DebugSymbolLevel.NONE else DebugSymbolLevel.SYMBOL_TABLE
        }

    // ---------------------------------------------------------------------------------------------
    // DO NOT USE, Deprecated DSL APIs.
    // ---------------------------------------------------------------------------------------------

    override val multiDexKeepFile = dslInfo.multiDexKeepFile

    // ---------------------------------------------------------------------------------------------
    // Private stuff
    // ---------------------------------------------------------------------------------------------

    override val consumesFeatureJars: Boolean =
        minifiedEnabled && global.hasDynamicFeatures

    override fun createVersionNameProperty(): Property<String?> =
        internalServices.newNullablePropertyBackingDeprecatedApi(
            String::class.java,
            dslInfo.versionName,
        )

    override fun createVersionCodeProperty() : Property<Int?> =
        internalServices.newNullablePropertyBackingDeprecatedApi(
            Int::class.java,
            dslInfo.versionCode,
        )

    override val dexingType: DexingType
        get() = delegate.dexingType

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
//                AnalyticsEnabledApplicationVariant::class.java,
//                this,
//                stats
//            ) as T
        }

    override val minSdkVersionForDexing: AndroidVersion
        get() = delegate.minSdkVersionForDexing

    override val needsMergedJavaResStream: Boolean = delegate.getNeedsMergedJavaResStream()

    override fun getJava8LangSupportType(): Java8LangSupport = delegate.getJava8LangSupportType()

    override val needsShrinkDesugarLibrary: Boolean
        get() = delegate.needsShrinkDesugarLibrary

    override val bundleConfig: BundleConfigImpl = BundleConfigImpl(
        CodeTransparencyImpl(
            global.bundleOptions.codeTransparency.signing,
        ),
        internalServices,
    )

    override val useJacocoTransformInstrumentation: Boolean
        get() = isAndroidTestCoverageEnabled

    // Apps include the jacoco agent if test coverage is enabled
    override val packageJacocoRuntime: Boolean
        get() = isAndroidTestCoverageEnabled

    override val isWearAppUnbundled: Boolean?
        get() = dslInfo.isWearAppUnbundled
}

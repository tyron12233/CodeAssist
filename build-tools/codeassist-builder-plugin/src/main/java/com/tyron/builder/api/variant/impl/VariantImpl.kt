package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.component.impl.ComponentImpl
import com.tyron.builder.api.component.impl.features.BuildConfigCreationConfigImpl
import com.tyron.builder.api.component.impl.features.ManifestPlaceholdersCreationConfigImpl
import com.tyron.builder.api.component.impl.features.RenderscriptCreationConfigImpl
import com.tyron.builder.api.component.impl.warnAboutAccessingVariantApiValueForDisabledFeature
import com.tyron.builder.api.variant.*
import com.tyron.builder.gradle.internal.PostprocessingFeatures
import com.tyron.builder.gradle.internal.ProguardFileType
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.component.features.BuildConfigCreationConfig
import com.tyron.builder.gradle.internal.component.features.FeatureNames
import com.tyron.builder.gradle.internal.component.features.ManifestPlaceholdersCreationConfig
import com.tyron.builder.gradle.internal.component.features.RenderscriptCreationConfig
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.VariantDslInfo
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.utils.immutableListBuilder
import com.tyron.builder.gradle.internal.variant.BaseVariantData
import com.tyron.builder.gradle.internal.variant.VariantPathHelper
import com.tyron.builder.internal.utils.appendCapitalized
import com.tyron.builder.internal.utils.capitalizeAndAppend
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import java.io.Serializable

abstract class VariantImpl<DslInfoT: VariantDslInfo>(
    open val variantBuilder: VariantBuilderImpl,
    buildFeatureValues: BuildFeatureValues,
    dslInfo: DslInfoT,
    variantDependencies: VariantDependencies,
    variantSources: VariantSources,
    paths: VariantPathHelper,
    artifacts: ArtifactsImpl,
    variantData: BaseVariantData,
    taskContainer: MutableTaskContainer,
    transformManager: TransformManager,
    variantServices: VariantServices,
    taskCreationServices: TaskCreationServices,
    global: GlobalTaskCreationConfig
) : ComponentImpl<DslInfoT>(
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
    variantServices,
    taskCreationServices,
    global
), Variant, VariantCreationConfig {

    override val description: String
        get() = if (componentIdentity.productFlavors.isNotEmpty()) {
            val sb = StringBuilder(50)
            componentIdentity.buildType?.let { sb.appendCapitalized(it) }
            sb.append(" build for flavor ")
            componentIdentity.flavorName?.let { sb.appendCapitalized(it) }
            sb.toString()
        } else {
            componentIdentity.buildType!!.capitalizeAndAppend(" build")
        }

    // ---------------------------------------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------------------------------------

    override val minSdkVersion: AndroidVersion by lazy {
        variantBuilder.minSdkVersion
    }

    override val targetSdkVersion: AndroidVersion by lazy {
        variantBuilder.targetSdkVersion
    }


    override val maxSdkVersion: Int?
        get() = variantBuilder.maxSdk

    override val buildConfigFields: MapProperty<String, BuildConfigField<out Serializable>>
    by lazy {
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

    override val packaging: Packaging by lazy {
        PackagingImpl(dslInfo.packaging, internalServices)
    }


//    override val externalNativeBuild: ExternalNativeBuild? by lazy {
//        dslInfo.nativeBuildSystem?.let { nativeBuildType ->
//            when(nativeBuildType) {
//                NativeBuiltType.CMAKE ->
//                    dslInfo.externalNativeBuildOptions.externalNativeCmakeOptions?.let {
//                        ExternalCmakeImpl(
//                                it,
//                                variantServices
//                        )
//                    }
//                NativeBuiltType.NDK_BUILD ->
//                    dslInfo.externalNativeBuildOptions.externalNativeNdkBuildOptions?.let {
//                        ExternalNdkBuildImpl(
//                                it,
//                                variantServices
//                        )
//                    }
//                NativeBuiltType.NINJA -> {
//                    ExternalNinjaImpl(
//                        externalNativeNinjaOptions,
//                        variantServices
//                    )
//                }
//            }
//        }
//    }

    override fun <T> getExtension(type: Class<T>): T? =
        type.cast(externalExtensions?.get(type))

    override val proguardFiles: ListProperty<RegularFile> =
        variantServices.listPropertyOf(RegularFile::class.java) {
            dslInfo.getProguardFiles(it)
        }

    // ---------------------------------------------------------------------------------------------
    // INTERNAL API
    // ---------------------------------------------------------------------------------------------

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
//
    override val renderscriptCreationConfig: RenderscriptCreationConfig? by lazy(LazyThreadSafetyMode.NONE) {
        if (buildFeatures.renderScript) {
            RenderscriptCreationConfigImpl(
                dslInfo,
                internalServices,
                renderscriptTargetApi = variantBuilder.renderscriptTargetApi
            )
        } else {
            null
        }
    }
//
    override val manifestPlaceholdersCreationConfig: ManifestPlaceholdersCreationConfig by lazy(LazyThreadSafetyMode.NONE) {
        ManifestPlaceholdersCreationConfigImpl(
            dslInfo,
            internalServices
        )
    }
//
//    override val testComponents = mutableMapOf<ComponentType, TestComponentCreationConfig>()
//    override var testFixturesComponent: TestFixturesCreationConfig? = null

    private val externalExtensions: Map<Class<*>, Any>? by lazy {
        variantBuilder.getRegisteredExtensions()
    }

    override val targetSdkVersionOverride: AndroidVersion?
        get() = variantBuilder.mutableTargetSdk?.sanitize()

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


    override fun makeResValueKey(type: String, name: String): ResValue.Key = TODO() // ResValueKeyImpl(type, name)

    private var _isMultiDexEnabled: Boolean? = dslInfo.isMultiDexEnabled
    override val isMultiDexEnabled: Boolean
        get() {
            return _isMultiDexEnabled ?: (minSdkVersion.getFeatureLevel() >= 21)
        }
//
    override val needsMainDexListForBundle: Boolean
        get() = dslInfo.componentType.isBaseModule
                && global.hasDynamicFeatures
                && dexingType.needsMainDexList

    override var unitTest = null

    override val pseudoLocalesEnabled: Property<Boolean> by lazy {
        androidResourcesCreationConfig?.pseudoLocalesEnabled
            ?: warnAboutAccessingVariantApiValueForDisabledFeature(
                featureName = FeatureNames.ANDROID_RESOURCES,
                apiName = "pseudoLocalesEnabled",
                value = internalServices.newPropertyBackingDeprecatedApi(
                    Boolean::class.java,
                    dslInfo.androidResourcesDsl!!.isPseudoLocalesEnabled
                )
            )
    }

    override val experimentalProperties: MapProperty<String, Any> =
            internalServices.mapPropertyOf(
                String::class.java,
                Any::class.java,
                dslInfo.experimentalProperties
            )

    override val externalNativeExperimentalProperties: Map<String, Any>
        get() = dslInfo.externalNativeExperimentalProperties

    override val nestedComponents: List<ComponentImpl<*>>
        get() = listOfNotNull(
            unitTest,
            (this as? HasAndroidTest)?.androidTest,
            (this as? HasAndroidTest)?.androidTest,
            (this as? HasTestFixtures)?.testFixtures
        )

    override val components: List<Component>
        get() = listOfNotNull(
            this,
            unitTest,
            (this as? HasAndroidTest)?.androidTest,
            (this as? HasTestFixtures)?.testFixtures
        )

    override val ignoredLibraryKeepRules: Provider<Set<String>> =
            internalServices.setPropertyOf(
                String::class.java,
                dslInfo.ignoredLibraryKeepRules
            )
//
    override val ignoreAllLibraryKeepRules: Boolean = dslInfo.ignoreAllLibraryKeepRules
//
    override val defaultGlslcArgs: List<String>
        get() = dslInfo.defaultGlslcArgs
    override val scopedGlslcArgs: Map<String, List<String>>
        get() = dslInfo.scopedGlslcArgs

//    override val ndkConfig: MergedNdkConfig
//        get() = dslInfo.ndkConfig
    override val isJniDebuggable: Boolean
        get() = dslInfo.isJniDebuggable
    override val supportedAbis: Set<String>
        get() = dslInfo.supportedAbis

    override val postProcessingFeatures: PostprocessingFeatures?
        get() = dslInfo.postProcessingOptions.getPostprocessingFeatures()
    override val consumerProguardFiles: List<File> by lazy(LazyThreadSafetyMode.NONE) {
        immutableListBuilder<File> {
            addAll(dslInfo.gatherProguardFiles(ProguardFileType.CONSUMER))
            // We include proguardFiles if we're in a dynamic-feature module.
            if (dslInfo.componentType.isDynamicFeature) {
                addAll(dslInfo.gatherProguardFiles(ProguardFileType.EXPLICIT))
            }
        }
    }
    override val manifestPlaceholders: MapProperty<String, String>
        get() = manifestPlaceholdersCreationConfig.placeholders
}

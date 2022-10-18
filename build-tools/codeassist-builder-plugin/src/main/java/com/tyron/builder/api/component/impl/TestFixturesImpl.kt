package com.tyron.builder.api.component.impl

import com.android.utils.appendCapitalized
import com.android.utils.capitalizeAndAppend
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar
import com.tyron.builder.api.variant.*
import com.tyron.builder.api.variant.impl.ResValueKeyImpl
import com.tyron.builder.gradle.internal.component.PublishableCreationConfig
import com.tyron.builder.gradle.internal.component.TestFixturesCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.component.features.BuildConfigCreationConfig
import com.tyron.builder.gradle.internal.component.features.FeatureNames
import com.tyron.builder.gradle.internal.component.features.ManifestPlaceholdersCreationConfig
import com.tyron.builder.gradle.internal.component.legacy.OldVariantApiLegacySupport
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.TestFixturesComponentDslInfo
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.publishing.VariantPublishingInfo
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.ProjectServices
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.tasks.AarMetadataTask.Companion.DEFAULT_MIN_AGP_VERSION
import com.tyron.builder.gradle.internal.tasks.AarMetadataTask.Companion.DEFAULT_MIN_COMPILE_SDK_EXTENSION
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.testFixtures.testFixturesFeatureName
import com.tyron.builder.gradle.internal.variant.VariantPathHelper
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

open class TestFixturesImpl @Inject constructor(
    componentIdentity: ComponentIdentity,
    buildFeatureValues: BuildFeatureValues,
    variantDslInfo: TestFixturesComponentDslInfo,
    variantDependencies: VariantDependencies,
    variantSources: VariantSources,
    paths: VariantPathHelper,
    artifacts: ArtifactsImpl,
    taskContainer: MutableTaskContainer,
    override val mainVariant: VariantCreationConfig,
    transformManager: TransformManager,
    variantServices: VariantServices,
    taskCreationServices: TaskCreationServices,
    global: GlobalTaskCreationConfig
): ComponentImpl<TestFixturesComponentDslInfo>(
    componentIdentity,
    buildFeatureValues,
    variantDslInfo,
    variantDependencies,
    variantSources,
    paths,
    artifacts,
    taskContainer = taskContainer,
    transformManager = transformManager,
    internalServices = variantServices,
    services = taskCreationServices,
    global = global
), TestFixtures, TestFixturesCreationConfig {

    override val description: String
        get() = if (productFlavorList.isNotEmpty()) {
            val sb = StringBuilder(50)
            dslInfo.componentIdentity.buildType?.let { sb.appendCapitalized(it) }
            sb.append(" build for flavor ")
            dslInfo.componentIdentity.flavorName?.let { sb.appendCapitalized(it) }
            sb.toString()
        } else {
            dslInfo.componentIdentity.buildType!!.capitalizeAndAppend(" build")
        }

    // test fixtures doesn't exist in the old variant api
    override val oldVariantApiLegacySupport: OldVariantApiLegacySupport? = null

    // ---------------------------------------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------------------------------------

    override val applicationId: Provider<String> =
        internalServices.providerOf(String::class.java, variantDslInfo.namespace)
    override val debuggable: Boolean
        get() = mainVariant.debuggable
    override val minSdkVersion: AndroidVersion
        get() = mainVariant.minSdkVersion
    override val targetSdkVersion: AndroidVersion
        get() = mainVariant.targetSdkVersion
    override val publishInfo: VariantPublishingInfo?
        get() = (mainVariant as? PublishableCreationConfig)?.publishInfo

    override val aarMetadata: AarMetadata =
        internalServices.newInstance(AarMetadata::class.java).also {
            it.minCompileSdk.set(1)
            it.minCompileSdkExtension.set(DEFAULT_MIN_COMPILE_SDK_EXTENSION)
            it.minAgpVersion.set(DEFAULT_MIN_AGP_VERSION)
        }

    override val javaCompilation: JavaCompilation =
        JavaCompilationImpl(
            variantDslInfo.javaCompileOptionsSetInDSL,
            buildFeatures.dataBinding,
            internalServices
        )

    // ---------------------------------------------------------------------------------------------
    // INTERNAL API
    // ---------------------------------------------------------------------------------------------

    override val buildConfigCreationConfig: BuildConfigCreationConfig? = null

    override val manifestPlaceholdersCreationConfig: ManifestPlaceholdersCreationConfig?
        get() = mainVariant.manifestPlaceholdersCreationConfig

    override val targetSdkVersionOverride: AndroidVersion?
        get() = mainVariant.targetSdkVersionOverride

    override fun <T : Component> createUserVisibleVariantObject(
        projectServices: ProjectServices,
        operationsRegistrar: VariantApiOperationsRegistrar<out CommonExtension<*, *, *, *>, out VariantBuilder, out Variant>,
        stats: Any?
    ): T {
        return if (stats == null) {
            this as T
        } else {
            TODO()
//            projectServices.objectFactory.newInstance(
//                AnalyticsEnabledTestFixtures::class.java,
//                this,
//                stats
//            ) as T
        }
    }

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

    override fun makeResValueKey(type: String, name: String): ResValue.Key = ResValueKeyImpl(type, name)

    override val pseudoLocalesEnabled: Property<Boolean>  by lazy {
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

    override fun getArtifactName(name: String): String {
        return "$testFixturesFeatureName-$name"
    }

    // ---------------------------------------------------------------------------------------------
    // Private stuff
    // ---------------------------------------------------------------------------------------------

    override val supportedAbis: Set<String>
        get() = mainVariant.supportedAbis
}

package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.DataBinding
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.api.variant.impl.GlobalVariantBuilderConfig
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.api.BaseVariantImpl
import com.tyron.builder.gradle.internal.api.ReadOnlyObjectProvider
import com.tyron.builder.gradle.internal.component.*
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.AndroidTestComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.TestFixturesComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.UnitTestComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.VariantDslInfo
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.dsl.BuildType
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.plugins.DslContainerProvider
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantBuilderServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.options.ProjectOptions
import org.gradle.api.Project

/**
 * Interface for Variant Factory.
 *
 *
 * While VariantManager is the general variant management, implementation of this interface
 * provides variant type (app, lib) specific implementation.
 */
interface VariantFactory<VariantBuilderT : VariantBuilder, VariantDslInfoT: VariantDslInfo, VariantT : VariantCreationConfig> {

    fun createVariantBuilder(
        globalVariantBuilderConfig: GlobalVariantBuilderConfig,
        componentIdentity: ComponentIdentity,
        variantDslInfo: VariantDslInfoT,
        variantBuilderServices: VariantBuilderServices
    ): VariantBuilderT

    fun createVariant(
        variantBuilder: VariantBuilderT,
        componentIdentity: ComponentIdentity,
        buildFeatures: BuildFeatureValues,
        variantDslInfo: VariantDslInfoT,
        variantDependencies: VariantDependencies,
        variantSources: VariantSources,
        paths: VariantPathHelper,
        artifacts: ArtifactsImpl,
        variantData: BaseVariantData,
        taskContainer: MutableTaskContainer,
        transformManager: TransformManager,
        variantServices: VariantServices,
        taskCreationServices: TaskCreationServices,
        globalConfig: GlobalTaskCreationConfig,
    ): VariantT

    fun createTestFixtures(
        componentIdentity: ComponentIdentity,
        buildFeatures: BuildFeatureValues,
        dslInfo: TestFixturesComponentDslInfo,
        variantDependencies: VariantDependencies,
        variantSources: VariantSources,
        paths: VariantPathHelper,
        artifacts: ArtifactsImpl,
        taskContainer: MutableTaskContainer,
        mainVariant: VariantCreationConfig,
        transformManager: TransformManager,
        variantServices: VariantServices,
        taskCreationServices: TaskCreationServices,
        globalConfig: GlobalTaskCreationConfig
    ): TestFixturesCreationConfig

    fun createUnitTest(
        componentIdentity: ComponentIdentity,
        buildFeatures: BuildFeatureValues,
        dslInfo: UnitTestComponentDslInfo,
        variantDependencies: VariantDependencies,
        variantSources: VariantSources,
        paths: VariantPathHelper,
        artifacts: ArtifactsImpl,
        variantData: TestVariantData,
        taskContainer: MutableTaskContainer,
        testedVariantProperties: VariantCreationConfig,
        transformManager: TransformManager,
        variantServices: VariantServices,
        taskCreationServices: TaskCreationServices,
        globalConfig: GlobalTaskCreationConfig,
    ): UnitTestCreationConfig

    fun createAndroidTest(
        componentIdentity: ComponentIdentity,
        buildFeatures: BuildFeatureValues,
        dslInfo: AndroidTestComponentDslInfo,
        variantDependencies: VariantDependencies,
        variantSources: VariantSources,
        paths: VariantPathHelper,
        artifacts: ArtifactsImpl,
        variantData: TestVariantData,
        taskContainer: MutableTaskContainer,
        testedVariantProperties: VariantCreationConfig,
        transformManager: TransformManager,
        variantServices: VariantServices,
        taskCreationServices: TaskCreationServices,
        globalConfig: GlobalTaskCreationConfig,
    ): AndroidTestCreationConfig

    fun createVariantData(
        componentIdentity: ComponentIdentity,
        artifacts: ArtifactsImpl,
        services: VariantServices,
        taskContainer: MutableTaskContainer
    ): BaseVariantData

    fun createBuildFeatureValues(
            buildFeatures: BuildFeatures, projectOptions: ProjectOptions): BuildFeatureValues

    fun createTestFixturesBuildFeatureValues(
        buildFeatures: BuildFeatures,
        projectOptions: ProjectOptions,
        androidResourcesEnabled: Boolean
    ): BuildFeatureValues

    fun createUnitTestBuildFeatureValues(
        buildFeatures: BuildFeatures,
        dataBinding: DataBinding,
        projectOptions: ProjectOptions,
        includeAndroidResources: Boolean
    ): BuildFeatureValues

    fun createAndroidTestBuildFeatureValues(
        buildFeatures: BuildFeatures,
        dataBinding: DataBinding,
        projectOptions: ProjectOptions): BuildFeatureValues

    val variantImplementationClass: Class<out BaseVariantImpl?>

    fun createVariantApi(
            component: ComponentCreationConfig,
            variantData: BaseVariantData,
            readOnlyObjectProvider: ReadOnlyObjectProvider): BaseVariantImpl?

    val componentType: ComponentType

    /**
     * Callback before variant creation to allow extra work or validation
     *
     * @param project the Project
     * @param dslExtension the Extension
     * @param model the non-null model to validate, as implemented by the VariantManager.

     * @throws org.gradle.api.GradleException in case of failed validation
     */
    fun preVariantCallback(
        project: Project,
        dslExtension: CommonExtension<*, *, *, *>,
        model: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>
    )

    fun createDefaultComponents(
            dslContainers: DslContainerProvider<DefaultConfig, BuildType, ProductFlavor, SigningConfig>)
}

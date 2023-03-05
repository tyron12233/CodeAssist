package com.tyron.builder.gradle.internal
//import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar
//import com.tyron.builder.api.variant.impl.ArtifactMetadataProcessor
//import com.tyron.builder.api.variant.impl.HasAndroidTest
//import com.tyron.builder.api.variant.impl.HasTestFixtures
//import com.tyron.builder.gradle.internal.core.dsl.AndroidTestComponentDslInfo
//import com.tyron.builder.gradle.internal.core.dsl.MultiVariantComponentDslInfo
//import com.tyron.builder.gradle.internal.core.dsl.TestComponentDslInfo
//import com.tyron.builder.gradle.internal.core.dsl.TestFixturesComponentDslInfo
//import com.tyron.builder.gradle.internal.core.dsl.TestedVariantDslInfo
//import com.tyron.builder.gradle.internal.core.dsl.UnitTestComponentDslInfo
//import com.tyron.builder.gradle.internal.core.dsl.impl.DslInfoBuilder
//import com.tyron.builder.gradle.internal.core.dsl.impl.DslInfoBuilder.Companion.getBuilder
//import com.tyron.builder.gradle.internal.core.dsl.impl.computeSourceSetName
//import com.tyron.builder.gradle.internal.crash.ExternalApiUsageException
//import com.tyron.builder.gradle.internal.dependency.VariantDependenciesBuilder
//import com.tyron.builder.gradle.internal.manifest.LazyManifestParser
//import com.tyron.builder.gradle.internal.pipeline.TransformManager
//import com.tyron.builder.gradle.internal.profile.AnalyticsConfiguratorService
//import com.tyron.builder.gradle.internal.profile.AnalyticsUtil
//import com.tyron.builder.gradle.internal.services.TaskCreationServicesImpl
//import com.tyron.builder.gradle.internal.services.VariantBuilderServicesImpl
//import com.tyron.builder.gradle.internal.services.VariantServicesImpl
//import com.tyron.builder.gradle.internal.services.getBuildService
//import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfigImpl.Companion.toExecutionEnum
//import com.tyron.builder.gradle.internal.variant.ComponentInfo
//import com.tyron.builder.gradle.internal.variant.DimensionCombination
//import com.tyron.builder.gradle.internal.variant.DimensionCombinator
//import com.tyron.builder.gradle.internal.variant.VariantComponentInfo
//import com.tyron.builder.gradle.internal.variant.VariantFactory
//import com.tyron.builder.gradle.internal.variant.VariantPathHelper
//import com.tyron.builder.gradle.options.SigningOptions
//import com.google.wireless.android.sdk.stats.ApiVersion
//import com.google.wireless.android.sdk.stats.GradleBuildVariant
import com.google.common.collect.Lists
import com.google.common.collect.Maps
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.attributes.ProductFlavorAttr
import com.tyron.builder.api.component.impl.AndroidTestImpl
import com.tyron.builder.api.component.impl.TestFixturesImpl
import com.tyron.builder.api.dsl.ApplicationExtension
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.TestedExtension
import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar
import com.tyron.builder.api.variant.*
import com.tyron.builder.api.variant.impl.*
import com.tyron.builder.api.variant.impl.HasAndroidTest
import com.tyron.builder.api.variant.impl.HasTestFixtures
import com.tyron.builder.core.AbstractProductFlavor.DimensionRequest
import com.tyron.builder.core.ComponentType
import com.tyron.builder.core.ComponentTypeImpl
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.BaseExtension
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet
import com.tyron.builder.gradle.internal.api.ReadOnlyObjectProvider
import com.tyron.builder.gradle.internal.api.VariantFilter
import com.tyron.builder.gradle.internal.component.NestedComponentCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.TestFixturesCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.*
import com.tyron.builder.gradle.internal.core.dsl.impl.DslInfoBuilder
import com.tyron.builder.gradle.internal.core.dsl.impl.DslInfoBuilder.Companion.getBuilder
import com.tyron.builder.gradle.internal.core.dsl.impl.computeSourceSetName
import com.tyron.builder.gradle.internal.crash.ExternalApiUsageException
import com.tyron.builder.gradle.internal.dependency.VariantDependenciesBuilder
import com.tyron.builder.gradle.internal.dsl.BuildType
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.manifest.LazyManifestParser
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.*
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.variant.*
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.SigningOptions
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.internal.GeneratedSubclass
import java.io.File
import java.util.*
import java.util.function.BooleanSupplier
import java.util.stream.Collectors

/** Class to create, manage variants.  */
@Suppress("UnstableApiUsage")
class VariantManager<
        CommonExtensionT: CommonExtension<*, *, *, *>,
        VariantBuilderT : VariantBuilder,
        VariantDslInfoT: VariantDslInfo,
        VariantT : VariantCreationConfig>(
    private val project: Project,
    private val dslServices: DslServices,
    @Deprecated("Use dslExtension")  private val oldExtension: BaseExtension,
    private val dslExtension: CommonExtensionT,
    val variantApiOperationsRegistrar: VariantApiOperationsRegistrar<CommonExtensionT, VariantBuilder, Variant>,
    private val variantFactory: VariantFactory<VariantBuilderT, VariantDslInfoT, VariantT>,
    private val variantInputModel: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>,
    val globalTaskCreationConfig: GlobalTaskCreationConfig,
    private val projectServices: ProjectServices
) {

    private val variantBuilderServices: VariantBuilderServices
    private val variantPropertiesApiServices: VariantServicesImpl
    private val taskCreationServices: TaskCreationServices
    private val variantFilter: VariantFilter
    private val variants: MutableList<ComponentInfo<VariantBuilderT, VariantT>> =
        Lists.newArrayList()
    private val lazyManifestParserMap: MutableMap<File, LazyManifestParser> =
        Maps.newHashMapWithExpectedSize(3)
    private val signingOverride: SigningConfig?

    // We cannot use gradle's state of executed as that returns true while inside afterEvalute.
    // Wew want this to only be true after all tasks have been create.
    private var hasCreatedTasks = false

    /**
     * Returns a list of all main components.
     *
     * @see .createVariants
     */
    val mainComponents: List<ComponentInfo<VariantBuilderT, VariantT>>
        get() = variants

    /**
     * Returns a list of all nested components.
     */
    val nestedComponents: MutableList<NestedComponentCreationConfig> = Lists.newArrayList()

    /**
     * Returns a list of all test components.
     *
     * @see .createVariants
     */
    val testComponents: MutableList<TestComponentCreationConfig> =
        Lists.newArrayList()

    /**
     * Returns a list of all test fixtures components.
     */
    val testFixturesComponents: MutableList<TestFixturesCreationConfig> = Lists.newArrayList()

    val buildFeatureValues: BuildFeatureValues
        get() = _buildFeatureValues

    private lateinit var _buildFeatureValues: BuildFeatureValues

    /**
     * Creates the variants.
     *
     * @param buildFeatureValues the build feature value instance
     */
    fun createVariants(
        buildFeatureValues: BuildFeatureValues,
    ) {
        _buildFeatureValues = buildFeatureValues
        variantFactory.preVariantCallback(project, dslExtension, variantInputModel)
        computeVariants()
    }

    private fun getFlavorSelection(
        variantDslInfo: ComponentDslInfo
    ): Map<Attribute<ProductFlavorAttr>, ProductFlavorAttr> {
        val factory = project.objects
        return variantDslInfo.missingDimensionStrategies.entries.stream()
            .collect(
                Collectors.toMap(
                    { entry: Map.Entry<String, DimensionRequest> ->
                        ProductFlavorAttr.of(entry.key)
                    }
                ) { entry: Map.Entry<String, DimensionRequest> ->
                    factory.named(
                        ProductFlavorAttr::class.java,
                        entry.value.requested)
                })
    }


    /**
     * Create all variants.
     */
    private fun computeVariants() {
        val flavorDimensionList: List<String> = dslExtension.flavorDimensions
        val computer = DimensionCombinator(
            variantInputModel,
            projectServices.issueReporter,
            flavorDimensionList)
        val variants = computer.computeVariants()

        // get some info related to testing
        val testBuildTypeData = testBuildTypeData

        // figure out whether there are inconsistency in the appId of the flavors
        val inconsistentTestAppId = checkInconsistentTestAppId(
            variantInputModel.productFlavors.values.map { it.productFlavor }
        )

        val globalConfig = GlobalVariantBuilderConfigImpl(dslExtension)

        // loop on all the new variant objects to create the legacy ones.
        for (variant in variants) {
            createVariantsFromCombination(
                variant,
                testBuildTypeData,
                inconsistentTestAppId,
                globalConfig
            )
        }

        // FIXME we should lock the variant API properties after all the beforeVariants, and
        // before any onVariants to avoid cross access between the two.
        // This means changing the way to run beforeVariants vs onVariants.
        variantBuilderServices.lockValues()
    }


    private val testBuildTypeData: BuildTypeData<BuildType>?
        get() {
            var testBuildTypeData: BuildTypeData<BuildType>? = null
            if (dslExtension is TestedExtension) {
                testBuildTypeData = variantInputModel.buildTypes[dslExtension.testBuildType]
                if (testBuildTypeData == null) {
                    throw RuntimeException(String.format(
                        "Test Build Type '%1\$s' does not" + " exist.",
                        dslExtension.testBuildType))
                }
            }
            return testBuildTypeData
        }

    private fun createVariant(
        dimensionCombination: DimensionCombination,
        buildTypeData: BuildTypeData<BuildType>,
        productFlavorDataList: List<ProductFlavorData<ProductFlavor>>,
        componentType: ComponentType,
        globalConfig: GlobalVariantBuilderConfig,
    ): VariantComponentInfo<VariantBuilderT, VariantDslInfoT, VariantT>? {
        // entry point for a given buildType/Flavors/VariantType combo.
        // Need to run the new variant API to selectively ignore variants.
        // in order to do this, we need access to the VariantDslInfo, to create a
        val defaultConfig = variantInputModel.defaultConfigData
        val defaultConfigSourceProvider = defaultConfig.sourceSet
        val variantDslInfoBuilder = getBuilder<CommonExtensionT, VariantDslInfoT>(
            dimensionCombination,
            componentType,
            defaultConfig.defaultConfig,
            defaultConfigSourceProvider,
            buildTypeData.buildType,
            buildTypeData.sourceSet,
            signingOverride,
            getLazyManifestParser(
                defaultConfigSourceProvider.manifestFile,
                componentType.requiresManifest) { canParseManifest() },
            variantPropertiesApiServices,
            dslExtension,
            project.layout.buildDirectory,
            dslServices
        )

        // We must first add the flavors to the variant config, in order to get the proper
        // variant-specific and multi-flavor name as we add/create the variant providers later.
        for (productFlavorData in productFlavorDataList) {
            variantDslInfoBuilder.addProductFlavor(
                productFlavorData.productFlavor, productFlavorData.sourceSet)
        }
        val variantDslInfo = variantDslInfoBuilder.createDslInfo()
        val componentIdentity = variantDslInfo.componentIdentity

        // create the Variant object so that we can run the action which may interrupt the creation
        // (in case of enabled = false)
        val variantBuilder = variantFactory.createVariantBuilder(
            globalConfig, componentIdentity, variantDslInfo, variantBuilderServices,
        )

        // now that we have the variant, create the analytics object,
//        val configuratorService = getBuildService(
//            project.gradle.sharedServices,
//            AnalyticsConfiguratorService::class.java)
//            .get()
//        val profileEnabledVariantBuilder = configuratorService.getVariantBuilder(
//            project.path, variantBuilder.name)

        val userVisibleVariantBuilder =
            (variantBuilder as InternalVariantBuilder).createUserVisibleVariantObject<VariantBuilder>(
                projectServices,
                null,
            )

        // execute the Variant API
        variantApiOperationsRegistrar.variantBuilderOperations.executeOperations(userVisibleVariantBuilder)
        if (!variantBuilder.enable) {
            return null
        }

        // now that we have the result of the filter, we can continue configuring the variant
        createCompoundSourceSets(productFlavorDataList, variantDslInfoBuilder)
        val variantSources = variantDslInfoBuilder.createVariantSources()

        // Add the container of dependencies.
        // The order of the libraries is important, in descending order:
        // variant-specific, build type, multi-flavor, flavor1, flavor2, ..., defaultConfig.
        // variant-specific if the full combo of flavors+build type. Does not exist if no flavors.
        // multi-flavor is the combination of all flavor dimensions. Does not exist if <2 dimension.
        val variantSourceSets: MutableList<DefaultAndroidSourceSet?> =
            Lists.newArrayListWithExpectedSize(productFlavorDataList.size + 4)

        // 1. add the variant-specific if applicable.
        if (productFlavorDataList.isNotEmpty()) {
            variantSourceSets.add(variantSources.variantSourceProvider)
        }

        // 2. the build type.
        variantSourceSets.add(buildTypeData.sourceSet)

        // 3. the multi-flavor combination
        if (productFlavorDataList.size > 1) {
            variantSourceSets.add(variantSources.multiFlavorSourceProvider)
        }

        // 4. the flavors.
        for (productFlavor in productFlavorDataList) {
            variantSourceSets.add(productFlavor.sourceSet)
        }

        // 5. The defaultConfig
        variantSourceSets.add(variantInputModel.defaultConfigData.sourceSet)

        // Create VariantDependencies
        val builder = VariantDependenciesBuilder.builder(
            project,
            dslServices.projectOptions,
            projectServices.issueReporter,
            variantDslInfo)
            .setFlavorSelection(getFlavorSelection(variantDslInfo))
            .addSourceSets(variantSourceSets)
        if (dslExtension is ApplicationExtension) {
            builder.setFeatureList(dslExtension.dynamicFeatures)
        }

        val variantDependencies = builder.build()

        // Done. Create the (too) many variant objects
        val pathHelper =
            VariantPathHelper(
                project.layout.buildDirectory,
                variantDslInfo,
                dslServices
            )
        val artifacts = ArtifactsImpl(project, componentIdentity.name)
        val taskContainer = MutableTaskContainer()
        val transformManager = TransformManager(project, dslServices.issueReporter)

        // and the obsolete variant data
        val variantData = variantFactory.createVariantData(
            componentIdentity,
            artifacts,
            variantPropertiesApiServices,
            taskContainer
        )

        // then the new Variant which will contain the 2 old objects.
        val variantApiObject = variantFactory.createVariant(
            variantBuilder,
            componentIdentity,
            buildFeatureValues,
            variantDslInfo,
            variantDependencies,
            variantSources,
            pathHelper,
            artifacts,
            variantData,
            taskContainer,
            transformManager,
            variantPropertiesApiServices,
            taskCreationServices,
            globalTaskCreationConfig,
        )

        return VariantComponentInfo(
            variantBuilder,
            variantApiObject,
            null,
            variantDslInfo
        )
    }

    private fun<DslInfoT: ComponentDslInfo> createCompoundSourceSets(
        productFlavorList: List<ProductFlavorData<ProductFlavor>>,
        dslInfoBuilder: DslInfoBuilder<CommonExtensionT, DslInfoT>
    ) {
        val componentType = dslInfoBuilder.componentType
        if (productFlavorList.isNotEmpty() /* && !variantConfig.getType().isSingleBuildType()*/) {
            val variantSourceSet = variantInputModel
                .sourceSetManager
                .setUpSourceSet(
                    computeSourceSetName(dslInfoBuilder.name, componentType),
                    componentType.isTestComponent) as DefaultAndroidSourceSet
            dslInfoBuilder.variantSourceProvider = variantSourceSet
        }
        if (productFlavorList.size > 1) {
            val multiFlavorSourceSet = variantInputModel
                .sourceSetManager
                .setUpSourceSet(
                    computeSourceSetName(dslInfoBuilder.flavorName,
                        componentType),
                    componentType.isTestComponent) as DefaultAndroidSourceSet
            dslInfoBuilder.multiFlavorSourceProvider = multiFlavorSourceSet
        }
    }

    /** Create a test fixtures component for the specified main component.  */
    private fun createTestFixturesComponent(
        dimensionCombination: DimensionCombination,
        buildTypeData: BuildTypeData<BuildType>,
        productFlavorDataList: List<ProductFlavorData<ProductFlavor>>,
        mainComponentInfo: VariantComponentInfo<VariantBuilderT, VariantDslInfoT, VariantT>
    ): TestFixturesCreationConfig {
        val testFixturesComponentType = ComponentTypeImpl.TEST_FIXTURES
        val testFixturesSourceSet = variantInputModel.defaultConfigData.testFixturesSourceSet!!
        val variantDslInfoBuilder = getBuilder<CommonExtensionT, TestFixturesComponentDslInfo>(
            dimensionCombination,
            testFixturesComponentType,
            variantInputModel.defaultConfigData.defaultConfig,
            testFixturesSourceSet,
            buildTypeData.buildType,
            buildTypeData.testFixturesSourceSet,
            signingOverride,
            getLazyManifestParser(
                testFixturesSourceSet.manifestFile,
                testFixturesComponentType.requiresManifest) { canParseManifest() },
            variantPropertiesApiServices,
            extension = dslExtension,
            buildDirectory = project.layout.buildDirectory,
            dslServices = dslServices
        )

        variantDslInfoBuilder.productionVariant =
            mainComponentInfo.variantDslInfo as TestedVariantDslInfo

        val productFlavorList = (mainComponentInfo.variantDslInfo as MultiVariantComponentDslInfo).productFlavorList

        // We must first add the flavors to the variant builder, in order to get the proper
        // variant-specific and multi-flavor name as we add/create the variant providers later.
        val productFlavors = variantInputModel.productFlavors
        for (productFlavor in productFlavorList) {
            productFlavors[productFlavor.name]?.let {
                variantDslInfoBuilder.addProductFlavor(
                    it.productFlavor,
                    it.testFixturesSourceSet!!
                )
            }
        }
        val variantDslInfo = variantDslInfoBuilder.createDslInfo()

        // now that we have the result of the filter, we can continue configuring the variant
        createCompoundSourceSets(productFlavorDataList, variantDslInfoBuilder)
        val variantSources = variantDslInfoBuilder.createVariantSources()

        // Add the container of dependencies, the order of the libraries is important.
        // In descending order: build type (only for unit test), flavors, defaultConfig.

        // Add the container of dependencies.
        // The order of the libraries is important, in descending order:
        // variant-specific, build type (, multi-flavor, flavor1, flavor2, ..., defaultConfig.
        // variant-specific if the full combo of flavors+build type. Does not exist if no flavors.
        // multi-flavor is the combination of all flavor dimensions. Does not exist if <2 dimension.
        val testFixturesVariantSourceSets: MutableList<DefaultAndroidSourceSet?> =
            Lists.newArrayListWithExpectedSize(4 + productFlavorList.size)

        // 1. add the variant-specific if applicable.
        if (productFlavorList.isNotEmpty()) {
            testFixturesVariantSourceSets.add(variantSources.variantSourceProvider)
        }

        // 2. the build type.
        val buildTypeConfigurationProvider = buildTypeData.testFixturesSourceSet
        buildTypeConfigurationProvider?.let {
            testFixturesVariantSourceSets.add(it)
        }

        // 3. the multi-flavor combination
        if (productFlavorList.size > 1) {
            testFixturesVariantSourceSets.add(variantSources.multiFlavorSourceProvider)
        }

        // 4. the flavors.
        for (productFlavor in productFlavorList) {
            variantInputModel.productFlavors[productFlavor.name]?.let {
                testFixturesVariantSourceSets.add(it.testFixturesSourceSet)
            }
        }

        // now add the default config
        testFixturesVariantSourceSets.add(variantInputModel.defaultConfigData.testFixturesSourceSet)

        // If the variant being tested is a library variant, VariantDependencies must be
        // computed after the tasks for the tested variant is created.  Therefore, the
        // VariantDependencies is computed here instead of when the VariantData was created.
        val variantDependencies = VariantDependenciesBuilder.builder(
            project,
            dslServices.projectOptions,
            projectServices.issueReporter,
            variantDslInfo
        )
            .addSourceSets(testFixturesVariantSourceSets)
            .setFlavorSelection(getFlavorSelection(variantDslInfo))
            .overrideVariantNameAttribute(mainComponentInfo.variant.name)
            .setMainVariant(mainComponentInfo.variant)
            .build()
        val pathHelper =
            VariantPathHelper(
                project.layout.buildDirectory,
                variantDslInfo,
                dslServices
            )
        val componentIdentity = variantDslInfo.componentIdentity
        val artifacts = ArtifactsImpl(project, componentIdentity.name)
        val taskContainer = MutableTaskContainer()
        val transformManager = TransformManager(project, dslServices.issueReporter)
        val testFixturesBuildFeatureValues = variantFactory.createTestFixturesBuildFeatureValues(
            dslExtension.buildFeatures,
            dslServices.projectOptions,
            variantDslInfo.testFixturesAndroidResourcesEnabled
        )

        return variantFactory.createTestFixtures(
            variantDslInfo.componentIdentity,
            testFixturesBuildFeatureValues,
            variantDslInfo,
            variantDependencies,
            variantSources,
            pathHelper,
            artifacts,
            taskContainer,
            mainComponentInfo.variant,
            transformManager,
            variantPropertiesApiServices,
            taskCreationServices,
            globalTaskCreationConfig
        ).also {
            // register testFixtures component to the main variant
//            mainComponentInfo.variant.testFixturesComponent = it
        }
    }

    /** Create a TestVariantData for the specified testedVariantData.  */
    fun<TestDslInfoT: TestComponentDslInfo> createTestComponents(
        dimensionCombination: DimensionCombination,
        buildTypeData: BuildTypeData<BuildType>,
        productFlavorDataList: List<ProductFlavorData<ProductFlavor>>,
        testedComponentInfo: VariantComponentInfo<VariantBuilderT, VariantDslInfoT, VariantT>,
        componentType: ComponentType,
        testFixturesEnabled: Boolean,
        inconsistentTestAppId: Boolean
    ): TestComponentCreationConfig? {

        // handle test variant
        // need a suppress warning because ProductFlavor.getTestSourceSet(type) is annotated
        // to return @Nullable and the constructor is @NonNull on this parameter,
        // but it's never the case on defaultConfigData
        // The constructor does a runtime check on the instances so we should be safe.
        val testSourceSet = variantInputModel.defaultConfigData.getTestSourceSet(componentType)
        val variantDslInfoBuilder = getBuilder<CommonExtensionT, TestDslInfoT> (
            dimensionCombination,
            componentType,
            variantInputModel.defaultConfigData.defaultConfig,
            testSourceSet!!,
            buildTypeData.buildType,
            buildTypeData.getTestSourceSet(componentType),
            signingOverride,
            getLazyManifestParser(
                testSourceSet.manifestFile,
                componentType.requiresManifest) { canParseManifest() },
            variantPropertiesApiServices,
            extension = dslExtension,
            buildDirectory = project.layout.buildDirectory,
            dslServices = dslServices
        )
        variantDslInfoBuilder.productionVariant =
            testedComponentInfo.variantDslInfo as TestedVariantDslInfo
        variantDslInfoBuilder.inconsistentTestAppId = inconsistentTestAppId

        val productFlavorList = (testedComponentInfo.variantDslInfo as MultiVariantComponentDslInfo).productFlavorList

        // We must first add the flavors to the variant builder, in order to get the proper
        // variant-specific and multi-flavor name as we add/create the variant providers later.
        val productFlavors = variantInputModel.productFlavors
        for (productFlavor in productFlavorList) {
            productFlavors[productFlavor.name]?.let {
                variantDslInfoBuilder.addProductFlavor(
                    it.productFlavor,
                    it.getTestSourceSet(componentType)!!)
            }
        }
        val variantDslInfo = variantDslInfoBuilder.createDslInfo()
        if (componentType.isApk
            && testedComponentInfo.variantBuilder is HasAndroidTestBuilder
        ) {
            // this is ANDROID_TEST
            if (!testedComponentInfo.variantBuilder.enableAndroidTest) {
                return null
            }
        } else {
            // this is UNIT_TEST
            if (!testedComponentInfo.variantBuilder.enableUnitTest) {
                return null
            }
        }

        // now that we have the result of the filter, we can continue configuring the variant
        createCompoundSourceSets(productFlavorDataList, variantDslInfoBuilder)
        val variantSources = variantDslInfoBuilder.createVariantSources()

        // Add the container of dependencies, the order of the libraries is important.
        // In descending order: build type (only for unit test), flavors, defaultConfig.

        // Add the container of dependencies.
        // The order of the libraries is important, in descending order:
        // variant-specific, build type (, multi-flavor, flavor1, flavor2, ..., defaultConfig.
        // variant-specific if the full combo of flavors+build type. Does not exist if no flavors.
        // multi-flavor is the combination of all flavor dimensions. Does not exist if <2 dimension.
        val testVariantSourceSets: MutableList<DefaultAndroidSourceSet?> =
            Lists.newArrayListWithExpectedSize(4 + productFlavorList.size)

        // 1. add the variant-specific if applicable.
        if (productFlavorList.isNotEmpty()) {
            testVariantSourceSets.add(variantSources.variantSourceProvider)
        }

        // 2. the build type.
        val buildTypeConfigurationProvider = buildTypeData.getTestSourceSet(componentType)
        buildTypeConfigurationProvider?.let {
            testVariantSourceSets.add(it)
        }

        // 3. the multi-flavor combination
        if (productFlavorList.size > 1) {
            testVariantSourceSets.add(variantSources.multiFlavorSourceProvider)
        }

        // 4. the flavors.
        for (productFlavor in productFlavorList) {
            variantInputModel.productFlavors[productFlavor.name]?.let {
                testVariantSourceSets.add(it.getTestSourceSet(componentType))
            }
        }

        // now add the default config
        testVariantSourceSets.add(
            variantInputModel.defaultConfigData.getTestSourceSet(componentType))

        // If the variant being tested is a library variant, VariantDependencies must be
        // computed after the tasks for the tested variant is created.  Therefore, the
        // VariantDependencies is computed here instead of when the VariantData was created.
        val builder = VariantDependenciesBuilder.builder(
            project,
            dslServices.projectOptions,
            projectServices.issueReporter,
            variantDslInfo)
            .addSourceSets(testVariantSourceSets)
            .setFlavorSelection(getFlavorSelection(variantDslInfo))
            .setTestedVariant(testedComponentInfo.variant)
            .setTestFixturesEnabled(testFixturesEnabled)
        val variantDependencies = builder.build()
        val pathHelper =
            VariantPathHelper(
                project.layout.buildDirectory,
                variantDslInfo,
                dslServices
            )
        val componentIdentity = variantDslInfo.componentIdentity
        val artifacts = ArtifactsImpl(project, componentIdentity.name)
        val taskContainer = MutableTaskContainer()
        val transformManager = TransformManager(project, dslServices.issueReporter)

        // create the internal storage for this variant.
        val testVariantData = TestVariantData(
            componentIdentity,
            artifacts,
            variantPropertiesApiServices,
            taskContainer
        )

        // this is ANDROID_TEST
        val testComponent = if (componentType.isApk) {
            val androidTest = variantFactory.createAndroidTest(
                variantDslInfo.componentIdentity,
                variantFactory.createAndroidTestBuildFeatureValues(
                    dslExtension.buildFeatures,
                    dslExtension.dataBinding,
                    dslServices.projectOptions
                ),
                variantDslInfo as AndroidTestComponentDslInfo,
                variantDependencies,
                variantSources,
                pathHelper,
                artifacts,
                testVariantData,
                taskContainer,
                testedComponentInfo.variant,
                transformManager,
                variantPropertiesApiServices,
                taskCreationServices,
                globalTaskCreationConfig
            )
            androidTest
        } else {
            // this is UNIT_TEST
            val unitTest = variantFactory.createUnitTest(
                variantDslInfo.componentIdentity,
                variantFactory.createUnitTestBuildFeatureValues(
                    dslExtension.buildFeatures,
                    dslExtension.dataBinding,
                    dslServices.projectOptions,
                    false
//                    globalTaskCreationConfig.testOptions.unitTests.isIncludeAndroidResources
                ),
                variantDslInfo as UnitTestComponentDslInfo,
                variantDependencies,
                variantSources,
                pathHelper,
                artifacts,
                testVariantData,
                taskContainer,
                testedComponentInfo.variant,
                transformManager,
                variantPropertiesApiServices,
                taskCreationServices,
                globalTaskCreationConfig
            )
            unitTest
        }

//        // register
//        testedComponentInfo
//            .variant
//            .testComponents[variantDslInfo.componentType] = testComponent
        return testComponent
    }

    /**
     * Creates Variant objects for a specific [ComponentIdentity]
     *
     *
     * This will create both the prod and the androidTest/unitTest variants.
     */
    private fun createVariantsFromCombination(
        dimensionCombination: DimensionCombination,
        testBuildTypeData: BuildTypeData<BuildType>?,
        inconsistentTestAppId: Boolean,
        globalConfig: GlobalVariantBuilderConfig,
    ) {
        val componentType = variantFactory.componentType

        // first run the old variantFilter API
        // This acts on buildtype/flavor only, and applies in one pass to prod/tests.
        val defaultConfig = variantInputModel.defaultConfigData.defaultConfig
        val buildTypeData = variantInputModel.buildTypes[dimensionCombination.buildType]
        val buildType = buildTypeData!!.buildType

        // get the list of ProductFlavorData from the list of flavor name
        val productFlavorDataList: List<ProductFlavorData<ProductFlavor>> = dimensionCombination
            .productFlavors
            .mapNotNull { (_, second) -> variantInputModel.productFlavors[second] }
        val productFlavorList: List<ProductFlavor> = productFlavorDataList
            .map { it.productFlavor }
        var ignore = false
        oldExtension.variantFilter?.let {
            variantFilter.reset(
                dimensionCombination, defaultConfig, buildType, componentType, productFlavorList)
            try {
                // variantFilterAction != null always true here.
                it.execute(variantFilter)
            } catch (t: Throwable) {
                throw ExternalApiUsageException(t)
            }
            ignore = variantFilter.ignore
        }
        if (!ignore) {
            // create the prod variant
            createVariant(
                dimensionCombination,
                buildTypeData,
                productFlavorDataList,
                componentType,
                globalConfig
            )?.let { variantInfo ->
                addVariant(variantInfo)
                val variant = variantInfo.variant
                val variantBuilder = variantInfo.variantBuilder
                val minSdkVersion = variant.minSdkVersion
                val targetSdkVersion = variant.targetSdkVersion
                if (minSdkVersion.apiLevel > targetSdkVersion.apiLevel) {
                    projectServices
                        .issueReporter
                        .reportWarning(
                            IssueReporter.Type.GENERIC, String.format(
                                Locale.US,
                                "minSdkVersion (%d) is greater than targetSdkVersion"
                                        + " (%d) for variant \"%s\". Please change the"
                                        + " values such that minSdkVersion is less than or"
                                        + " equal to targetSdkVersion.",
                                minSdkVersion.apiLevel,
                                targetSdkVersion.apiLevel,
                                variant.name))
                }

                val testFixturesEnabledForVariant =
                    variantBuilder is HasTestFixturesBuilder &&
                            (variantBuilder as HasTestFixturesBuilder)
                                .enableTestFixtures

                if (testFixturesEnabledForVariant) {
                    val testFixtures = createTestFixturesComponent(
                        dimensionCombination,
                        buildTypeData,
                        productFlavorDataList,
                        variantInfo
                    )
                    addTestFixturesComponent(testFixtures)
                    (variant as HasTestFixtures).testFixtures = testFixtures as TestFixturesImpl
                }

                if (variantFactory.componentType.hasTestComponents) {
                    if (buildTypeData == testBuildTypeData) {
                        val androidTest = createTestComponents<AndroidTestComponentDslInfo>(
                            dimensionCombination,
                            buildTypeData,
                            productFlavorDataList,
                            variantInfo,
                            ComponentTypeImpl.ANDROID_TEST,
                            testFixturesEnabledForVariant,
                            inconsistentTestAppId
                        )
                        androidTest?.let {
                            addTestComponent(it)
                            (variant as HasAndroidTest).androidTest = it as AndroidTestImpl
                        }
                    }
                    val unitTest = createTestComponents<UnitTestComponentDslInfo>(
                        dimensionCombination,
                        buildTypeData,
                        productFlavorDataList,
                        variantInfo,
                        ComponentTypeImpl.UNIT_TEST,
                        testFixturesEnabledForVariant,
                        false
                    )
                    unitTest?.let {
                        addTestComponent(it)
//                        variant.unitTest = it as UnitTestImpl
                    }
                }

                // Now that unitTest and/or androidTest have been created and added to the main
                // user visible variant object, we can run the onVariants() actions
                val userVisibleVariant = (variant as VariantImpl<*>)
                    .createUserVisibleVariantObject<Variant>(projectServices,
                        variantApiOperationsRegistrar,
                        variantInfo.stats)

                // The variant object is created, let's create the user extension variant scoped objects
                // and store them in our newly created variant object.
                val variantExtensionConfig = object: VariantExtensionConfig<Variant> {
                    override val variant: Variant
                        get() = userVisibleVariant

                    override fun <T> projectExtension(extensionType: Class<T>): T {
                        // we need to make DefaultConfig or CommonExtension implement ExtensionAware.
                        throw RuntimeException("No global extension DSL element implements ExtensionAware.")
                    }

                    override fun <T> buildTypeExtension(extensionType: Class<T>): T =
                        buildTypeData.buildType.extensions.getByType(extensionType)

                    override fun <T> productFlavorsExtensions(extensionType: Class<T>): List<T> =
                        productFlavorDataList.map { productFlavorData ->
                            productFlavorData.productFlavor.extensions.getByType(extensionType)
                        }
                }

                variantApiOperationsRegistrar.dslExtensions.forEach { registeredExtension ->
                    registeredExtension.configurator.invoke(variantExtensionConfig).let {
                        variantBuilder.registerExtension(
                            if (it is GeneratedSubclass) it.publicType() else it.javaClass,
                            it
                        )
                    }
                }
                variantApiOperationsRegistrar.variantOperations.executeOperations(userVisibleVariant)

                // all the variant public APIs have run, we can now safely fill the analytics with
                // the final values that will be used throughout the task creation and execution.
//                val variantAnalytics = variantInfo.stats
//                variantAnalytics?.let {
//                    it
//                        .setIsDebug(buildType.isDebuggable)
//                        .setMinSdkVersion(AnalyticsUtil.toProto(minSdkVersion))
//                        .setMinifyEnabled(variant.minifiedEnabled)
//                        .setUseMultidex(variant.isMultiDexEnabled)
//                        .setUseLegacyMultidex(variant.dexingType.isLegacyMultiDexMode())
//                        .setVariantType(variant.componentType.analyticsVariantType)
//                        .setDexBuilder(GradleBuildVariant.DexBuilderTool.D8_DEXER)
//                        .setDexMerger(GradleBuildVariant.DexMergerTool.D8_MERGER)
//                        .setCoreLibraryDesugaringEnabled(variant.isCoreLibraryDesugaringEnabled)
//                        .setHasUnitTest(variant.unitTest != null)
//                        .setHasAndroidTest((variant as? HasAndroidTest)?.androidTest != null)
//                        .setHasTestFixtures((variant as? HasTestFixtures)?.testFixtures != null)
//                        .testExecution = AnalyticsUtil.toProto(dslExtension.testOptions.execution.toExecutionEnum() ?: TestOptions.Execution.HOST)
//
//                    if (variant.minifiedEnabled) {
//                        // If code shrinker is used, it can only be R8
//                        variantAnalytics.codeShrinker = GradleBuildVariant.CodeShrinkerTool.R8
//                    }
//                    variantAnalytics.targetSdkVersion = AnalyticsUtil.toProto(targetSdkVersion)
//                    variant.maxSdkVersion?.let { version ->
//                        variantAnalytics.setMaxSdkVersion(
//                            ApiVersion.newBuilder().setApiLevel(version.toLong()))
//                    }
//                    val supportType = variant.getJava8LangSupportType()
//                    if (supportType != Java8LangSupport.INVALID
//                        && supportType != Java8LangSupport.UNUSED) {
//                        variantAnalytics.java8LangSupport = AnalyticsUtil.toProto(supportType)
//                    }
//                }
            }
        }
    }

    private fun addVariant(variant: ComponentInfo<VariantBuilderT, VariantT>) {
        variants.add(variant)
    }

    private fun addTestComponent(testComponent: TestComponentCreationConfig) {
        nestedComponents.add(testComponent)
        testComponents.add(testComponent)
    }

    private fun addTestFixturesComponent(testFixturesComponent: TestFixturesCreationConfig) {
        nestedComponents.add(testFixturesComponent)
        testFixturesComponents.add(testFixturesComponent)
    }

    private fun createSigningOverride(): SigningConfig? {
        SigningOptions.readSigningOptions(dslServices.projectOptions)?.let { signingOptions ->
            val signingConfigDsl = dslServices.newDecoratedInstance(SigningConfig::class.java, SigningOptions.SIGNING_CONFIG_NAME, dslServices)
            signingConfigDsl.storeFile(File(signingOptions.storeFile))
            signingConfigDsl.storePassword(signingOptions.storePassword)
            signingConfigDsl.keyAlias(signingOptions.keyAlias)
            signingConfigDsl.keyPassword(signingOptions.keyPassword)
            signingOptions.storeType?.let {
                signingConfigDsl.storeType(it)
            }
            signingOptions.v1Enabled?.let {
                signingConfigDsl.enableV1Signing = it
            }
            signingOptions.v2Enabled?.let {
                signingConfigDsl.enableV2Signing = it
            }
            return signingConfigDsl
        }
        return null
    }

    private fun getLazyManifestParser(
        file: File,
        isManifestFileRequired: Boolean,
        isInExecutionPhase: BooleanSupplier
    ): LazyManifestParser {
        return lazyManifestParserMap.computeIfAbsent(
            file
        ) { f: File? ->
            LazyManifestParser(
                projectServices.objectFactory.fileProperty().fileValue(f),
                isManifestFileRequired,
                projectServices,
                isInExecutionPhase)
        }
    }

    private fun canParseManifest(): Boolean {
        return hasCreatedTasks || !dslServices.projectOptions[BooleanOption.DISABLE_EARLY_MANIFEST_PARSING]
    }

    fun setHasCreatedTasks(hasCreatedTasks: Boolean) {
        this.hasCreatedTasks = hasCreatedTasks
    }

    fun lockVariantProperties() {
        variantPropertiesApiServices.lockProperties()
    }

    fun finalizeAllVariants() {
        variants.forEach { variant ->
            variant.variant.artifacts.finalizeAndLock()
            ArtifactMetadataProcessor.wireAllFinalizedBy(variant.variant)
        }
        testComponents.forEach { testComponent ->
            testComponent.artifacts.finalizeAndLock()
        }
        testFixturesComponents.forEach { testFixturesComponent ->
            testFixturesComponent.artifacts.finalizeAndLock()
        }
    }

    init {
        signingOverride = createSigningOverride()
        variantFilter = VariantFilter(ReadOnlyObjectProvider())
        variantBuilderServices = VariantBuilderServicesImpl(projectServices)
        variantPropertiesApiServices = VariantServicesImpl(
            projectServices,
            // detects whether we are running the plugin under unit test mode
            forUnitTesting = project.hasProperty("_agp_internal_test_mode_")
        )
        taskCreationServices = TaskCreationServicesImpl(projectServices)
    }

    companion object {

        /**
         * Returns a modified name.
         *
         *
         * This name is used to request a missing dimension. It is the same name as the flavor that
         * sets up the request, which means it's not going to be matched, and instead it'll go to a
         * custom fallbacks provided by the flavor.
         *
         *
         * We are just modifying the name to avoid collision in case the same name exists in
         * different dimensions
         */
        fun getModifiedName(name: String): String {
            return "____$name"
        }

        internal fun checkInconsistentTestAppId(
            flavors: List<ProductFlavor>
        ): Boolean {
            if (flavors.isEmpty()) {
                return false
            }

            // as soon as one flavor declares an ID or a suffix, we bail.
            // There are possible corner cases where a project could have 2 flavors setting the same
            // appId in which case it would be safe to keep the current behavior but this is
            // unlikely to be a common case.
            for (flavor in flavors) {
                if (flavor.applicationId != null || flavor.applicationIdSuffix != null) {
                    return true
                }
            }

            return false
        }
    }
}

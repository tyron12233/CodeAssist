package com.tyron.builder.gradle.internal.variant

import com.android.ide.common.build.GenericBuiltArtifact
import com.android.ide.common.build.GenericBuiltArtifactsSplitOutputMatcher
import com.android.ide.common.build.GenericFilterConfiguration
import com.android.resources.Density
import com.google.common.base.Joiner
import com.google.common.base.Strings
import com.tyron.builder.VariantOutput
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.ApplicationBuildFeatures
import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.api.dsl.DataBinding
import com.tyron.builder.api.variant.ApplicationVariantBuilder
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.FilterConfiguration
import com.tyron.builder.api.variant.impl.*
import com.tyron.builder.core.ComponentTypeImpl
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.ApplicationVariantDslInfo
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.scope.*
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantBuilderServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.gradle.options.StringOption
import java.util.*
import java.util.function.Consumer

class ApplicationVariantFactory(
    dslServices: DslServices,
) : AbstractAppVariantFactory<ApplicationVariantBuilder, ApplicationVariantDslInfo, ApplicationCreationConfig>(
    dslServices,
) {

    override fun createVariantBuilder(
        globalVariantBuilderConfig: GlobalVariantBuilderConfig,
        componentIdentity: ComponentIdentity,
        variantDslInfo: ApplicationVariantDslInfo,
        variantBuilderServices: VariantBuilderServices
    ): ApplicationVariantBuilderImpl {

        return dslServices
            .newInstance(
                ApplicationVariantBuilderImpl::class.java,
                globalVariantBuilderConfig,
                variantDslInfo,
                componentIdentity,
                variantBuilderServices
            )
    }

    override fun createVariant(
        variantBuilder: ApplicationVariantBuilder,
        componentIdentity: ComponentIdentity,
        buildFeatures: BuildFeatureValues,
        variantDslInfo: ApplicationVariantDslInfo,
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
        ): ApplicationCreationConfig {
        val appVariant = dslServices
            .newInstance(
                ApplicationVariantImpl::class.java,
                variantBuilder,
                buildFeatures,
                variantDslInfo,
                variantDependencies,
                variantSources,
                paths,
                artifacts,
                variantData,
                taskContainer,
                variantBuilder.dependenciesInfo,
                transformManager,
                variantServices,
                taskCreationServices,
                globalConfig,
            )

        computeOutputs(appVariant, (variantData as ApplicationVariantData), globalConfig)

        return appVariant
    }

    override fun createBuildFeatureValues(
        buildFeatures: BuildFeatures,
        projectOptions: ProjectOptions
    ): BuildFeatureValues {
        buildFeatures as? ApplicationBuildFeatures
            ?: throw RuntimeException("buildFeatures not of type ApplicationBuildFeatures")

        return BuildFeatureValuesImpl(buildFeatures, projectOptions)
    }

    override fun createTestFixturesBuildFeatureValues(
        buildFeatures: BuildFeatures,
        projectOptions: ProjectOptions,
        androidResourcesEnabled: Boolean
    ): BuildFeatureValues {
        buildFeatures as? ApplicationBuildFeatures
            ?: throw RuntimeException("buildFeatures not of type ApplicationBuildFeatures")

        return TestFixturesBuildFeaturesValuesImpl(
            buildFeatures,
            projectOptions,
            androidResourcesEnabled
        )
    }

    override fun createUnitTestBuildFeatureValues(
        buildFeatures: BuildFeatures,
        dataBinding: DataBinding,
        projectOptions: ProjectOptions,
        includeAndroidResources: Boolean
    ): BuildFeatureValues {
        buildFeatures as? ApplicationBuildFeatures
            ?: throw RuntimeException("buildFeatures not of type ApplicationBuildFeatures")

        return UnitTestBuildFeaturesValuesImpl(
            buildFeatures,
            projectOptions,
            dataBindingOverride = if (!dataBinding.isEnabledForTests) {
                false
            } else {
                null // means whatever is default.
            },
            mlModelBindingOverride = false,
            includeAndroidResources = includeAndroidResources
        )
    }

    override fun createAndroidTestBuildFeatureValues(
        buildFeatures: BuildFeatures,
        dataBinding: DataBinding,
        projectOptions: ProjectOptions
    ): BuildFeatureValues {
        buildFeatures as? ApplicationBuildFeatures
            ?: throw RuntimeException("buildFeatures not of type ApplicationBuildFeatures")

        return AndroidTestBuildFeatureValuesImpl(
            buildFeatures,
            projectOptions,
            dataBindingOverride = if (!dataBinding.isEnabledForTests) {
                false
            } else {
                null // means whatever is default.
            },
            mlModelBindingOverride = false
        )
    }

    override val componentType
        get() = ComponentTypeImpl.BASE_APK

    private fun computeOutputs(
        appVariant: ApplicationCreationConfig,
        variant: ApplicationVariantData,
        globalConfig: GlobalTaskCreationConfig,
    ) {
        variant.calculateFilters(globalConfig.splits)
        val densities =
            variant.getFilters(VariantOutput.FilterType.DENSITY)
        val abis =
            variant.getFilters(VariantOutput.FilterType.ABI)
        checkSplitsConflicts(appVariant, abis, globalConfig)
        if (!densities.isEmpty()) {
            variant.compatibleScreens = globalConfig.splits.density
                .compatibleScreens
        }
        val variantOutputs =
            populateMultiApkOutputs(abis, densities, globalConfig)
        variantOutputs.forEach { appVariant.addVariantOutput(it) }
        restrictEnabledOutputs(
                appVariant,
                appVariant.outputs,
                globalConfig
        )
    }

    private fun populateMultiApkOutputs(
        abis: Set<String>,
        densities: Set<String>,
        globalConfig: GlobalTaskCreationConfig
    ): List<VariantOutputConfigurationImpl> {

        if (densities.isEmpty() && abis.isEmpty()) {
            // If both are empty, we will have only the main Apk.
            return listOf(VariantOutputConfigurationImpl())
        }
        val variantOutputs = mutableListOf<VariantOutputConfigurationImpl>()
        val universalApkForAbi =
            (globalConfig.splits.abi.isEnable && globalConfig.splits.abi.isUniversalApk)
        if (universalApkForAbi) {
            variantOutputs.add(
                VariantOutputConfigurationImpl(isUniversal = true)
            )
        } else {
            if (abis.isEmpty()) {
                variantOutputs.add(
                    VariantOutputConfigurationImpl(isUniversal = true)
                )
            }
        }
        if (abis.isNotEmpty()) { // TODO(b/117973371): Check if this is still needed/used, as BundleTool don't do this.
            // for each ABI, create a specific split that will contain all densities.
            abis.forEach(
                Consumer { abi: String ->
                    variantOutputs.add(
                        VariantOutputConfigurationImpl(
                            filters = listOf(
                                FilterConfigurationImpl(
                                    filterType = FilterConfiguration.FilterType.ABI,
                                    identifier = abi
                                )
                            )
                        )
                    )
                }
            )
        }
        // create its outputs
        for (density in densities) {
            if (abis.isNotEmpty()) {
                for (abi in abis) {
                    variantOutputs.add(
                        VariantOutputConfigurationImpl(
                            filters = listOf(
                                FilterConfigurationImpl(
                                    filterType = FilterConfiguration.FilterType.ABI,
                                    identifier = abi
                                ),
                                FilterConfigurationImpl(
                                    filterType = FilterConfiguration.FilterType.DENSITY,
                                    identifier = density
                                )
                            )
                        )
                    )
                }
            } else {
                variantOutputs.add(
                    VariantOutputConfigurationImpl(
                        filters = listOf(
                            FilterConfigurationImpl(
                                filterType = FilterConfiguration.FilterType.DENSITY,
                                identifier = density
                            )
                        )
                    )
                )
            }
        }
        return variantOutputs
    }

    private fun checkSplitsConflicts(
        component: ApplicationCreationConfig,
        abiFilters: Set<String?>,
        globalConfig: GlobalTaskCreationConfig,
    ) { // if we don't have any ABI splits, nothing is conflicting.
        if (abiFilters.isEmpty()) {
            return
        }
        // if universalAPK is requested, abiFilter will control what goes into the universal APK.
        if (globalConfig.splits.abi.isUniversalApk) {
            return
        }
        // check supportedAbis in Ndk configuration versus ABI splits.
//        val ndkConfigAbiFilters = component.ndkConfig.abiFilters
//        if (ndkConfigAbiFilters.isEmpty()) {
//            return
//        }
        if (true) {
            return;
        }
        // if we have any ABI splits, whether it's a full or pure ABI splits, it's an error.
        val issueReporter = dslServices.issueReporter
        issueReporter.reportError(
            IssueReporter.Type.GENERIC, String.format(
                "Conflicting configuration : '%1\$s' in ndk abiFilters "
                        + "cannot be present when splits abi filters are set : %2\$s",
//                Joiner.on(",").join(ndkConfigAbiFilters),
                Joiner.on(",").join(abiFilters)
            )
        )
    }

    private fun restrictEnabledOutputs(
        component: ApplicationCreationConfig,
        variantOutputs: VariantOutputList,
        globalConfig: GlobalTaskCreationConfig
    ) {
        val supportedAbis: Set<String> = component.supportedAbis
        val projectOptions = dslServices.projectOptions
        val buildTargetAbi =
            (if (projectOptions[BooleanOption.BUILD_ONLY_TARGET_ABI]
                || globalConfig.splits.abi.isEnable
            ) projectOptions[StringOption.IDE_BUILD_TARGET_ABI] else null)
                ?: return
        val buildTargetDensity =
            projectOptions[StringOption.IDE_BUILD_TARGET_DENSITY]
        val density = Density.getEnum(buildTargetDensity)
        val genericBuiltArtifacts = variantOutputs
            .map { variantOutput ->
                GenericBuiltArtifact(
                    outputType = variantOutput.outputType.name,
                    filters = variantOutput.filters.map { filter -> GenericFilterConfiguration(filter.filterType.name, filter.identifier) },
                    // this is wrong, talk to xav@, we cannot continue supporting this.
                    versionCode = variantOutput.versionCode.orNull,
                    versionName = variantOutput.versionName.orNull,
                    outputFile = "not_provided"
                ) to variantOutput
            }
            .toMap()
        val computedBestArtifact = GenericBuiltArtifactsSplitOutputMatcher.computeBestArtifact(
            genericBuiltArtifacts.keys,
            supportedAbis,
            Arrays.asList(
                *Strings.nullToEmpty(
                    buildTargetAbi
                ).split(",".toRegex()).toTypedArray()
            )
        )
        if (computedBestArtifact == null) {
            val splits = variantOutputs
                .map { obj: com.tyron.builder.api.variant.VariantOutput -> obj.filters }
                .map { filters: Collection<FilterConfiguration> ->
                    filters.joinToString(",")
                }
            dslServices
                .issueReporter
                .reportWarning(
                    IssueReporter.Type.GENERIC, String.format(
                        "Cannot build selected target ABI: %1\$s, "
                                + if (splits.isEmpty()) "no suitable splits configured: %2\$s;" else "supported ABIs are: %2\$s",
                        buildTargetAbi,
                        if (supportedAbis.isEmpty()) Joiner.on(", ").join(splits)
                        else Joiner.on(", ").join(supportedAbis)
                    )
                )
            // do not disable anything, build all and let the apk install figure it out.
            return
        }
        genericBuiltArtifacts.forEach { key: GenericBuiltArtifact, value: VariantOutputImpl ->
            if (key != computedBestArtifact) value.enabled.set(false)
        }
    }
}

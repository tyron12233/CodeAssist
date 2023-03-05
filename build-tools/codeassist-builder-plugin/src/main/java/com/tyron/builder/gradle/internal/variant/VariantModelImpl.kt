package com.tyron.builder.gradle.internal.variant
import com.google.common.base.Joiner
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableMap
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.errors.SyncIssueReporter
import com.tyron.builder.gradle.internal.SdkComponentsBuildService
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.dsl.BuildType
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.model.v2.ide.ProjectType
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

class VariantModelImpl(
    override val inputs: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>,
    private val testBuilderTypeProvider: () -> String?,
    private val variantProvider: () -> List<VariantCreationConfig>,
    private val testComponentProvider: () -> List<TestComponentCreationConfig>,
    private val buildFeaturesProvider: () -> BuildFeatureValues,
    override val projectTypeV1: Int,
    override val projectType: ProjectType,
    private val globalConfig: GlobalTaskCreationConfig,
) : VariantModel {

    override val projectOptions: ProjectOptions
        get() = globalConfig.services.projectOptions

    override val syncIssueReporter: SyncIssueReporter
        get() = globalConfig.services.issueReporter as SyncIssueReporter

    override val variants: List<VariantCreationConfig>
        get() = variantProvider()

    override val testComponents: List<TestComponentCreationConfig>
        get() = testComponentProvider()

    override val defaultVariant: String?
        get() = computeDefaultVariant()

    override val buildFeatures: BuildFeatureValues
        get() = buildFeaturesProvider()

    override val versionedSdkLoader: Provider<SdkComponentsBuildService.VersionedSdkLoader>
        get() = globalConfig.versionedSdkLoader

    override val mockableJarArtifact: FileCollection
        get() = globalConfig.mockableJarArtifact

    override val filteredBootClasspath: Provider<List<RegularFile>>
        get() = globalConfig.filteredBootClasspath

    override val globalArtifacts: ArtifactsImpl
        get() = globalConfig.globalArtifacts

    /**
     * Calculates the default variant to put in the model.
     *
     *
     * Given user preferences, this attempts to respect them in the presence of the variant
     * filter.
     *
     *
     * This prioritizes by, in descending order of preference:
     *
     *
     *  - The build author's explicit build type settings
     *  - The build author's explicit product flavor settings, matching the highest number of
     * chosen defaults
     *  - The fallback default build type, which is the tested build type, if applicable,
     * otherwise 'debug'
     *  - The alphabetically sorted default product flavors, left to right
     *
     *
     * @return the name of a variant that exists under the presence of the variant filter. Only
     * returns null if all variants are removed.
     */
    private fun computeDefaultVariant(): String? {
        // Finalize the DSL we are about to read.
        finalizeDefaultVariantDsl()

        // Exit early if all variants were filtered out, this is not a valid project
        if (variants.isEmpty()) {
            return null
        }
        // Otherwise get the 'best' build type, respecting the user's preferences first.
        val chosenBuildType: String? = getBuildAuthorSpecifiedDefaultBuildType()
        val chosenFlavors: Map<String, String> = getBuildAuthorSpecifiedDefaultFlavors()
        val fallbackDefaultBuildType: String = testBuilderTypeProvider() ?: "debug"
        val preferredDefaultVariantScopeComparator: Comparator<ComponentCreationConfig> =
            BuildAuthorSpecifiedDefaultBuildTypeComparator(chosenBuildType)
                .thenComparing(BuildAuthorSpecifiedDefaultsFlavorComparator(chosenFlavors))
                .thenComparing(DefaultBuildTypeComparator(fallbackDefaultBuildType))
                .thenComparing(DefaultFlavorComparator())

        // Ignore test, base feature and feature variants.
        // * Test variants have corresponding production variants
        // * Hybrid feature variants have corresponding library variants.
        val defaultComponent = variants.minWithOrNull(preferredDefaultVariantScopeComparator)

        return defaultComponent?.name
    }

    /** Prevent any subsequent modifications to the default variant DSL properties.  */
    private fun finalizeDefaultVariantDsl() {
        for (buildTypeData in inputs.buildTypes.values) {
            buildTypeData.buildType.getIsDefault().finalizeValue()
        }
        for (productFlavorData in inputs.productFlavors.values) {
            productFlavorData.productFlavor.getIsDefault().finalizeValue()
        }
    }


    /**
     * Computes explicit build-author default build type.
     *
     * @return user specified default build type, null if none set.
     */
    private fun getBuildAuthorSpecifiedDefaultBuildType(): String? {
        // First look for the user setting
        val buildTypesMarkedAsDefault: MutableList<String> = ArrayList(1)
        for (buildType in inputs.buildTypes.values) {
            if (buildType.buildType.isDefault) {
                buildTypesMarkedAsDefault.add(buildType.buildType.name)
            }
        }

        buildTypesMarkedAsDefault.sort()

        if (buildTypesMarkedAsDefault.size > 1) {
            globalConfig.services.issueReporter.reportWarning(
                IssueReporter.Type.AMBIGUOUS_BUILD_TYPE_DEFAULT,
                "Ambiguous default build type: '"
                        + Joiner.on("', '").join(buildTypesMarkedAsDefault)
                        + "'.\n"
                        + "Please only set `isDefault = true` for one build type.",
                Joiner.on(',').join(buildTypesMarkedAsDefault)
            )
        }

        return if (buildTypesMarkedAsDefault.isEmpty()) {
            null
        } else {
            // This picks the first alphabetically that was tagged, to make it stable,
            // even if the user accidentally tags two build types as default.
            buildTypesMarkedAsDefault[0]
        }
    }

    /**
     * Computes explicit user set default product flavors for each dimension.
     *
     * @param syncIssueHandler any configuration issues will be added here, e.g. if multiple flavors
     * in one dimension are marked as default.
     * @return map from flavor dimension to the user-specified default flavor for that dimension,
     * with entries missing for flavors without user-specified defaults.
     */
    private fun getBuildAuthorSpecifiedDefaultFlavors(): Map<String, String> {
        // Using ArrayListMultiMap to preserve sorting of flavor names.
        val userDefaults = ArrayListMultimap.create<String, String>()

        for (flavor in inputs.productFlavors.values) {
            val productFlavor = flavor.productFlavor
            val dimension = productFlavor.dimension

            @Suppress("DEPRECATION")
            if (productFlavor.getIsDefault().get()) {
                userDefaults.put(dimension, productFlavor.name)
            }
        }
        val defaults = ImmutableMap.builder<String, String>()

        // For each user preference, validate it and override the alphabetical default.
        for (dimension in userDefaults.keySet()) {
            val userDefault = userDefaults[dimension]

            userDefault.sort()

            if (userDefault.isNotEmpty()) {
                // This picks the first alphabetically that was tagged, to make it stable,
                // even if the user accidentally tags two flavors in the same dimension as default.
                defaults.put(dimension, userDefault[0])
            }

            // Report the ambiguous default setting.
            if (userDefault.size > 1) {
                globalConfig.services.issueReporter.reportWarning(
                                IssueReporter.Type.AMBIGUOUS_PRODUCT_FLAVOR_DEFAULT,
"""Ambiguous default product flavors for flavor dimension '$dimension': '${Joiner.on("', '").join(userDefault)}'.
Please only set `isDefault = true` for one product flavor in each flavor dimension.""",
                                dimension
                            )
            }
        }
        return defaults.build()
    }
}

/**
 * Compares variants prioritizing those that match the given default build type.
 *
 *
 * The best match is the *minimum* element.
 *
 *
 * Note: this comparator imposes orderings that are inconsistent with equals, as variants
 * that do not match the default will compare the same.
 */
private class BuildAuthorSpecifiedDefaultBuildTypeComparator constructor(
    private val chosen: String?
) : Comparator<ComponentCreationConfig> {
    override fun compare(v1: ComponentCreationConfig, v2: ComponentCreationConfig): Int {
        if (chosen == null) {
            return 0
        }
        val b1Score = if (v1.buildType == chosen) 1 else 0
        val b2Score = if (v2.buildType == chosen) 1 else 0
        return b2Score - b1Score
    }

}

/**
 * Compares variants prioritizing those that match the given default flavors over those that do
 * not.
 *
 *
 * The best match is the *minimum* element.
 *
 *
 * Note: this comparator imposes orderings that are inconsistent with equals, as variants
 * that do not match the default will compare the same.
 */
private class BuildAuthorSpecifiedDefaultsFlavorComparator constructor(
    private val defaultFlavors: Map<String, String>
) : Comparator<ComponentCreationConfig> {
    override fun compare(v1: ComponentCreationConfig, v2: ComponentCreationConfig): Int {
        var f1Score = 0
        var f2Score = 0
        for (flavor in v1.productFlavorList) {
            if (flavor.name == defaultFlavors[flavor.dimension]) {
                f1Score++
            }
        }
        for (flavor in v2.productFlavorList) {
            if (flavor.name == defaultFlavors[flavor.dimension]) {
                f2Score++
            }
        }
        return f2Score - f1Score
    }

}

/**
 * Compares variants on build types.
 *
 *
 * Prefers 'debug', then falls back to the first alphabetically.
 *
 *
 * The best match is the *minimum* element.
 */
private class DefaultBuildTypeComparator constructor(
    private val preferredBuildType: String
) : Comparator<ComponentCreationConfig> {
    override fun compare(v1: ComponentCreationConfig, v2: ComponentCreationConfig): Int {
        val b1 = v1.buildType
        val b2 = v2.buildType
        return if (b1 == b2) {
            0
        } else if (b1 == preferredBuildType) {
            -1
        } else if (b2 == preferredBuildType) {
            1
        } else {
            b1!!.compareTo(b2!!)
        }
    }
}

/**
 * Compares variants prioritizing product flavors alphabetically, left-to-right.
 *
 *
 * The best match is the *minimum* element.
 */
private class DefaultFlavorComparator : Comparator<ComponentCreationConfig> {
    override fun compare(v1: ComponentCreationConfig, v2: ComponentCreationConfig): Int {
        // Compare flavors left-to right.
        for (i in v1.productFlavorList.indices) {
            val f1 = v1.productFlavorList[i].name
            val f2 = v2.productFlavorList[i].name
            val diff = f1.compareTo(f2)
            if (diff != 0) {
                return diff
            }
        }
        return 0
    }
}

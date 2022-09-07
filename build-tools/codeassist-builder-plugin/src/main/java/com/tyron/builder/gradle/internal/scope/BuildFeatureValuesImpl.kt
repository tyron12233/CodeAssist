package com.tyron.builder.gradle.internal.scope
import com.tyron.builder.api.dsl.ApplicationBuildFeatures
import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.api.dsl.DynamicFeatureBuildFeatures
import com.tyron.builder.api.dsl.LibraryBuildFeatures
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.ProjectOptions

open class BuildFeatureValuesImpl constructor(
    buildFeatures: BuildFeatures,
    projectOptions: ProjectOptions,
    dataBindingOverride: Boolean? = null,
    mlModelBindingOverride: Boolean? = null
) : BuildFeatureValues {

    // add new flags here with computation:
    // dslFeatures.flagX ?: projectOptions[BooleanOption.FlagX]

    // ------------------
    // Common flags

    override val aidl: Boolean = buildFeatures.aidl ?: projectOptions[BooleanOption.BUILD_FEATURE_AIDL]

    override val compose: Boolean = buildFeatures.compose ?: false

    override val buildConfig: Boolean = buildFeatures.buildConfig ?: projectOptions[BooleanOption.BUILD_FEATURE_BUILDCONFIG]

    override val prefab: Boolean = buildFeatures.prefab ?: false

    override val androidResources: Boolean =  when (buildFeatures) {
        is LibraryBuildFeatures -> buildFeatures.androidResources ?: projectOptions[BooleanOption.BUILD_FEATURE_ANDROID_RESOURCES]
        else -> true
    }

    private val _renderScript = buildFeatures.renderScript ?:
    projectOptions[BooleanOption.BUILD_FEATURE_RENDERSCRIPT]

    override val renderScript: Boolean
        get() = androidResources && _renderScript


    private val _resValues: Boolean =
        buildFeatures.resValues ?: projectOptions[BooleanOption.BUILD_FEATURE_RESVALUES]

    override val resValues: Boolean
        get() = androidResources && _resValues

    override val shaders: Boolean = buildFeatures.shaders ?: projectOptions[BooleanOption.BUILD_FEATURE_SHADERS]

    private val _viewBinding: Boolean = buildFeatures.viewBinding ?: projectOptions[BooleanOption.BUILD_FEATURE_VIEWBINDING]
    override val viewBinding: Boolean
        get() = androidResources && _viewBinding

    // ------------------
    // Application flags

    // ------------------
    // Dynamic-Feature flags

    // ------------------
    // Library flags

    override val buildType: Boolean = true

    override val prefabPublishing: Boolean = when (buildFeatures) {
        is LibraryBuildFeatures -> buildFeatures.prefabPublishing ?: false
        else -> false
    }

    // ------------------
    // Test flags

    // ------------------
    // Application / dynamic-feature / library flags

    override val mlModelBinding: Boolean = mlModelBindingOverride
        ?: when (buildFeatures) {
            is ApplicationBuildFeatures -> {
                buildFeatures.mlModelBinding
            }
            is LibraryBuildFeatures -> {
                buildFeatures.mlModelBinding
            }
            is DynamicFeatureBuildFeatures -> {
                buildFeatures.mlModelBinding
            }
            else -> null
        }
        ?: projectOptions[BooleanOption.BUILD_FEATURE_MLMODELBINDING]

    private val _dataBinding = (dataBindingOverride
        ?: when (buildFeatures) {
            is ApplicationBuildFeatures -> {
                buildFeatures.dataBinding
            }
            is LibraryBuildFeatures -> {
                buildFeatures.dataBinding
            }
            is DynamicFeatureBuildFeatures -> {
                buildFeatures.dataBinding
            }
            else -> null
        }
        ?: projectOptions[BooleanOption.BUILD_FEATURE_DATABINDING])

    override val dataBinding: Boolean
        get() = androidResources && _dataBinding
}

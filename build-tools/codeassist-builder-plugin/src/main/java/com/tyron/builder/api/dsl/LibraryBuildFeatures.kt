package com.tyron.builder.api.dsl

/**
 * A list of build features that can be disabled or enabled in an Android Library project.
 */
interface LibraryBuildFeatures : BuildFeatures {
    /**
     * Flag to disable Android resource processing.
     *
     * Setting the value to 'null' resets to the default value.
     * Default value is 'true'.
     *
     * You can override the default for this for all projects in your build by adding the line
     *     `android.library.defaults.buildfeatures.androidresources=false`
     * in the gradle.properties file at the root project of your build.
     *
     * Once set to 'false', flag disables
     * [com.android.build.api.dsl.LibraryBuildFeatures.dataBinding],
     * [com.android.build.api.dsl.BuildFeatures.viewBinding],
     * [com.android.build.api.dsl.BuildFeatures.renderScript].
     *
     * More information about this feature at: TBD
     */
    var androidResources: Boolean?

    /**
     * Flag to enable Data Binding.
     *
     * Setting the value to `null` resets to the default value.
     * Default value is `false`.
     *
     * You can override the default for this for all projects in your build by adding the line
     *     `android.defaults.buildfeatures.databinding=true`
     * in the `gradle.properties` file at the root project of your build.
     *
     * More information about this feature at: TBD
     */
    var dataBinding: Boolean?

    /**
     * Flag to enable Machine Learning Model Binding.
     *
     * Setting the value to `null` resets to the default value.
     * Default value is `false`.
     *
     * You can override the default for this for all projects in your build by adding the line
     *     `android.defaults.buildfeatures.mlmodelbinding=true`
     * in the `gradle.properties` file at the root project of your build.
     *
     * More information about this feature at: TBD
     */
    var mlModelBinding: Boolean?

    /**
     * Flag to enable generating Prefab packages for AARs.
     *
     * Setting the value to `null` resets to the default value.
     * Default value is `false`.
     *
     * You can override the default for this for all projects in your build by adding the line
     *     `android.defaults.buildfeatures.prefabPublishing=true`
     * in the `gradle.properties` file at the root project of your build.
     *
     * More information about this feature at: https://developer.android.com/studio/build/native-dependencies
     */
    var prefabPublishing: Boolean?
}

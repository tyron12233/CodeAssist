package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * A list of build features that can be disabled or enabled in an Android Dynamic Feature project.
 */
interface DynamicFeatureBuildFeatures : BuildFeatures {

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
    @get:Incubating
    @set:Incubating
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
    @get:Incubating
    @set:Incubating
    var mlModelBinding: Boolean?
}
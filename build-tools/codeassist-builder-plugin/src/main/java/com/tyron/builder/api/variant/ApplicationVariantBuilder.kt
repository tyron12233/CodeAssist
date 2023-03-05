package com.tyron.builder.api.variant

/**
 * Application specific variant object that contains properties that will determine the variant's
 * build flow.
 *
 * For example, an application variant may have minification on or off, or have a different
 * minSdkVersion from the other variants.
 *
 * All these properties must be resolved during configuration time as [org.gradle.api.Task]
 * representing the variant build flows must be created.
 */
interface ApplicationVariantBuilder : VariantBuilder,
    HasAndroidTestBuilder,
    HasTestFixturesBuilder,
    GeneratesApkBuilder,
    CanMinifyCodeBuilder,
    CanMinifyAndroidResourcesBuilder{

    val debuggable: Boolean

    /** Specify whether to include SDK dependency information in APKs and Bundles. */
    val dependenciesInfo: DependenciesInfoBuilder
}

package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Extension for the Android Gradle Plugin Application plugin.
 *
 * This is the `android` block when the `com.android.application` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
 */
interface ApplicationExtension :
    CommonExtension<
            ApplicationBuildFeatures,
            ApplicationBuildType,
            ApplicationDefaultConfig,
            ApplicationProductFlavor>,
    ApkExtension,
    TestedExtension
{
    // TODO(b/140406102)

    /** Specify whether to include SDK dependency information in APKs and Bundles. */
    val dependenciesInfo: DependenciesInfo

    /** Specify whether to include SDK dependency information in APKs and Bundles. */
    fun dependenciesInfo(action: DependenciesInfo.() -> Unit)

    val bundle: Bundle

    fun bundle(action: Bundle.() -> Unit)

    @get:Incubating
    val dynamicFeatures: MutableSet<String>

    /**
     * Set of asset pack subprojects to be included in the app's bundle.
     */
    @get:Incubating
    val assetPacks: MutableSet<String>

    /**
     * Customizes publishing build variant artifacts from app module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [ApplicationPublishing]
     */
    val publishing: ApplicationPublishing

    /**
     * Customizes publishing build variant artifacts from app module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [ApplicationPublishing]
     */
    fun publishing(action: ApplicationPublishing.() -> Unit)
}
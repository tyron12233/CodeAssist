package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.provider.Property

/**
 * Properties for the main Variant of an application.
 */
interface ApplicationVariant : GeneratesApk,
    Variant,
    HasAndroidTest,
    HasTestFixtures,
    CanMinifyCode,
    CanMinifyAndroidResources {

    /**
     * Variant's application ID as present in the final manifest file of the APK.
     *
     * Setting this value will override anything set via the DSL with
     * [com.tyron.builder.api.dsl.ApplicationBaseFlavor.applicationId], and
     * [com.tyron.builder.api.dsl.ApplicationVariantDimension.applicationIdSuffix]
     */
    override val applicationId: Property<String>

    /**
     * Returns the final list of variant outputs.
     * @return read only list of [VariantOutput] for this variant.
     */
    val outputs: List<VariantOutput>

    /** Specify whether to include SDK dependency information in APKs and Bundles. */
    val dependenciesInfo: DependenciesInfo

    /**
     * Variant's signingConfig, initialized by the corresponding DSL element.
     * @return Variant's config or null if the variant is not configured for signing.
     */
    val signingConfig: SigningConfig

    /**
     * Variant's information related to the bundle creation configuration.
     * @return Variant's [BundleConfig].
     */
    @get:Incubating
    val bundleConfig: BundleConfig
}

package com.tyron.builder.api.variant

import org.gradle.api.provider.Provider

/**
 * Cross cutting interface for [Component] subtypes that are producing APK files.
 */
interface GeneratesApk {

    /**
     * Variant's application ID as present in the final manifest file of the APK.
     */
    val applicationId: Provider<String>

    /**
     * Variant's android resources processing configuration, initialized by the corresponding
     * global DSL element.
     */
    val androidResources: AndroidResources

    /**
     * Variant's packagingOptions, initialized by the corresponding global DSL element.
     */
    val packaging: ApkPackaging

    /**
     * Variant specific settings for the renderscript compiler. This will return null when
     * [com.android.build.api.dsl.BuildFeatures.renderScript] is false.
     */
    val renderscript: Renderscript?

    /**
     * Target SDK version for this variant.
     */
    val targetSdkVersion: AndroidVersion
}

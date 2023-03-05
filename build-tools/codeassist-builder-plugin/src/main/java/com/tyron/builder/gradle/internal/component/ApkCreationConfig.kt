package com.tyron.builder.gradle.internal.component

import com.tyron.builder.api.variant.ApkPackaging
import com.tyron.builder.api.variant.impl.BundleConfigImpl
import com.tyron.builder.api.variant.impl.SigningConfigImpl
import java.io.File

/**
 * Interface for properties common to all variant generating APKs
 */
interface ApkCreationConfig: ConsumableCreationConfig {

    val embedsMicroApp: Boolean

    // TODO: move to a non variant object (GlobalTaskScope?)
    val testOnlyApk: Boolean

    /** If this variant should package desugar_lib DEX in the final APK. */
    val shouldPackageDesugarLibDex: Boolean

    /**
     * If this variant should package additional dependencies (code and native libraries) needed for
     * profilers support in the IDE.
     */
    val shouldPackageProfilerDependencies: Boolean

    /** List of transforms for profilers support in the IDE. */
    val advancedProfilingTransforms: List<String>

    override val packaging: ApkPackaging

    /**
     * Variant's signing information of null if signing is not configured for this variant.
     */
    val signingConfigImpl: SigningConfigImpl?

    val multiDexKeepFile: File?

    val bundleConfig: BundleConfigImpl?
        get() = null

    val useJacocoTransformInstrumentation: Boolean

    val packageJacocoRuntime: Boolean
}
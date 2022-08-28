package com.tyron.builder.gradle.internal.component

import com.tyron.builder.api.variant.AndroidVersion
import com.tyron.builder.gradle.internal.scope.Java8LangSupport

/**
 * CreationConfig for variants that produces an artifact that is directly install-able to devices
 * like APKs or AABs or used by other projects as a versioned reusable logic like AARs.
 */
interface ConsumableCreationConfig : ComponentCreationConfig {

    /**
     * Returns the minimum SDK version for which is used for dexing this variant.
     * See [ApkCreationConfigImpl.minSdkVersionForDexing] for details.
     */
    val minSdkVersionForDexing: AndroidVersion

    val isMultiDexEnabled: Boolean

    val isCoreLibraryDesugaringEnabled: Boolean

    fun getJava8LangSupportType(): Java8LangSupport = Java8LangSupport.D8

    /**
     * Returns if we need to shrink desugar lib when desugaring Core Library.
     */
    val needsShrinkDesugarLibrary: Boolean
}
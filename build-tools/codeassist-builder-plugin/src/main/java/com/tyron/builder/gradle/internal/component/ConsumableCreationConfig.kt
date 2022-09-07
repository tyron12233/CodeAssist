package com.tyron.builder.gradle.internal.component

import com.tyron.builder.api.component.impl.ApkCreationConfigImpl
import com.tyron.builder.api.variant.AndroidVersion
import com.tyron.builder.api.variant.Packaging
import com.tyron.builder.dexing.DexingType
import com.tyron.builder.gradle.internal.PostprocessingFeatures
import com.tyron.builder.gradle.internal.component.features.RenderscriptCreationConfig
import com.tyron.builder.gradle.internal.scope.Java8LangSupport
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider

/**
 * CreationConfig for variants that produces an artifact that is directly install-able to devices
 * like APKs or AABs or used by other projects as a versioned reusable logic like AARs.
 */
interface ConsumableCreationConfig: ComponentCreationConfig {
    val renderscriptCreationConfig: RenderscriptCreationConfig?

    val packaging: Packaging

    /**
     * Returns the minimum SDK version for which is used for dexing this variant.
     * See [ApkCreationConfigImpl.minSdkVersionForDexing] for details.
     */
    val minSdkVersionForDexing: AndroidVersion

    val isMultiDexEnabled: Boolean

    val isCoreLibraryDesugaringEnabled: Boolean

    val proguardFiles: ListProperty<RegularFile>

    /**
     * Returns the component ids of those library dependencies whose keep rules are ignored when
     * building the project.
     */
    val ignoredLibraryKeepRules: Provider<Set<String>>

    /**
     * Returns whether to ignore all keep rules from external library dependencies.
     */
    val ignoreAllLibraryKeepRules: Boolean

    val dexingType: DexingType

    val minifiedEnabled: Boolean
    val resourcesShrink: Boolean

    /** Returns whether we need to create a stream from the merged java resources */
    val needsMergedJavaResStream: Boolean

    fun getJava8LangSupportType(): Java8LangSupport

    val postProcessingFeatures: PostprocessingFeatures?

    /**
     * Returns if we need to shrink desugar lib when desugaring Core Library.
     */
    val needsShrinkDesugarLibrary: Boolean

    val needsMainDexListForBundle: Boolean

    val defaultGlslcArgs: List<String>

    val scopedGlslcArgs: Map<String, List<String>>

    val manifestPlaceholders: MapProperty<String, String>

    val isAndroidTestCoverageEnabled: Boolean
}

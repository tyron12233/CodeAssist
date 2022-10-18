package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.api.variant.BuildConfigField
import java.io.File
import java.io.Serializable

/**
 * Contains the final dsl info computed from the DSL object model (extension, default config,
 * build type, flavors) that are needed by consumable components.
 */
interface ConsumableComponentDslInfo: ComponentDslInfo {
    val renderscriptTarget: Int

    // Only require specific multidex opt-in for legacy multidex.
    val isMultiDexEnabled: Boolean?

    val multiDexKeepProguard: File?

    val multiDexKeepFile: File?

    /**
     * Returns the API to which device/emulator we're deploying via the IDE or null if not.
     * Can be used to optimize some build steps when deploying via the IDE.
     *
     * This has no relation with targetSdkVersion from build.gradle/manifest.
     */
    val targetDeployApiFromIDE: Int?

    /** Returns the renderscript support mode.  */
    val renderscriptSupportModeEnabled: Boolean

    /** Returns the renderscript BLAS support mode.  */
    val renderscriptSupportModeBlasEnabled: Boolean

    /** Returns the renderscript NDK mode.  */
    val renderscriptNdkModeEnabled: Boolean

    val renderscriptOptimLevel: Int

    val defaultGlslcArgs: List<String>

    val scopedGlslcArgs: Map<String, List<String>>

    /**
     * Returns the component ids of those external library dependencies whose keep rules are ignored
     * when building the project.
     */
    val ignoredLibraryKeepRules: Set<String>

    /**
     * Returns whether to ignore all keep rules from external library dependencies.
     */
    val ignoreAllLibraryKeepRules: Boolean

    /**
     * Returns a list of items for the BuildConfig class.
     *
     *
     * Items can be either fields (instance of [com.android.builder.model.ClassField]) or
     * comments (instance of String).
     *
     * @return a list of items.
     */
    fun getBuildConfigFields(): Map<String, BuildConfigField<out Serializable>>

    /**
     * Returns the merged manifest placeholders. All product flavors are merged first, then build
     * type specific placeholders are added and potentially overrides product flavors values.
     *
     * @return the merged manifest placeholders for a build variant.
     */
    val manifestPlaceholders: Map<String, String>
}
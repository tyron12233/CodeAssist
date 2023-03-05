package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * DSL object for configuring per-density splits options.
 *
 * See [APK Splits](https://developer.android.com/studio/build/configure-apk-splits.html).
 */
interface DensitySplit : Split {
    /** TODO: Document. */
    @get:Incubating
    @set:Incubating
    var isStrict: Boolean

    /**
     * A list of compatible screens.
     *
     * This will inject a matching `<compatible-screens><screen ...>` node in the manifest.
     * This is optional.
     */
    @get:Incubating
    val compatibleScreens: MutableSet<String>

    /** Adds a new compatible screen. */
    @Incubating
    fun compatibleScreens(vararg sizes: String)
}
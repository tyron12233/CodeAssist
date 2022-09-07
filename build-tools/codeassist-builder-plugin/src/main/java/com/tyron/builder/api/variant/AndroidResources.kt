package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.provider.ListProperty

/**
 * Defines a variant's aapt options.
 */
interface AndroidResources {

    /**
     * The list of patterns describing assets to be ignored.
     *
     * See aapt's --ignore-assets flag via `aapt --help`. Note: the --ignore-assets flag accepts a
     * single string of colon-delimited patterns, whereas this property is a list of patterns.
     *
     * This property will be initialized using the corresponding DSL value.
     */
    val ignoreAssetsPatterns: ListProperty<String>

    /**
     * The list of additional parameters to pass to aapt.
     *
     * This property will be initialized using the corresponding DSL value.
     */
    val aaptAdditionalParameters: ListProperty<String>

    /**
     * File extensions of Android resources, assets, and Java resources to be stored uncompressed in
     * the APK. Adding an empty extension (e.g., `noCompress.add("")`) will disable compression for
     * all Android resources, assets, and Java resources.
     */
    @get:Incubating
    val noCompress: ListProperty<String>
}

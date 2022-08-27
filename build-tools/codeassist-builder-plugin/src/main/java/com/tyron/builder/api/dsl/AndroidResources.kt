package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/** DSL object for configuring aapt options. */
interface AndroidResources {
    /**
     * Pattern describing assets to be ignored.
     *
     * This is [ignoreAssetsPatterns] joined by ':'.
     */
    var ignoreAssetsPattern: String?

    /**
     * Patterns describing assets to be ignored.
     *
     * If empty, defaults to `["!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~"]`
     */
    val ignoreAssetsPatterns: MutableCollection<String>

    /**
     * File extensions of Android resources, assets, and Java resources to be stored uncompressed in
     * the APK. Adding an empty extension (e.g., setting `noCompress ''`) will disable compression
     * for all Android resources, assets, and Java resources.
     */
    val noCompress: MutableCollection<String>

    /**
     * Adds a file extension of Android resources, assets, and Java resources to be stored
     * uncompressed in the APK. Adding an empty extension (i.e., `noCompress('')`) will disable
     * compression for all Android resources, assets, and Java resources.
     */
    @Incubating
    fun noCompress(noCompress: String)

    /**
     * Adds file extensions of Android resources, assets, and Java resources to be stored
     * uncompressed in the APK. Adding an empty extension (e.g., `noCompress('')`) will disable
     * compression for all Android resources, assets, and Java resources.
     */
    @Incubating
    fun noCompress(vararg noCompress: String)

    /**
     * Forces aapt to return an error if it fails to find an entry for a configuration.
     *
     * See `aapt --help`
     */
    var failOnMissingConfigEntry: Boolean

    /** List of additional parameters to pass to `aapt`. */
    @get:Incubating
    val additionalParameters: MutableList<String>

    /** Adds additional parameters to be passed to `aapt`. */
    @Incubating
    fun additionalParameters(params: String)

    /** Adds additional parameters to be passed to `aapt`. */
    @Incubating
    fun additionalParameters(vararg params: String)

    /**
     * Indicates whether the resources in this sub-project are fully namespaced.
     *
     * This property is incubating and may change in a future release.
     */
    @get:Incubating
    @set:Incubating
    var namespaced: Boolean
}
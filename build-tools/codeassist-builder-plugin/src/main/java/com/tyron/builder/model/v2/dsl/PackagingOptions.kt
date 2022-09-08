package com.tyron.builder.model.v2.dsl

import com.tyron.builder.model.v2.AndroidModel

/**
 * Options for APK packaging.
 *
 * @since 4.2
 */
interface PackagingOptions: AndroidModel {
    /**
     * Glob patterns to exclude from packaging.
     *
     * @return a set of glob pattern that use forward slash as a separator
     */
    val excludes: Set<String>

    /**
     * Glob patterns to pick first.
     *
     * @return a set of glob pattern that use forward slash as a separator
     */
    val pickFirsts: Set<String>

    /**
     * Glob patterns to merge.
     *
     * @return a set of glob pattern that use forward slash as a separator
     */
    val merges: Set<String>

    /**
     * Glob patterns to exclude native libraries from being stripped.
     *
     * @return a set of glob pattern that use forward slash as a separator
     */
    val doNotStrip: Set<String>
}

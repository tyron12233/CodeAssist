package com.tyron.builder.model

/**
 * tooling model representation of resolved dependenciesInfo from the DSL
 * @see `com.android.build.api.dsl.DependenciesInfo`
 */
interface DependenciesInfo {
    val includeInApk: Boolean
    val includeInBundle: Boolean
}
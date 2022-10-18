package com.tyron.builder.model.v2.dsl

import com.tyron.builder.model.v2.AndroidModel

/**
 * Information about whether the dependencies are packaged in the apk/bundle.
 *
 * @see `com.android.build.api.dsl.DependenciesInfo`
 *
 * @since 4.2
 */
interface DependenciesInfo: AndroidModel {
    val includeInApk: Boolean
    val includeInBundle: Boolean
}
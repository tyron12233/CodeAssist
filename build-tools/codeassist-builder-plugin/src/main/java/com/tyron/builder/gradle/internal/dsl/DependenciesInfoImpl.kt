package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.DependenciesInfo
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization

/**
 * DSL options for specifying whether to include SDK dependency information in APKs and Bundles.
 */
abstract class DependenciesInfoImpl @WithLazyInitialization("lazyInit") constructor()
    : DependenciesInfo {

    @Suppress("unused") // call injected in the constructor by the dsl decorator
    protected fun lazyInit() {
        includeInApk = true
        includeInBundle = true
    }
}
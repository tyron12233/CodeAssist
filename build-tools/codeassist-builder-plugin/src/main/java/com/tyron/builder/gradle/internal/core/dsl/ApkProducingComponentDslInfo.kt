package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.gradle.internal.dsl.SigningConfig

/**
 * Contains the final dsl info computed from the DSL object model (extension, default config,
 * build type, flavors) that are needed by components that produces APKs.
 */
interface ApkProducingComponentDslInfo: ConsumableComponentDslInfo {

    val isDebuggable: Boolean

    /** Holds all SigningConfig information from the DSL and/or [ProjectOptions].  */
    val signingConfig: SigningConfig?

    val isSigningReady: Boolean
}
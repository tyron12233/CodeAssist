package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.gradle.internal.services.DslServices
import com.google.common.annotations.VisibleForTesting
import org.gradle.api.Action
import javax.inject.Inject

open class ExternalNativeBuildOptions :
    CoreExternalNativeBuildOptions,
    com.tyron.builder.api.dsl.ExternalNativeBuildOptions {
    final override val ndkBuild: ExternalNativeNdkBuildOptions
    final override val cmake: ExternalNativeCmakeOptions
    final override val experimentalProperties: MutableMap<String, Any> = mutableMapOf()

    @VisibleForTesting
    constructor() {
        ndkBuild = ExternalNativeNdkBuildOptions()
        cmake = ExternalNativeCmakeOptions()
    }

    @Inject
    constructor(dslServices: DslServices) {
        ndkBuild = dslServices.newInstance(ExternalNativeNdkBuildOptions::class.java)
        cmake = dslServices.newInstance(ExternalNativeCmakeOptions::class.java)
    }

    fun _initWith(that: ExternalNativeBuildOptions) {
        ndkBuild._initWith(that.externalNativeNdkBuildOptions)
        cmake._initWith(that.externalNativeCmakeOptions)
        experimentalProperties.putAll(that.externalNativeExperimentalProperties)
    }

    override fun getExternalNativeNdkBuildOptions(): ExternalNativeNdkBuildOptions? = ndkBuild

    override fun getExternalNativeCmakeOptions(): ExternalNativeCmakeOptions? = cmake

    override fun getExternalNativeExperimentalProperties(): MutableMap<String, Any> =
        experimentalProperties

    fun ndkBuild(action: Action<ExternalNativeNdkBuildOptions>) {
        action.execute(ndkBuild)
    }

    fun cmake(action: Action<ExternalNativeCmakeOptions>) {
        action.execute(cmake)
    }

    override fun ndkBuild(action: com.tyron.builder.api.dsl.ExternalNativeNdkBuildOptions.() -> Unit) {
        action.invoke(ndkBuild)
    }

    override fun cmake(action: com.tyron.builder.api.dsl.ExternalNativeCmakeOptions.() -> Unit) {
        action.invoke(cmake)
    }
}

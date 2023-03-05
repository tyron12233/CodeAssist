package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ComposeOptions
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

open class ComposeOptionsImpl @Inject constructor(private val dslServices: DslServices) :
        ComposeOptions {
    override var kotlinCompilerVersion: String?
        get() = null
        set(s: String?) { dslServices.logger.warn("ComposeOptions.kotlinCompilerVersion is deprecated. Compose now uses the kotlin compiler defined in your buildscript.") }
    override var kotlinCompilerExtensionVersion: String? = null
    override var useLiveLiterals: Boolean = true
}
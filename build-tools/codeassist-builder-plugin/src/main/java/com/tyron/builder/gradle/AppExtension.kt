package com.tyron.builder.gradle

import com.tyron.builder.gradle.api.BaseVariantOutput
import com.tyron.builder.gradle.internal.ExtraModelInfo
import com.tyron.builder.gradle.internal.dependency.SourceSetManager
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfig
import org.gradle.api.NamedDomainObjectContainer

/**
 * AppExtension is used directly by build.gradle.kts when configuring project so adding generics
 * declaration is not possible.
 */
abstract class AppExtension(
    dslServices: DslServices,
    bootClasspathConfig: BootClasspathConfig,
    buildOutputs: NamedDomainObjectContainer<BaseVariantOutput>,
    sourceSetManager: SourceSetManager,
    extraModelInfo: ExtraModelInfo,
    isBaseModule: Boolean
) : AbstractAppExtension(
    dslServices,
    bootClasspathConfig,
    buildOutputs,
    sourceSetManager,
    extraModelInfo,
    isBaseModule
)

package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ApplicationBuildFeatures
import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.ApplicationDefaultConfig
import com.tyron.builder.api.dsl.ApplicationProductFlavor
import com.tyron.builder.gradle.internal.plugins.DslContainerProvider
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

/** Internal implementation of the 'new' DSL interface */
abstract class ApplicationExtensionImpl @Inject constructor(
    dslServices: DslServices,
    dslContainers: DslContainerProvider<
            ApplicationDefaultConfig,
            ApplicationBuildType,
            ApplicationProductFlavor,
            SigningConfig>
) :
    TestedExtensionImpl<
            ApplicationBuildFeatures,
            ApplicationBuildType,
            ApplicationDefaultConfig,
            ApplicationProductFlavor>(
        dslServices,
        dslContainers
    ),
    InternalApplicationExtension {

    override val buildFeatures: ApplicationBuildFeatures =
        dslServices.newInstance(ApplicationBuildFeaturesImpl::class.java)
}
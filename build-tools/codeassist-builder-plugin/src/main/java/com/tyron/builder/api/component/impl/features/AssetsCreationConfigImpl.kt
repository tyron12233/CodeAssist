package com.tyron.builder.api.component.impl.features

import com.tyron.builder.api.variant.AndroidResources
import com.tyron.builder.api.variant.impl.initializeAaptOptionsFromDsl
import com.tyron.builder.gradle.internal.component.features.AndroidResourcesCreationConfig
import com.tyron.builder.gradle.internal.component.features.AssetsCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo
import com.tyron.builder.gradle.internal.services.VariantServices

class AssetsCreationConfigImpl(
    private val dslInfo: AndroidResourcesDslInfo,
    private val internalServices: VariantServices,
    private val androidResourcesCreationConfig: () -> AndroidResourcesCreationConfig?
): AssetsCreationConfig {

    override val androidResources: AndroidResources by lazy {
        androidResourcesCreationConfig.invoke()?.androidResources
            ?: initializeAaptOptionsFromDsl(dslInfo.androidResources, internalServices)
    }
}

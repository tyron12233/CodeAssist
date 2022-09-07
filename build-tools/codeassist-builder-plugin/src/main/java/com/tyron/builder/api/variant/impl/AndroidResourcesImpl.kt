package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.AndroidResources
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.provider.ListProperty

class AndroidResourcesImpl(
    override val ignoreAssetsPatterns: ListProperty<String>,
    override val aaptAdditionalParameters: ListProperty<String>,
    override val noCompress: ListProperty<String>
) : AndroidResources

internal fun initializeAaptOptionsFromDsl(
    dslAndroidResources: com.tyron.builder.api.dsl.AndroidResources,
    variantServices: VariantServices
) : AndroidResources {
    return AndroidResourcesImpl(
        ignoreAssetsPatterns = variantServices.listPropertyOf(
            String::class.java,
            dslAndroidResources.ignoreAssetsPattern?.split(':') ?: listOf()
        ),
        aaptAdditionalParameters = variantServices.listPropertyOf(
            String::class.java,
            dslAndroidResources.additionalParameters
        ),
        noCompress = variantServices.listPropertyOf(
            String::class.java,
            dslAndroidResources.noCompress
        )
    )
}
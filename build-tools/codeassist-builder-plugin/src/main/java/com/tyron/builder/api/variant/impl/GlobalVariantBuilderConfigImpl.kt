package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.dsl.ApplicationExtension
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.DependenciesInfo

class GlobalVariantBuilderConfigImpl(
    private val extension: CommonExtension<*, *, *, *>
) : GlobalVariantBuilderConfig {

    override val dependenciesInfo: DependenciesInfo
        get() = (extension as? ApplicationExtension)?.dependenciesInfo
            ?: throw RuntimeException("Access to dependenciesInfo on a non Application variant")
}
package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ApplicationSingleVariant
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

abstract class ApplicationSingleVariantImpl @Inject constructor(
    dslServices: DslServices,
    override val variantName: String
) : ApplicationSingleVariant, PublishingOptionsImpl() {

    internal abstract var publishVariantAsApk: Boolean

    override fun publishApk() {
        publishVariantAsApk = true
    }
}

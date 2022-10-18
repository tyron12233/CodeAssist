package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ApplicationPublishing
import com.tyron.builder.api.dsl.ApplicationSingleVariant
import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.Action
import javax.inject.Inject

abstract class ApplicationPublishingImpl@Inject constructor(dslService: DslServices)
    : ApplicationPublishing, AbstractPublishing<ApplicationSingleVariantImpl>(dslService) {

    override fun singleVariant(variantName: String) {
        addSingleVariant(variantName, ApplicationSingleVariantImpl::class.java)
    }

    override fun singleVariant(
        variantName: String,
        action: ApplicationSingleVariant.() -> Unit
    ) {
        addSingleVariantAndConfigure(variantName, ApplicationSingleVariantImpl::class.java, action)
    }

    fun singleVariant(variantName: String, action: Action<ApplicationSingleVariant>) {
        addSingleVariantAndConfigure(variantName, ApplicationSingleVariantImpl::class.java) {
            action.execute(this) }
    }
}

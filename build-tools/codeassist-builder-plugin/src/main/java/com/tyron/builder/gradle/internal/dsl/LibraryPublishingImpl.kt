package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.LibraryPublishing
import com.tyron.builder.api.dsl.LibrarySingleVariant
import com.tyron.builder.api.dsl.MultipleVariants
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.Action
import javax.inject.Inject

abstract class LibraryPublishingImpl @Inject constructor(dslService: DslServices)
    : LibraryPublishing, AbstractPublishing<LibrarySingleVariantImpl>(dslService) {

    abstract val multipleVariantsContainer: MutableList<MultipleVariantsImpl>

    override fun singleVariant(variantName: String) {
        addSingleVariant(variantName, LibrarySingleVariantImpl::class.java)
    }

    override fun singleVariant(variantName: String, action: LibrarySingleVariant.() -> Unit) {
        addSingleVariantAndConfigure(variantName, LibrarySingleVariantImpl::class.java, action)
    }

    fun singleVariant(variantName: String, action: Action<LibrarySingleVariant>) {
        addSingleVariantAndConfigure(variantName, LibrarySingleVariantImpl::class.java) {
            action.execute(this) }
    }

    override fun multipleVariants(componentName: String, action: MultipleVariants.() -> Unit) {
        action.invoke(addMultipleVariants(componentName))
    }

    override fun multipleVariants(action: MultipleVariants.() -> Unit) {
        multipleVariants(DEFAULT_COMPONENT_NAME, action)
    }

    fun multipleVariants(componentName: String, action: Action<MultipleVariants>) {
        action.execute(addMultipleVariants(componentName))
    }

    fun multipleVariants(action: Action<MultipleVariants>) {
        multipleVariants(DEFAULT_COMPONENT_NAME, action)
    }

    private fun addMultipleVariants(componentName: String) : MultipleVariantsImpl {
        checkMultipleVariantUniqueness(componentName)
        val multipleVariants =
            dslService.newDecoratedInstance(
                MultipleVariantsImpl::class.java, dslService, componentName)
        multipleVariantsContainer.add(multipleVariants)
        return multipleVariants
    }

    private fun checkMultipleVariantUniqueness(componentName: String) {
        if (multipleVariantsContainer.any { it.componentName == componentName }) {
            dslService.issueReporter.reportError(
                IssueReporter.Type.GENERIC,
                "Using multipleVariants publishing DSL multiple times to publish variants " +
                        "to the same component \"$componentName\" is not allowed."
            )
        }
    }

    companion object {
        const val DEFAULT_COMPONENT_NAME = "default"
    }
}

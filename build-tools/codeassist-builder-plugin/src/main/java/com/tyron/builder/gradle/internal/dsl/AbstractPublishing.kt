package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.SingleVariant
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.services.DslServices

abstract class AbstractPublishing<T : SingleVariant>(val dslService: DslServices) {

    abstract val singleVariants: MutableList<T>

    protected fun addSingleVariant(
        variantName: String,
        implementationClass: Class<T>
    ): T {
        checkSingleVariantUniqueness(variantName, singleVariants)
        val singleVariant = dslService.newDecoratedInstance(implementationClass, dslService, variantName)
        singleVariants.add(singleVariant)
        return singleVariant
    }

    protected fun addSingleVariantAndConfigure(
        variantName: String,
        implementationClass: Class<T>,
        action: T.() -> Unit
    ) {
        action.invoke(addSingleVariant(variantName, implementationClass))
    }

    private fun checkSingleVariantUniqueness(
        variantName: String,
        singleVariants: List<T>
    ) {
        if (singleVariants.any { it.variantName == variantName}) {
            dslService.issueReporter.reportError(
                IssueReporter.Type.GENERIC,
                "Using singleVariant publishing DSL multiple times to publish " +
                        "variant \"$variantName\" to component \"$variantName\" is not allowed."
            )
        }
    }

    /**
     * Publication artifact types.
     */
    enum class Type {
        AAR,
        APK,
        AAB
    }
}
package com.tyron.builder.api.extension.impl

import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.variant.Variant
import com.tyron.builder.api.variant.VariantBuilder

/**
 * Holder of various [OperationsRegistrar] for all the variant API related operations to a plugin.
 */
class VariantApiOperationsRegistrar<CommonExtensionT: CommonExtension<*, *, *, *>, VariantBuilderT: VariantBuilder, VariantT: Variant>(
        extension: CommonExtensionT,
) : DslLifecycleComponentsOperationsRegistrar<CommonExtensionT>(extension) {

    internal val variantBuilderOperations = OperationsRegistrar<VariantBuilderT>()
    internal val variantOperations = OperationsRegistrar<VariantT>()
    internal val dslExtensions = mutableListOf<AndroidComponentsExtensionImpl.RegisteredApiExtension<VariantT>>()
    internal val sourceSetExtensions = mutableListOf<String>()

    fun onEachSourceSetExtensions(action: (name: String) -> Unit) {
        sourceSetExtensions.forEach(action)
    }
}

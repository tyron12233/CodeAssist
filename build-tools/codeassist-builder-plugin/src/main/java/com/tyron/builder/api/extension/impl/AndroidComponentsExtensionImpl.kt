package com.tyron.builder.api.extension.impl

import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.SdkComponents
import com.tyron.builder.api.variant.*
import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.Action

abstract class AndroidComponentsExtensionImpl<
        DslExtensionT: CommonExtension<*, *, *, *>,
        VariantBuilderT: VariantBuilder,
        VariantT: Variant>(
    private val dslServices: DslServices,
    override val sdkComponents: SdkComponents,
    private val variantApiOperations: VariantApiOperationsRegistrar<DslExtensionT, VariantBuilderT, VariantT>,
    private val commonExtension: DslExtensionT
): AndroidComponentsExtension<DslExtensionT, VariantBuilderT, VariantT> {

    override fun finalizeDsl(callback: (DslExtensionT) -> Unit) {
        variantApiOperations.add {
            callback.invoke(it)
        }
    }

    override fun finalizeDsl(callback: Action<DslExtensionT>) {
        variantApiOperations.add(callback)
    }

    @Suppress("OverridingDeprecatedMember")
    override fun finalizeDSl(callback: Action<DslExtensionT>) {
        variantApiOperations.add(callback)
    }

//    override val pluginVersion: AndroidPluginVersion
//        get() = CURRENT_AGP_VERSION

    override fun beforeVariants(selector: VariantSelector, callback: (VariantBuilderT) -> Unit) {
        variantApiOperations.variantBuilderOperations.addOperation({
            callback.invoke(it)
        }, selector)
    }

    override fun beforeVariants(selector: VariantSelector, callback: Action<VariantBuilderT>) {
        variantApiOperations.variantBuilderOperations.addOperation(callback, selector)
    }

    override fun onVariants(selector: VariantSelector, callback: (VariantT) -> Unit) {
        variantApiOperations.variantOperations.addOperation({
            callback.invoke(it)
        }, selector)
    }

    override fun onVariants(selector: VariantSelector, callback: Action<VariantT>) {
        variantApiOperations.variantOperations.addOperation(callback, selector)
    }

    override fun selector(): VariantSelectorImpl =
            dslServices.newInstance(VariantSelectorImpl::class.java)

    class RegisteredApiExtension<VariantT: Variant>(
        val dslExtensionTypes: DslExtension,
        val configurator: (variantExtensionConfig: VariantExtensionConfig<VariantT>) -> VariantExtension
    )

    override fun registerExtension(
        dslExtension: DslExtension,
        configurator: (variantExtensionConfig: VariantExtensionConfig<VariantT>) -> VariantExtension
    ) {
        variantApiOperations.dslExtensions.add(
            RegisteredApiExtension(
                dslExtensionTypes = dslExtension,
                configurator = configurator
        ))

        dslExtension.buildTypeExtensionType?.let {
            commonExtension.buildTypes.forEach {
                    buildType ->
                buildType.extensions.add(
                    dslExtension.dslName,
                    it
                )
            }
        }
        dslExtension.productFlavorExtensionType?.let {
            commonExtension.productFlavors.forEach {
                productFlavor -> productFlavor.extensions.add(
                    dslExtension.dslName,
                    it
                )
            }
        }
    }

    override fun registerSourceType(name: String) {
        variantApiOperations.sourceSetExtensions.add(name)
    }
}

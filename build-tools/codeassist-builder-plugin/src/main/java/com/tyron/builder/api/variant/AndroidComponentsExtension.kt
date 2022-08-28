package com.tyron.builder.api.variant

import com.tyron.builder.api.dsl.CommonExtension

/**
 * Generic extension for Android Gradle Plugin related components.
 *
 * Each component has a type, like application or library and will have a dedicated extension with
 * methods that are related to the particular component type.
 *
 * @param DslExtensionT the type of the DSL to be used in [DslLifecycle.finalizeDsl]
 * @param VariantBuilderT the [ComponentBuilder] type produced by this variant.
 * @param VariantT the [Variant] type produced by this variant.
 */
interface AndroidComponentsExtension<
        DslExtensionT: CommonExtension<*, *, *, *>,
        VariantBuilderT: VariantBuilder,
        VariantT: Variant>
    : DslLifecycle<DslExtensionT> {
}
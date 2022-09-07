package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.variant.ComponentBuilder
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.VariantDslInfo

open class ComponentInfo<
        ComponentBuilderT : ComponentBuilder,
        ComponentT : ComponentCreationConfig>(
    val variantBuilder: ComponentBuilderT,
    val variant: ComponentT,
    val stats: Any?
)

class VariantComponentInfo<
        VariantBuilderT : VariantBuilder,
        VariantDslInfoT: VariantDslInfo,
        VariantT : VariantCreationConfig> (
    variantBuilder: VariantBuilderT,
    variant: VariantT,
    stats: Any?,
    val variantDslInfo: VariantDslInfoT
) : ComponentInfo<VariantBuilderT, VariantT>(variantBuilder, variant, stats)

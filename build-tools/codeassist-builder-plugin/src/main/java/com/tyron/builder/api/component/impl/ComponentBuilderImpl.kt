package com.tyron.builder.api.component.impl

import com.tyron.builder.api.variant.ComponentBuilder
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.impl.GlobalVariantBuilderConfig
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.services.VariantBuilderServices

abstract class ComponentBuilderImpl(
    protected val globalVariantBuilderConfig: GlobalVariantBuilderConfig,
    protected val dslInfo: ComponentDslInfo,
    variantConfiguration: ComponentIdentity,
    protected val variantBuilderServices: VariantBuilderServices
) : ComponentBuilder, ComponentIdentity by variantConfiguration {

    @Suppress("OverridingDeprecatedMember")
    override var enabled: Boolean
        get() = enable
        set(value) {
            enable = value
        }

    override var enable: Boolean = true
}
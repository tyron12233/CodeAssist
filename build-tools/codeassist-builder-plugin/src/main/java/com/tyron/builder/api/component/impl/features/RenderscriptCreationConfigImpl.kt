package com.tyron.builder.api.component.impl.features

import com.tyron.builder.api.variant.Renderscript
import com.tyron.builder.gradle.internal.component.features.RenderscriptCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.ConsumableComponentDslInfo
import com.tyron.builder.gradle.internal.services.VariantServices

class RenderscriptCreationConfigImpl(
    private val dslInfo: ConsumableComponentDslInfo,
    private val internalServices: VariantServices,
    override val renderscriptTargetApi: Int
): RenderscriptCreationConfig {

    override val renderscript: Renderscript by lazy {
        internalServices.newInstance(Renderscript::class.java).also {
            it.supportModeEnabled.set(dslInfo.renderscriptSupportModeEnabled)
            it.supportModeBlasEnabled.set(dslInfo.renderscriptSupportModeBlasEnabled)
            it.ndkModeEnabled.set(dslInfo.renderscriptNdkModeEnabled)
            it.optimLevel.set(dslInfo.renderscriptOptimLevel)
        }
    }
    override val dslRenderscriptNdkModeEnabled: Boolean
        get() = dslInfo.renderscriptNdkModeEnabled
}

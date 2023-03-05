package com.tyron.builder.gradle.internal.component.features

import com.tyron.builder.api.variant.Renderscript

/**
 * Creation config for components that support renderscript.
 *
 * To use this in a task that requires renderscript support, use
 * [com.tyron.builder.gradle.internal.tasks.factory.features.RenderscriptTaskCreationAction].
 * Otherwise, access the nullable property on the component
 * [com.tyron.builder.gradle.internal.component.ConsumableCreationConfig.renderscriptCreationConfig].
 */
interface RenderscriptCreationConfig {
    val renderscript: Renderscript
    val renderscriptTargetApi: Int
    @Deprecated("DO NOT USE, instead use the final value from the renderscript variant api value")
    val dslRenderscriptNdkModeEnabled: Boolean
}

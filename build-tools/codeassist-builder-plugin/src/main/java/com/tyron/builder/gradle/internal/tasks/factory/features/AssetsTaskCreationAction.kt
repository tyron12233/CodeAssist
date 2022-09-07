package com.tyron.builder.gradle.internal.tasks.factory.features

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.features.AssetsCreationConfig

/**
 * Creation action for tasks that requires assets support.
 *
 * Example:
 * ```
 * abstract class Task {
 *   class CreationAction(
 *     creationConfig: ComponentCreationConfig
 *   ) : VariantTaskCreationAction<Task, ComponentCreationConfig>(
 *     creationConfig
 *   ), AssetsTaskCreationAction by AssetsTaskCreationActionImpl(
 *     creationConfig
 *   ) {
 *     ...
 *   }
 * }
 * ```
 */
interface AssetsTaskCreationAction {
    val assetsCreationConfig: AssetsCreationConfig
}

class AssetsTaskCreationActionImpl(
    creationConfig: ComponentCreationConfig
): AssetsTaskCreationAction {

    override val assetsCreationConfig: AssetsCreationConfig = creationConfig.assetsCreationConfig!!
}

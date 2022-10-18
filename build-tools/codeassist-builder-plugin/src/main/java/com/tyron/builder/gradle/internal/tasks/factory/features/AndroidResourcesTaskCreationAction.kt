package com.tyron.builder.gradle.internal.tasks.factory.features

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.features.AndroidResourcesCreationConfig

/**
 * Creation action for tasks that requires android resources support.
 *
 * Example:
 * ```
 * abstract class Task {
 *   class CreationAction(
 *     creationConfig: ComponentCreationConfig
 *   ) : VariantTaskCreationAction<Task, ComponentCreationConfig>(
 *     creationConfig
 *   ), AndroidResourcesTaskCreationAction by AndroidResourcesTaskCreationActionImpl(
 *     creationConfig
 *   ) {
 *     ...
 *   }
 * }
 * ```
 */
interface AndroidResourcesTaskCreationAction {
    val androidResourcesCreationConfig: AndroidResourcesCreationConfig
}

class AndroidResourcesTaskCreationActionImpl(
    creationConfig: ComponentCreationConfig
): AndroidResourcesTaskCreationAction {

    override val androidResourcesCreationConfig: AndroidResourcesCreationConfig =
        creationConfig.androidResourcesCreationConfig!!
}

package com.tyron.builder.gradle.internal.tasks.factory

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.features.ResValuesCreationConfig

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
 *   ), ResValuesTaskCreationAction by ResValuesTaskCreationActionImpl(
 *     creationConfig
 *   ) {
 *     ...
 *   }
 * }
 * ```
 */
interface ResValuesTaskCreationAction {
    val resValuesCreationConfig: ResValuesCreationConfig
}

class ResValuesTaskCreationActionImpl(
    creationConfig: ComponentCreationConfig
): ResValuesTaskCreationAction {

    override val resValuesCreationConfig: ResValuesCreationConfig =
        creationConfig.resValuesCreationConfig!!
}
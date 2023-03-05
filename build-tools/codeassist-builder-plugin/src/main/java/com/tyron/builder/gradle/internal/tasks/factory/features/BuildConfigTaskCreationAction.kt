package com.tyron.builder.gradle.internal.tasks.factory.features

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.features.BuildConfigCreationConfig

/**
 * Creation action for tasks that requires build config support.
 *
 * Example:
 * ```
 * abstract class Task {
 *   class CreationAction(
 *     creationConfig: ComponentCreationConfig
 *   ) : VariantTaskCreationAction<Task, ComponentCreationConfig>(
 *     creationConfig
 *   ), BuildConfigTaskCreationAction by BuildConfigTaskCreationActionImpl(
 *     creationConfig
 *   ) {
 *     ...
 *   }
 * }
 * ```
 */
interface BuildConfigTaskCreationAction {
    val buildConfigCreationConfig: BuildConfigCreationConfig
}

class BuildConfigTaskCreationActionImpl(
    creationConfig: ComponentCreationConfig
): BuildConfigTaskCreationAction {

    override val buildConfigCreationConfig: BuildConfigCreationConfig =
        creationConfig.buildConfigCreationConfig!!
}

package com.tyron.builder.gradle.internal.component.features

import com.tyron.builder.api.variant.ResValue
import org.gradle.api.provider.MapProperty

/**
 * Creation config for components that support res values.
 *
 * To use this in a task that requires res values support, use
 * [com.android.build.gradle.internal.tasks.factory.features.ResValuesTaskCreationAction].
 * Otherwise, access the nullable property on the component
 * [com.android.build.gradle.internal.component.ComponentCreationConfig.resValuesCreationConfig].
 */
interface ResValuesCreationConfig {
    val resValues: MapProperty<ResValue.Key, ResValue>
}
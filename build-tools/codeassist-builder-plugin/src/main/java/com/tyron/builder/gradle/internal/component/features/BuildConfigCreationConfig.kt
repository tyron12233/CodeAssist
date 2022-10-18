package com.tyron.builder.gradle.internal.component.features

import com.tyron.builder.api.variant.BuildConfigField
import com.tyron.builder.compiling.BuildConfigType
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.MapProperty
import java.io.Serializable

/**
 * Creation config for components that support build config.
 *
 * To use this in a task that requires assets support, use
 * [com.android.build.gradle.internal.tasks.factory.features.BuildConfigTaskCreationAction].
 * Otherwise, access the nullable property on the component
 * [com.android.build.gradle.internal.component.ComponentCreationConfig.buildConfigCreationConfig].
 */
interface BuildConfigCreationConfig {
    val buildConfigFields: MapProperty<String, BuildConfigField<out Serializable>>
    @Deprecated("DO NOT USE, use buildConfigFields map property")
    val dslBuildConfigFields: Map<String, BuildConfigField<out Serializable>>
    val compiledBuildConfig: FileCollection
    val buildConfigType: BuildConfigType
}
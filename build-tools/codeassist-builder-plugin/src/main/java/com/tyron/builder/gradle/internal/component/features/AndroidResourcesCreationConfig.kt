package com.tyron.builder.gradle.internal.component.features

import com.tyron.builder.api.variant.AndroidResources
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.model.VectorDrawablesOptions
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * Creation config for components that support android resources.
 *
 * To use this in a task that requires android resources support, use
 * [com.tyron.builder.gradle.internal.tasks.factory.features.AndroidResourcesTaskCreationAction].
 * Otherwise, access the nullable property on the component
 * [com.tyron.builder.gradle.internal.component.ComponentCreationConfig.androidResourcesCreationConfig].
 */
interface AndroidResourcesCreationConfig {
    val androidResources: AndroidResources
    val pseudoLocalesEnabled: Property<Boolean>
    val isCrunchPngs: Boolean
    val isPrecompileDependenciesResourcesEnabled: Boolean
    val resourceConfigurations: Set<String>
    val vectorDrawables: VectorDrawablesOptions
    val useResourceShrinker: Boolean
    val compiledRClassArtifact: Provider<RegularFile>
    fun getCompiledRClasses(configType: AndroidArtifacts.ConsumedConfigType): FileCollection
}

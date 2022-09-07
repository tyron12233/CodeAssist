package com.tyron.builder.gradle.internal.fusedlibrary

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.FusedLibraryExtension
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ProjectLayout
import org.gradle.api.specs.Spec

interface FusedLibraryVariantScope {
    val layout: ProjectLayout
    val artifacts: ArtifactsImpl
    val incomingConfigurations: FusedLibraryConfigurations
    val outgoingConfigurations: FusedLibraryConfigurations
    val dependencies: FusedLibraryDependencies
    val extension: FusedLibraryExtension
    val mergeSpec: Spec<ComponentIdentifier>
}

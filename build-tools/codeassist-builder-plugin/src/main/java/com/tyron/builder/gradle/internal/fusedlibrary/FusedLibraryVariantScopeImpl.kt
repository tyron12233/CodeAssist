package com.tyron.builder.gradle.internal.fusedlibrary

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.FusedLibraryExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ProjectLayout
import org.gradle.api.specs.Spec

open class FusedLibraryVariantScopeImpl(
    project: Project,
    extensionProvider: () -> FusedLibraryExtension
) : FusedLibraryVariantScope {
    override val layout: ProjectLayout = project.layout
    override val artifacts= ArtifactsImpl(project, "single")
    override val incomingConfigurations = FusedLibraryConfigurations()
    override val outgoingConfigurations = FusedLibraryConfigurations()
    override val dependencies = FusedLibraryDependencies(incomingConfigurations)

    override val extension: FusedLibraryExtension by lazy {
        extensionProvider.invoke()
    }

    override val mergeSpec = Spec { componentIdentifier: ComponentIdentifier ->
        println("In mergeSpec -> $componentIdentifier, type is ${componentIdentifier.javaClass}, merge = ${componentIdentifier is ProjectComponentIdentifier}")
        componentIdentifier is ProjectComponentIdentifier
    }
}

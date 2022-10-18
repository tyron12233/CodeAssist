package com.tyron.builder.gradle.internal.ide.dependencies

import com.tyron.builder.errors.IssueReporter

interface DependencyGraphBuilder {

    fun createDependencies(
        modelBuilder: DependencyModelBuilder<*>,
        artifactCollectionsProvider: ArtifactCollectionsInputs,
        withFullDependency: Boolean,
        issueReporter: IssueReporter
    )
}

fun getDependencyGraphBuilder(): DependencyGraphBuilder {
    return ArtifactDependencyGraph()
}

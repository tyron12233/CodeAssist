package org.gradle.configurationcache

import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal
import org.gradle.api.internal.artifacts.configurations.ProjectDependencyObservedListener
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedProjectConfiguration
import org.gradle.api.internal.project.ProjectState
import org.gradle.execution.plan.Node
import org.gradle.internal.service.scopes.Scopes
import org.gradle.internal.service.scopes.ServiceScope


@ServiceScope(Scopes.Build::class)
class RelevantProjectsRegistry : ProjectDependencyObservedListener {
    private
    val targetProjects = mutableSetOf<ProjectState>()

    fun relevantProjects(nodes: List<Node>): Set<ProjectState> =
        targetProjects +
            nodes.mapNotNullTo(mutableListOf()) { node ->
                node.owningProject?.owner
            }

    override fun dependencyObserved(consumingProject: ProjectState?, targetProject: ProjectState, requestedState: ConfigurationInternal.InternalState, target: ResolvedProjectConfiguration) {
        targetProjects.add(targetProject)
    }
}

package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.ArtifactDependencies
import com.tyron.builder.model.v2.ide.GraphItem
import com.tyron.builder.model.v2.ide.UnresolvedDependency
import java.io.Serializable

/**
 * Implementation of [ArtifactDependencies] for serialization via the Tooling API.
 */
data class ArtifactDependenciesImpl(
    override val compileDependencies: List<GraphItem>,
    override val runtimeDependencies: List<GraphItem>,
    override val unresolvedDependencies: List<UnresolvedDependency>
) : ArtifactDependencies, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}

data class UnresolvedDependencyImpl(
    override val name: String,
    override val cause: String?
): UnresolvedDependency, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}

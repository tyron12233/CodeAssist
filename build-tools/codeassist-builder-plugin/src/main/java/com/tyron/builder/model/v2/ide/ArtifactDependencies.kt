package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * The dependencies information for a given artifact.
 *
 * It contains the compile graph, always, and optionally the runtime graph.
 *
 * Each graph is fairly lightweight, with each artifact node being mostly an address, children,
 * and modifiers that are specific to this particular usage of the artifact rather than
 * artifact properties.*
 *
 * @since 4.2
 */
interface ArtifactDependencies: AndroidModel {
    /**
     * The compile dependency graph.
     */
    val compileDependencies: List<GraphItem>

    /**
     * The runtime dependency graph.
     */
    val runtimeDependencies: List<GraphItem>

    val unresolvedDependencies: List<UnresolvedDependency>
}

interface UnresolvedDependency {
    val name: String
    val cause: String?
}
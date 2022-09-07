package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * A node in a dependency graph, representing a direct or transitive dependency.
 *
 * This does not directly contain artifact information, instead it focuses on the graph
 * information (transitive dependencies) as well as the usage of this particular dependency
 * in this node of the graph (ie what are its modifiers: what version was originally requested.)
 *
 */
interface GraphItem: AndroidModel {
    /**
     * A Unique key representing the library, and allowing to match it with a [Library] instance
     */
    val key: String

    /**
     * Returns this library's Maven coordinates, as requested in the build file.
     */
    val requestedCoordinates: String?

    /**
     * Return the direct dependency of this node.
     */
    val dependencies: List<GraphItem>
}

package com.tyron.builder.dexing

import java.io.Serializable

/**
 * Directed graph to model the dependencies among objects of interest. An edge from A to B means
 * that object A depends on object B (A is a dependent of B, and B is a dependency of A).
 */
class MutableDependencyGraph<T> : DependencyGraphUpdater<T>, Serializable {

    /** Map from a node B to all the nodes A that have an edge from A to B. */
    private val dependentsMap: MutableMap<T, MutableSet<T>> = mutableMapOf()

    /** Returns all the nodes. */
    fun getNodes(): Set<T> {
        return dependentsMap.keys.toSet()
    }

    /**
     * Returns the directly dependent nodes of a given node. The returned set does not include the
     * given node.
     *
     * If the given node does not exist, the returned set is empty.
     */
    fun getDirectDependents(node: T): Set<T> {
        return dependentsMap[node]?.toSet() ?: emptySet()
    }

    override fun addEdge(dependent: T, dependency: T) {
        check(dependent != dependency) { "Can't add an edge from a node to itself: $dependent" }

        dependentsMap.computeIfAbsent(dependency) { mutableSetOf() }.add(dependent)
        dependentsMap.computeIfAbsent(dependent) { mutableSetOf() }
    }

    /**
     * Removes a node and all the edges from it and to it.
     *
     * If the given node does not exist, it will be ignored.
     */
    fun removeNode(nodeToRemove: T) {
        dependentsMap.remove(nodeToRemove)
        dependentsMap.values.forEach { it.remove(nodeToRemove) }
    }

    /**
     * Returns all the directly and transitively dependent nodes of a given set of nodes. The
     * returned set does not include the nodes in the given set.
     *
     * Any nodes in the given set that do not exist are ignored.
     */
    fun getAllDependents(nodes: Set<T>): Set<T> {
        val visitedSet: MutableSet<T> = mutableSetOf()
        val toVisitSet: MutableSet<T> = nodes.toMutableSet()

        while (toVisitSet.isNotEmpty()) {
            val toVisitNextSet = mutableSetOf<T>()
            for (toVisitNode in toVisitSet) {
                dependentsMap[toVisitNode]?.let { toVisitNextSet.addAll(it) }
            }
            visitedSet.addAll(toVisitSet)
            toVisitSet.clear()
            toVisitSet.addAll(toVisitNextSet - visitedSet)
        }

        return visitedSet - nodes
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** Interface for updating a dependency graph. */
interface DependencyGraphUpdater<T> {

    /**
     * Adds an edge from a dependent to a dependency.
     *
     * If any of the given nodes does not yet exist, it will be added.
     */
    fun addEdge(dependent: T, dependency: T)
}
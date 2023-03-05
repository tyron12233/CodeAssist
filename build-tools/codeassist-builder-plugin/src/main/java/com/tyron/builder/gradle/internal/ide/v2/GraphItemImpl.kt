package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.GraphItem
import java.io.Serializable

/**
 * Implementation of [GraphItem] for serialization via the Tooling API.
 */
data class GraphItemImpl(
    override val key: String,
    override val requestedCoordinates: String?,
) : GraphItem, Serializable {

    private val _dependencies = mutableListOf<GraphItem>()

    override val dependencies: List<GraphItem>
        get() = _dependencies

    internal fun addDependency(dependency: GraphItem) {
        _dependencies.add(dependency)
    }

    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 2L
    }

}
package com.tyron.builder.api.internal.graph;

import java.util.Collection;

/**
 * A directed graph with nodes of type N. Each node has a collection of values of type V.
 */
public interface DirectedGraph<N, V> {
    void getNodeValues(N node, Collection<? super V> values, Collection<? super N> connectedNodes);
}
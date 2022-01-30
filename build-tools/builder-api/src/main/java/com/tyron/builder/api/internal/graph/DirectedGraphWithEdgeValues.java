package com.tyron.builder.api.internal.graph;

import java.util.Collection;

/**
 * A directed graph with nodes of type N. Each edge has a collection of values of type V
 */
public interface DirectedGraphWithEdgeValues<N, V> extends DirectedGraph<N, V> {
    void getEdgeValues(N from, N to, Collection<V> values);
}
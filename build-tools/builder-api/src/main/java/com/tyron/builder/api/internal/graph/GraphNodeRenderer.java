package com.tyron.builder.api.internal.graph;

public interface GraphNodeRenderer<N> {
    void renderTo(N node, StyledTextOutput output);
}
package com.tyron.builder.internal.graph;

public interface GraphNodeRenderer<N> {
    void renderTo(N node, StyledTextOutput output);
}
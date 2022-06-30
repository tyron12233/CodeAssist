package com.tyron.builder.internal.graph;

import com.tyron.builder.internal.logging.text.StyledTextOutput;

public interface GraphNodeRenderer<N> {
    void renderTo(N node, StyledTextOutput output);
}
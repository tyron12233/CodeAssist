package org.gradle.internal.graph;

import org.gradle.internal.logging.text.StyledTextOutput;

public interface GraphNodeRenderer<N> {
    void renderTo(N node, StyledTextOutput output);
}
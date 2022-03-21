package com.tyron.builder.api.execution.plan;

import com.google.common.collect.Sets;

import java.util.Set;

class MutationInfo {
    final Node node;
    final Set<Node> consumingNodes = Sets.newHashSet();
    final Set<String> outputPaths = Sets.newHashSet();
    final Set<String> destroyablePaths = Sets.newHashSet();
    boolean hasFileInputs;
    boolean hasOutputs;
    boolean hasLocalState;
    boolean resolved;
    boolean hasValidationProblem;

    MutationInfo(Node node) {
        this.node = node;
    }
}
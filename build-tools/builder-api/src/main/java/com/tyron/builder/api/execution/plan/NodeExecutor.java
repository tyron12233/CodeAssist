package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.internal.tasks.NodeExecutionContext;

public interface NodeExecutor {
    boolean execute(Node node, NodeExecutionContext context);
}
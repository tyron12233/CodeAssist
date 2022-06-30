package com.tyron.builder.execution.plan;

import com.tyron.builder.api.internal.tasks.NodeExecutionContext;

public interface NodeExecutor {
    boolean execute(Node node, NodeExecutionContext context);
}
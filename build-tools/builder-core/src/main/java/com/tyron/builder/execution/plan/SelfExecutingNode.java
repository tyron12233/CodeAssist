package com.tyron.builder.execution.plan;

import com.tyron.builder.api.internal.tasks.NodeExecutionContext;

public interface SelfExecutingNode {
    void execute(NodeExecutionContext context);
}

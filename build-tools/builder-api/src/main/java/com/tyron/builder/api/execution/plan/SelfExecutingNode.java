package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.internal.tasks.NodeExecutionContext;

public interface SelfExecutingNode {
    void execute(NodeExecutionContext context);
}

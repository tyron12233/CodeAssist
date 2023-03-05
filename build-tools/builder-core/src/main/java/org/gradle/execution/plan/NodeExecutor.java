package org.gradle.execution.plan;

import org.gradle.api.internal.tasks.NodeExecutionContext;

public interface NodeExecutor {
    boolean execute(Node node, NodeExecutionContext context);
}
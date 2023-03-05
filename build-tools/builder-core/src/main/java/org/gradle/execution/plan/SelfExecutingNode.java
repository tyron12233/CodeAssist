package org.gradle.execution.plan;

import org.gradle.api.internal.tasks.NodeExecutionContext;

public interface SelfExecutingNode {
    void execute(NodeExecutionContext context);
}

package org.gradle.execution.plan;

import org.gradle.api.internal.tasks.NodeExecutionContext;

public class WorkNodeExecutor implements NodeExecutor {
    @Override
    public boolean execute(Node node, NodeExecutionContext context) {
        if (!(node instanceof SelfExecutingNode)) {
            return false;
        }
        ((SelfExecutingNode) node).execute(context);
        return true;
    }
}
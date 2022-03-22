package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;

public class LocalTaskNodeExecutor implements NodeExecutor {

    private final ExecutionNodeAccessHierarchy outputHierarchy;

    public LocalTaskNodeExecutor(ExecutionNodeAccessHierarchy outputHierarchy) {
        this.outputHierarchy = outputHierarchy;
    }

    @Override
    public boolean execute(Node node, NodeExecutionContext context) {
        if (node instanceof LocalTaskNode) {
            LocalTaskNode localTaskNode = (LocalTaskNode) node;
            TaskInternal task = localTaskNode.getTask();
//            TaskStateInternal state = task.getState();
//            ExecutionNodeAccessHierarchies.InputNodeAccessHierarchy inputHierarchy = context.getService(ExecutionNodeAccessHierarchies.InputNodeAccessHierarchy.class);
//            TaskExecutionContext ctx = new DefaultTaskExecutionContext(
//                    localTaskNode,
//                    localTaskNode.getTaskProperties(),
//                    localTaskNode.getValidationContext(),
//                    (historyMaintained, typeValidationContext) -> detectMissingDependencies(localTaskNode, historyMaintained, inputHierarchy, typeValidationContext)
//            );
//            TaskExecuter taskExecuter = context.getService(TaskExecuter.class);
//            taskExecuter.execute(task, state, ctx);
//            localTaskNode.getPostAction().execute(task);
            return true;
        } else {
            return false;
        }
    }
}

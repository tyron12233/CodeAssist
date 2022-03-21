package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;

/**
 * Resolves dependencies to {@link TaskNode} objects. Uses the same logic as {@link #TASK_AS_TASK}.
 */
public class TaskNodeDependencyResolver implements DependencyResolver {
    private final TaskNodeFactory taskNodeFactory;

    public TaskNodeDependencyResolver(TaskNodeFactory taskNodeFactory) {
        this.taskNodeFactory = taskNodeFactory;
    }

    @Override
    public boolean resolve(Task task, Object node, final Action<? super Node> resolveAction) {
        return TASK_AS_TASK.resolve(task, node, resolved -> resolveAction.execute(taskNodeFactory.getOrCreateNode(resolved)));
    }

    @Override
    public boolean attachActionTo(Node value, Action<? super Task> action) {
        if (value instanceof TaskNode) {
            TaskNode taskNode = (TaskNode) value;
            taskNode.appendPostAction(action);
            return true;
        }
        return false;
    }
}
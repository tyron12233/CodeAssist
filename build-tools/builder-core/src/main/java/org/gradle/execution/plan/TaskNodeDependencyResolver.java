package org.gradle.execution.plan;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

/**
 * Resolves dependencies to {@link TaskNode} objects. Uses the same logic as {@link #TASK_AS_TASK}.
 */
@ServiceScope(Scopes.Build.class)
public class TaskNodeDependencyResolver implements DependencyResolver {
    private final TaskNodeFactory taskNodeFactory;

    public TaskNodeDependencyResolver(TaskNodeFactory taskNodeFactory) {
        this.taskNodeFactory = taskNodeFactory;
    }

    @Override
    public boolean resolve(Task task, Object node, final Action<? super Node> resolveAction) {
        return TASK_AS_TASK.resolve(task, node, resolved -> resolveAction.execute(taskNodeFactory.getOrCreateNode(resolved)));
    }
}
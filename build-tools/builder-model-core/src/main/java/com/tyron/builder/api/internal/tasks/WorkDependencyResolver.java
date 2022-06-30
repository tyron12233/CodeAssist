package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskDependency;

public interface WorkDependencyResolver<T> {
    /**
     * Resolves dependencies to a specific type.
     *
     * @return {@code true} if this resolver could resolve the given node, {@code false} otherwise.
     */
    boolean resolve(Task task, Object node, Action<? super T> resolveAction);

    boolean attachActionTo(T value, Action<? super Task> action);

    /**
     * Resolves dependencies to {@link Task} objects.
     */
    WorkDependencyResolver<Task> TASK_AS_TASK = new WorkDependencyResolver<Task>() {
        @Override
        public boolean resolve(Task originalTask, Object node, Action<? super Task> resolveAction) {
            if (node instanceof TaskDependency) {
                TaskDependency taskDependency = (TaskDependency) node;
                for (Task dependencyTask : taskDependency.getDependencies(originalTask)) {
                    resolveAction.execute(dependencyTask);
                }
                return true;
            }
            if (node instanceof Task) {
                resolveAction.execute((Task) node);
                return true;
            }
            return false;
        }

        @Override
        public boolean attachActionTo(Task task, Action<? super Task> action) {
            return false;
        }
    };
}

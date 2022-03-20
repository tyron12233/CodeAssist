package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.TaskResolver;

public class TaskExecutor {

    private final TaskResolver resolver;

    public TaskExecutor(TaskResolver resolver) {
        this.resolver = resolver;
    }

    // TODO: handle cases when name is not found.
    /**
     * Resolves the task using the given path and executes it.
     * @param path The task name
     */
    public void execute(String path) {
        Task task = resolver.resolveTask(path);
        if (task == null) {
            throw new RuntimeException("Failed to resolve " + path);
        }
        execute(task);
    }

    public void execute(Task task) {
        WorkDependencyResolver.TASK_AS_TASK
                .resolve(task, task.getTaskDependencies(), dependency -> {
                    if (task.equals(dependency)) {
                        throw new CircularDependencyException();
                    }
                    execute(dependency);
                });
        task.getActions().forEach(action -> action.execute(task));

        WorkDependencyResolver.TASK_AS_TASK
                .resolve(task, task.getMustRunAfter(), this::execute);
    }
}

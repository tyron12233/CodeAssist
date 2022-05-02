package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.TaskResolver;

import java.lang.reflect.Proxy;

public interface TaskContainerInternal extends TaskContainer, TaskResolver {

    /**
     * Performs work to discover more tasks.
     *
     * This method differs from {@link #realize} in that it does not realize the whole subtree.
     */
    void discoverTasks();

    /**
     * Ensures that all configuration has been applied to the given task, and the task is ready to be added to the task graph.
     */
    void prepareForExecution(Task task);


    default <T extends Task> TaskProvider<T> register(
            String name,
            Class<T> type,
            Proxy action) {
        System.out.println(name);
        return null;
    }
}

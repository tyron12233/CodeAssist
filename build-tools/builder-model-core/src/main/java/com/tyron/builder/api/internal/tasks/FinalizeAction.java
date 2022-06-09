package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;

/**
 * An action to run as soon as the given task completes, to perform some work before the outputs of the task are consumed by other tasks.
 *
 * <p>This should evolve into some real node to the graph with similar behaviour, but as a first step this is simply bolted on.
 */
public interface FinalizeAction extends Action<Task> {
    TaskDependencyContainer getDependencies();
}

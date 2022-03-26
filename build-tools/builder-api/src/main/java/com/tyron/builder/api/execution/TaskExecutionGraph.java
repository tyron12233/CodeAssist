package com.tyron.builder.api.execution;

import com.tyron.builder.api.Task;

import java.util.List;
import java.util.Set;

public interface TaskExecutionGraph {

    /**
     * <p>Determines whether the given task is included in the execution plan.</p>
     *
     * @param path the <em>absolute</em> path of the task.
     * @return true if a task with the given path is included in the execution plan.
     * @throws IllegalStateException When this graph has not been populated.
     */
    boolean hasTask(String path);

    /**
     * <p>Determines whether the given task is included in the execution plan.</p>
     *
     * @param task the task
     * @return true if the given task is included in the execution plan.
     * @throws IllegalStateException When this graph has not been populated.
     */
    boolean hasTask(Task task);

    /**
     * <p>Returns the tasks which are included in the execution plan. The tasks are returned in the order that they will
     * be executed.</p>
     *
     * @return The tasks. Returns an empty set if no tasks are to be executed.
     * @throws IllegalStateException When this graph has not been populated.
     */
    List<Task> getAllTasks();

    /**
     * <p>Returns the dependencies of a task which are part of the execution graph.</p>
     *
     * @return The tasks. Returns an empty set if there are no dependent tasks.
     * @throws IllegalStateException When this graph has not been populated or the task is not part of it.
     *
     * @since 4.6
     */
    Set<Task> getDependencies(Task task);
}

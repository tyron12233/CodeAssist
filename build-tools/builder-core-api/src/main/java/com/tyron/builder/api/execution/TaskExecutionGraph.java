package com.tyron.builder.api.execution;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;

import java.util.List;
import java.util.Set;

public interface TaskExecutionGraph {

    /**
     * <p>Adds a listener to this graph, to be notified when this graph is ready.</p>
     *
     * @param listener The listener to add. Does nothing if this listener has already been added.
     */
    void addTaskExecutionGraphListener(TaskExecutionGraphListener listener);

    /**
     * <p>Remove a listener from this graph.</p>
     *
     * @param listener The listener to remove. Does nothing if this listener was never added to this graph.
     */
    void removeTaskExecutionGraphListener(TaskExecutionGraphListener listener);

    /**
     * <p>Adds an action to be called when this graph has been populated. This graph is passed to the action as a
     * parameter.</p>
     *
     * @param action The action to execute when this graph has been populated.
     *
     * @since 3.1
     */
    void whenReady(Action<TaskExecutionGraph> action);

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

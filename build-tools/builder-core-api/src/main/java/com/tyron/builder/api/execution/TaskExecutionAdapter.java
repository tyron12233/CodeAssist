package com.tyron.builder.api.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskState;

/**
 * A {@link TaskExecutionListener} adapter class for receiving task execution events.
 *
 * The methods in this class are empty. This class exists as convenience for creating listener objects.
 */
public class TaskExecutionAdapter implements TaskExecutionListener {

    @Override
    public void beforeExecute(Task task) {}

    @Override
    public void afterExecute(Task task, TaskState state) {}

}

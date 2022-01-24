package com.tyron.builder.api;

import java.util.List;

public interface Task {

    /**
     * Returns the name of this task. This name uniquely identifies the task within its Project
     * @return The name of this task, never returns null.
     */
    String getName();

    /**
     * @return The sequence of {@link Action} objects which will be executed by this task, in the
     * order of execution
     */
    List<Action<? super Task>> getActions();

    /**
     * Sets the sequence of {@link Action} objects which will be executed by this task.
     * @param actions The actions
     */
    void setActions(List<Action<? super Task>> actions);
}

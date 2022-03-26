package com.tyron.builder.api.internal.invocation;

import com.tyron.builder.api.internal.StartParameterInternal;

/**
 * An object that describes the top level build action to perform, e.g. run some tasks, build a tooling model, run some tests, etc.
 */
public interface BuildAction {
    StartParameterInternal getStartParameter();

    /**
     * Will this action result in tasks being run?
     *
     * <p>An action may both run tasks and create a model.</p>
     */
    boolean isRunTasks();

    /**
     * Will this action return a tooling model as its result?
     *
     * <p>An action may both run tasks and create a model.</p>
     */
    boolean isCreateModel();
}
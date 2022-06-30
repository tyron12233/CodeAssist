package com.tyron.builder.api.tasks;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;

/**
 * A {@code TaskInstantiationException} is thrown when a task cannot be instantiated for some reason.
 */
@Contextual
public class TaskInstantiationException extends BuildException {
    public TaskInstantiationException(String message) {
        this(message, null);
    }

    public TaskInstantiationException(String message, Throwable cause) {
        super(message, cause);
    }
}

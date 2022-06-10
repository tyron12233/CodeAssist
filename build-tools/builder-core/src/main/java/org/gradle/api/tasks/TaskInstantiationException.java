package org.gradle.api.tasks;

import org.gradle.api.BuildException;
import org.gradle.internal.exceptions.Contextual;

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

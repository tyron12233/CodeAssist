package org.gradle.api.tasks;

import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;

/**
 * A {@code TaskInstantiationException} is thrown when a task cannot be instantiated for some reason.
 */
@Contextual
public class TaskInstantiationException extends GradleException {
    public TaskInstantiationException(String message) {
        this(message, null);
    }

    public TaskInstantiationException(String message, Throwable cause) {
        super(message, cause);
    }
}

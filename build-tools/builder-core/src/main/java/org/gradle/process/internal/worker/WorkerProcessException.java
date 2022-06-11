package org.gradle.process.internal.worker;

import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class WorkerProcessException extends GradleException {
    public WorkerProcessException(String message, Throwable cause) {
        super(message, cause);
    }

    public static WorkerProcessException runFailed(String workerDisplayName, Throwable failure) {
        return new WorkerProcessException(String.format("Failed to run %s", workerDisplayName), failure);
    }
}

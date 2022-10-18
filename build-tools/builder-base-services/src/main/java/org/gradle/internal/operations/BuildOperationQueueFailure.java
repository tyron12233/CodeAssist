package org.gradle.internal.operations;


import org.gradle.api.GradleException;

public class BuildOperationQueueFailure extends GradleException {
    public BuildOperationQueueFailure(String message) {
        super(message);
    }

    public BuildOperationQueueFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
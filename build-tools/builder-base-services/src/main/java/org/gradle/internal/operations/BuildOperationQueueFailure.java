package org.gradle.internal.operations;


import org.gradle.api.BuildException;

public class BuildOperationQueueFailure extends BuildException {
    public BuildOperationQueueFailure(String message) {
        super(message);
    }

    public BuildOperationQueueFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
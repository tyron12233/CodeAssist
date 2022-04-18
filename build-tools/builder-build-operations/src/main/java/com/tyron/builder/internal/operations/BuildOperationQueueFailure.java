package com.tyron.builder.internal.operations;


import com.tyron.builder.api.BuildException;

public class BuildOperationQueueFailure extends BuildException {
    public BuildOperationQueueFailure(String message) {
        super(message);
    }

    public BuildOperationQueueFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
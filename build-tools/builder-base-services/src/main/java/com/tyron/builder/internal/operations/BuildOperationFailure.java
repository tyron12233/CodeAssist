package com.tyron.builder.internal.operations;

import com.tyron.builder.api.BuildException;

public class BuildOperationFailure extends BuildException {
    private final BuildOperation operation;

    protected BuildOperationFailure(BuildOperation operation) {
        super();
        this.operation = operation;
    }

    protected BuildOperationFailure(BuildOperation operation, String message) {
        super(message);
        this.operation = operation;
    }

    protected BuildOperationFailure(BuildOperation operation, String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
    }

    public BuildOperation getOperation() {
        return operation;
    }
}
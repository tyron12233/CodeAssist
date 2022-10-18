package org.gradle.internal.operations;

import org.gradle.api.GradleException;

public class BuildOperationFailure extends GradleException {
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
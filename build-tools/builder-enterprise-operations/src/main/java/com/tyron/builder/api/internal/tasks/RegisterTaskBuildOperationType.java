package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * Represents a task realization for a task whose creation was deferred.
 *
 * @since 4.9
 */
public final class RegisterTaskBuildOperationType implements BuildOperationType<RegisterTaskBuildOperationType.Details, RegisterTaskBuildOperationType.Result> {

    public interface Details {
        String getBuildPath();

        String getTaskPath();

        /**
         * @see TaskIdentity#uniqueId
         */
        long getTaskId();

        boolean isReplacement();
    }

    public interface Result {
    }

    private RegisterTaskBuildOperationType() {
    }

}
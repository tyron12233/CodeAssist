package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * Represents a creation request for a task. Actual task may be realized later.
 *
 * @since 4.9
 */
public final class RealizeTaskBuildOperationType implements BuildOperationType<RealizeTaskBuildOperationType.Details, RealizeTaskBuildOperationType.Result> {

    public interface Details {
        String getBuildPath();

        String getTaskPath();

        /**
         * @see TaskIdentity#uniqueId
         */
        long getTaskId();

        boolean isReplacement();

        boolean isEager();
    }

    public interface Result {
    }

    private RealizeTaskBuildOperationType() {
    }

}
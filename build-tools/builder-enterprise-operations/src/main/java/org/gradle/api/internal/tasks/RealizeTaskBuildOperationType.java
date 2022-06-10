package org.gradle.api.internal.tasks;

import org.gradle.internal.operations.BuildOperationType;

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
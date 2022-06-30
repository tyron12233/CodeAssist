package com.tyron.builder.configuration.project;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * Execution of a project's beforeEvaluate hooks
 *
 * @since 4.9
 */
public final class NotifyProjectBeforeEvaluatedBuildOperationType implements BuildOperationType<NotifyProjectBeforeEvaluatedBuildOperationType.Details, NotifyProjectBeforeEvaluatedBuildOperationType.Result> {

    public interface Details {

        String getProjectPath();

        String getBuildPath();

    }

    public interface Result {

    }

    final static Result RESULT = new Result() {
    };

    private NotifyProjectBeforeEvaluatedBuildOperationType() {
    }

}
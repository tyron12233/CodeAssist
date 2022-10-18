package org.gradle.configuration.project;

import org.gradle.internal.operations.BuildOperationType;

/**
 * Execution of a project's afterEvaluate hooks
 *
 * @since 4.9
 */
public final class NotifyProjectAfterEvaluatedBuildOperationType implements BuildOperationType<NotifyProjectAfterEvaluatedBuildOperationType.Details, NotifyProjectAfterEvaluatedBuildOperationType.Result> {

    public interface Details {

        String getProjectPath();

        String getBuildPath();

    }

    public interface Result {

    }

    final static Result RESULT = new Result() {
    };

    private NotifyProjectAfterEvaluatedBuildOperationType() {
    }

}
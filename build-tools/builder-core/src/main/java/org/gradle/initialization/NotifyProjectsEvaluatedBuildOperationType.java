package org.gradle.initialization;

import org.gradle.internal.operations.BuildOperationType;

/**
 * An operation to run the projectsEvaluated lifecycle hook.
 *
 * @since 4.9
 */
public final class NotifyProjectsEvaluatedBuildOperationType implements BuildOperationType<NotifyProjectsEvaluatedBuildOperationType.Details, NotifyProjectsEvaluatedBuildOperationType.Result> {

    public interface Details {
        String getBuildPath();
    }

    public interface Result {
    }
}
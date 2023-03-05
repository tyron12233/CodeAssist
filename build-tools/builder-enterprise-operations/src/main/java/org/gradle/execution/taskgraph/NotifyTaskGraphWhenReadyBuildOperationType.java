package org.gradle.execution.taskgraph;

import org.gradle.internal.operations.BuildOperationType;

/**
 * Execution of a build's taskgraph.whenReady hooks
 *
 * @since 4.9
 */
public class NotifyTaskGraphWhenReadyBuildOperationType implements BuildOperationType<NotifyTaskGraphWhenReadyBuildOperationType.Details, NotifyTaskGraphWhenReadyBuildOperationType.Result> {

    public interface Details {

        String getBuildPath();

    }

    public interface Result {

    }

    public final static NotifyTaskGraphWhenReadyBuildOperationType.Result RESULT = new NotifyTaskGraphWhenReadyBuildOperationType.Result() {
    };

    private NotifyTaskGraphWhenReadyBuildOperationType() {
    }
}
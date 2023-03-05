package org.gradle.initialization;

import org.gradle.internal.operations.BuildOperationType;

public final class RunNestedBuildBuildOperationType implements BuildOperationType<RunNestedBuildBuildOperationType.Details, RunNestedBuildBuildOperationType.Result> {

    public interface Details {
        String getBuildPath();
    }

    public interface Result {
    }

}

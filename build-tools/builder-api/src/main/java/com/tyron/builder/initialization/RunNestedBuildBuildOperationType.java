package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.operations.BuildOperationType;

public final class RunNestedBuildBuildOperationType implements BuildOperationType<RunNestedBuildBuildOperationType.Details, RunNestedBuildBuildOperationType.Result> {

    public interface Details {
        String getBuildPath();
    }

    public interface Result {
    }

}

package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * Executing a task action.
 *
 * @since 5.6
 */
public final class ExecuteTaskActionBuildOperationType implements BuildOperationType<ExecuteTaskActionBuildOperationType.Details, ExecuteTaskActionBuildOperationType.Result> {

    // Info about the owning task can be inferred, and we don't provide any further info at this point.
    // This is largely to expose timing information about executed tasks

    public interface Details {
    }

    public interface Result {
    }

    public static final Details DETAILS_INSTANCE = new Details() {};
    public static final Result RESULT_INSTANCE = new Result() {};
}
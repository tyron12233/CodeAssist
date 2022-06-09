package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * @since 5.1
 */
public interface ExecuteWorkItemBuildOperationType extends BuildOperationType<ExecuteWorkItemBuildOperationType.Details, ExecuteWorkItemBuildOperationType.Result> {

    interface Details {
        /**
         * Returns the fully-qualified class name of work item's action.
         */
        String getClassName();

        /**
         * Returns the display name of the work item.
         */
        String getDisplayName();
    }

    interface Result {
    }

}

package org.gradle.tooling.provider.model.internal;

import org.gradle.internal.operations.BuildOperationType;

import javax.annotation.Nullable;

/**
 * Not used by build scan plugin.
 */
public interface QueryToolingModelBuildOperationType extends BuildOperationType<QueryToolingModelBuildOperationType.Details, QueryToolingModelBuildOperationType.Result> {
    interface Details {
        String getBuildPath();

        /**
         * Null for a model that is not project scoped.
         */
        @Nullable
        String getProjectPath();
    }

    interface Result {
    }
}

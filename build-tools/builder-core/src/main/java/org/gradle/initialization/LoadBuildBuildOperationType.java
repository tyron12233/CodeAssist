package org.gradle.initialization;


import org.gradle.internal.operations.BuildOperationType;

import javax.annotation.Nullable;

public final class LoadBuildBuildOperationType implements BuildOperationType<LoadBuildBuildOperationType.Details, LoadBuildBuildOperationType.Result> {
    public interface Details {
        /**
         * @since 4.6
         */
        String getBuildPath();

        /**
         * The build path of the build that caused this build to be included.
         *
         * Null for the root build.
         *
         * @since 4.10
         */
        @Nullable
        String getIncludedBy();
    }

    public interface Result {
    }
}
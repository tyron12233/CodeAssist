package com.tyron.builder.initialization;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * An operation to run the projectsLoaded lifecycle hook.
 *
 * @since 4.9
 */
public final class NotifyProjectsLoadedBuildOperationType implements BuildOperationType<NotifyProjectsLoadedBuildOperationType.Details, NotifyProjectsLoadedBuildOperationType.Result> {

    public interface Details {
        String getBuildPath();
    }

    public interface Result {
    }
}

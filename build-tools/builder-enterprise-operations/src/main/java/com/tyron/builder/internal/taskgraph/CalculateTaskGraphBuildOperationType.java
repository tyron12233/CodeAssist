package com.tyron.builder.internal.taskgraph;

import com.tyron.builder.internal.operations.BuildOperationType;

import java.util.List;

/**
 * Computing the task graph for a given build in the build tree based on the inputs and build configuration.
 *
 * @since 4.0
 */
public final class CalculateTaskGraphBuildOperationType implements BuildOperationType<CalculateTaskGraphBuildOperationType.Details, CalculateTaskGraphBuildOperationType.Result> {

    /**
     *
     * @since 6.2
     *
     * */
    public interface TaskIdentity {

        String getBuildPath();

        String getTaskPath();

        /**
         * @see com.tyron.builder.api.internal.project.taskfactory.TaskIdentity#uniqueId
         */
        long getTaskId();

    }

    /**
     *
     * @since 6.2
     *
     * */
    public interface PlannedTask {

        TaskIdentity getTask();

        List<TaskIdentity> getDependencies();

        List<TaskIdentity> getMustRunAfter();

        List<TaskIdentity> getShouldRunAfter();

        List<TaskIdentity> getFinalizedBy();

    }

    public interface Details {

        /**
         * The build path the calculated task graph belongs too.
         * Never null.
         *
         * @since 4.5
         */
        String getBuildPath();
    }

    public interface Result {

        /**
         * Lexicographically sorted.
         * Never null.
         * Never contains duplicates.
         */
        List<String> getRequestedTaskPaths();

        /**
         * Lexicographically sorted.
         * Never null.
         * Never contains duplicates.
         */
        List<String> getExcludedTaskPaths();

        /**
         * Capturing task execution plan details.
         *
         * @since 6.2
         */
        List<PlannedTask> getTaskPlan();
    }

    private CalculateTaskGraphBuildOperationType() {
    }

}
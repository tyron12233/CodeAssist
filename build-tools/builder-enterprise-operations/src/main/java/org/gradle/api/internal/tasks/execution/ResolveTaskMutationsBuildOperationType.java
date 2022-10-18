package org.gradle.api.internal.tasks.execution;

import org.gradle.internal.operations.BuildOperationType;

/**
 * Represents resolving the mutations of a task.
 * <p>
 * Before task execution ({@link org.gradle.api.internal.tasks.execution.ExecuteTaskBuildOperationType}),
 * the mutations of the task need to be resolved. The mutations of a task are the locations it may modify,
 * e.g. its outputs, destroyables and local state. In order to resolve the mutations, the task input and output
 * properties are detected as well. Since Gradle 7.5, resolving of the mutations happens in a separate node
 * in the execution graph.
 *
 * @since 7.6
 */
public final class ResolveTaskMutationsBuildOperationType implements BuildOperationType<ResolveTaskMutationsBuildOperationType.Details, ResolveTaskMutationsBuildOperationType.Result> {
    public interface Details {
        String getBuildPath();

        String getTaskPath();

        /**
         * @see org.gradle.api.internal.project.taskfactory.TaskIdentity#uniqueId
         */
        long getTaskId();
    }

    public interface Result {
    }
}